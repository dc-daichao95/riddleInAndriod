package dev.riddle.magicpaper.paperui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.riddle.magicpaper.conversation.ConversationOrchestrator
import dev.riddle.magicpaper.conversation.ConversationStateMachine
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.conversation.HandwritingRecognizer
import dev.riddle.magicpaper.conversation.TurnInputRouter
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelError
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PaperIntent
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaperLifecycleTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `owner clear cancels pending inactivity before provider starts`() = runTest(dispatcher) {
        val provider = FakeModelProvider(emptyList())
        val preferences = LifecyclePreferences()
        val (store, viewModel) = ownedViewModel(provider, LifecyclePersistence(), preferences)
        drawStroke(viewModel)

        store.clear()
        preferences.portraitLocked.value = true
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertTrue(provider.recordedRequests.isEmpty())
        assertFalse(viewModel.state.value.portraitLocked)
    }

    @Test fun `owner clear preserves original draft and rejects late work`() = runTest(dispatcher) {
        val providerEvents = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 2)
        val providerCancelled = CompletableDeferred<Unit>()
        val provider = FakeModelProvider(providerEvents.onCompletion { providerCancelled.complete(Unit) })
        val persistence = BlockingCleanupPersistence()
        val preferences = LifecyclePreferences()
        val (store, viewModel) = ownedViewModel(provider, persistence, preferences)
        val originalStroke = drawStroke(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        providerEvents.tryEmit(ModelEvent.TextDelta("partial"))
        dispatcher.scheduler.runCurrent()
        assertTrue(persistence.current.interrupted)
        val stateAtClear = viewModel.state.value

        store.clear()
        dispatcher.scheduler.runCurrent()
        persistence.cleanupStarted.await()
        providerEvents.tryEmit(ModelEvent.TextDelta(" late"))
        preferences.portraitLocked.value = true
        preferences.settingsEntryMode.value = SettingsEntryMode.THREE_FINGER_LONG_PRESS
        dispatcher.scheduler.runCurrent()
        persistence.allowCleanup.complete(Unit)
        advanceUntilIdle()

        assertTrue(providerCancelled.isCompleted)
        assertEquals(listOf("mark-streaming", "save-clean-draft"), persistence.events)
        assertEquals(listOf(originalStroke), persistence.current.strokes)
        assertFalse(persistence.current.interrupted)
        assertEquals(stateAtClear, viewModel.state.value)
    }

    @Test fun `merged draft wins when old cleanup already owns persistence lock`() = runTest(dispatcher) {
        val persistence = DraftRacePersistence(blockCleanup = true)
        val provider = FakeModelProvider(MutableSharedFlow())
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences())
        drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()

        startStroke(viewModel, "new")
        dispatcher.scheduler.runCurrent()
        persistence.cleanupStarted.await()
        endStroke(viewModel, "new")
        viewModel.onIntent(PaperUiIntent.OpenSettings)
        persistence.allowCleanup.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old-cleanup", "new-save"), persistence.saves)
        assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `old cleanup is skipped when new draft revision advances behind marker lock`() = runTest(dispatcher) {
        val persistence = DraftRacePersistence(blockMarker = true)
        val provider = FakeModelProvider(MutableSharedFlow())
        val handle = SavedStateHandle()
        val (store, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences(), handle)
        drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        persistence.markerStarted.await()

        startStroke(viewModel, "new")
        endStroke(viewModel, "new")
        viewModel.onIntent(PaperUiIntent.OpenSettings)
        store.clear()
        persistence.allowMarker.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("new-save"), persistence.saves)
        assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
        assertEquals(null, handle.get<Long>(RUN_TOKEN_KEY))
    }

    @Test fun `new draft wins when interrupted recovery load completes late`() = runTest(dispatcher) {
        val persistence = SlowRecoveryPersistence()
        val (_, viewModel) = ownedViewModel(
            FakeModelProvider(emptyList()),
            persistence,
            LifecyclePreferences(),
        )
        dispatcher.scheduler.runCurrent()
        persistence.loadStarted.await()

        drawStroke(viewModel, "new")
        viewModel.onIntent(PaperUiIntent.OpenSettings)
        persistence.allowLoad.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `owner clear immediately after stroke end still persists latest draft`() = runTest(dispatcher) {
        val persistence = PendingSavePersistence()
        val (store, viewModel) = ownedViewModel(
            FakeModelProvider(emptyList()),
            persistence,
            LifecyclePreferences(),
        )
        drawStroke(viewModel, "new")

        store.clear()
        persistence.allowSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `captured draft save completes before marker is allowed`() = runTest(dispatcher) {
        val persistence = PendingSavePersistence()
        val (_, viewModel) = ownedViewModel(
            FakeModelProvider(MutableSharedFlow()),
            persistence,
            LifecyclePreferences(),
        )
        drawStroke(viewModel, "old")
        dispatcher.scheduler.runCurrent()
        persistence.saveStarted.await()

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, persistence.markerCalls)

        persistence.allowSave.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("clean-save", "mark-streaming"), persistence.events)
    }

    @Test fun `new generation wins when completion clear already owns lock`() = runTest(dispatcher) {
        val events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 2)
        val persistence = TerminalRacePersistence(blockClear = true)
        val handle = SavedStateHandle()
        val (_, viewModel) = ownedViewModel(FakeModelProvider(events), persistence, LifecyclePreferences(), handle)
        drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        events.tryEmit(ModelEvent.TextDelta("partial"))
        dispatcher.scheduler.runCurrent()
        events.tryEmit(ModelEvent.Completed(dev.riddle.magicpaper.model.FinishReason.STOP))
        dispatcher.scheduler.runCurrent()
        persistence.clearStarted.await()

        viewModel.onIntent(PaperUiIntent.Cancel)
        val futureToken = 999L
        handle[RUN_TOKEN_KEY] = futureToken
        persistence.allowClear.complete(Unit)
        advanceUntilIdle()

        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertEquals(futureToken, handle.get<Long>(RUN_TOKEN_KEY))
    }

    @Test fun `new generation wins when failure cleanup already owns lock`() = runTest(dispatcher) {
        val events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 2)
        val persistence = TerminalRacePersistence(blockFailureSave = true)
        val handle = SavedStateHandle()
        val (_, viewModel) = ownedViewModel(FakeModelProvider(events), persistence, LifecyclePreferences(), handle)
        drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        events.tryEmit(ModelEvent.TextDelta("partial"))
        dispatcher.scheduler.runCurrent()
        events.tryEmit(ModelEvent.Failed(ModelError.Network()))
        dispatcher.scheduler.runCurrent()
        persistence.failureSaveStarted.await()

        viewModel.onIntent(PaperUiIntent.Cancel)
        val futureToken = 999L
        handle[RUN_TOKEN_KEY] = futureToken
        persistence.allowFailureSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertEquals(futureToken, handle.get<Long>(RUN_TOKEN_KEY))
    }

    @Test fun `current marker writes exact run token and owner clear removes it`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 1)
        val (store, viewModel) = ownedViewModel(
            FakeModelProvider(events),
            LifecyclePersistence(),
            LifecyclePreferences(),
            handle,
        )
        drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()

        val token = handle.get<Long>(RUN_TOKEN_KEY)
        assertTrue(token != null)

        store.clear()
        advanceUntilIdle()
        assertEquals(null, handle.get<Long>(RUN_TOKEN_KEY))
    }

    @Test fun `process recovery recognizes new run token and legacy boolean`() = runTest(dispatcher) {
        val stroke = PaperStroke("old", PaperTool.PEN, listOf(NormalizedPoint(.3f, .3f, .01f)))
        val tokenHandle = SavedStateHandle(mapOf(RUN_TOKEN_KEY to 41L))
        val (_, tokenViewModel) = ownedViewModel(
            FakeModelProvider(emptyList()),
            StaticRecoveryPersistence(PaperRecovery(listOf(stroke))),
            LifecyclePreferences(),
            tokenHandle,
        )
        advanceUntilIdle()
        assertEquals(PaperPhase.Interrupted, tokenViewModel.state.value.phase)
        assertEquals(null, tokenHandle.get<Long>(RUN_TOKEN_KEY))

        val legacyHandle = SavedStateHandle(mapOf(LEGACY_ACTIVE_KEY to true))
        val (_, legacyViewModel) = ownedViewModel(
            FakeModelProvider(emptyList()),
            StaticRecoveryPersistence(PaperRecovery(listOf(stroke))),
            LifecyclePreferences(),
            legacyHandle,
        )
        advanceUntilIdle()
        assertEquals(PaperPhase.Interrupted, legacyViewModel.state.value.phase)
        assertFalse(legacyHandle.get<Boolean>(LEGACY_ACTIVE_KEY) == true)
    }

    @Test fun `late recovery cleanup cannot clear a newer run token`() = runTest(dispatcher) {
        val persistence = SlowRecoveryPersistence()
        val handle = SavedStateHandle(mapOf(RUN_TOKEN_KEY to 1L))
        ownedViewModel(FakeModelProvider(emptyList()), persistence, LifecyclePreferences(), handle)
        dispatcher.scheduler.runCurrent()
        persistence.loadStarted.await()

        handle[RUN_TOKEN_KEY] = 2L
        persistence.allowLoad.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, handle.get<Long>(RUN_TOKEN_KEY))
    }

    @Test fun `marker failure before side effect restores source draft and fails`() = runTest(dispatcher) {
        val persistence = MarkerFailurePersistence(writeBeforeThrow = false)
        val provider = FakeModelProvider(emptyList())
        val handle = SavedStateHandle()
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences(), handle)
        val original = drawStroke(viewModel, "old")

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.Failed, viewModel.state.value.phase)
        assertEquals(listOf(original), viewModel.state.value.renderModel.strokes)
        assertEquals(listOf("marker-attempt", "clean-draft"), persistence.events)
        assertFalse(persistence.current.interrupted)
        assertEquals(null, handle.get<Long>(RUN_TOKEN_KEY))
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `marker failure after side effect cleans interrupted marker and fails`() = runTest(dispatcher) {
        val persistence = MarkerFailurePersistence(writeBeforeThrow = true)
        val provider = FakeModelProvider(emptyList())
        val handle = SavedStateHandle()
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences(), handle)
        val original = drawStroke(viewModel, "old")

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.Failed, viewModel.state.value.phase)
        assertEquals(listOf(original), viewModel.state.value.renderModel.strokes)
        assertEquals(listOf("marker-attempt", "marker-written", "clean-draft"), persistence.events)
        assertFalse(persistence.current.interrupted)
        assertEquals(null, handle.get<Long>(RUN_TOKEN_KEY))
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `draft save failure reaches failed without marker or provider request`() = runTest(dispatcher) {
        val persistence = DraftSaveFailurePersistence()
        val provider = FakeModelProvider(emptyList())
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences())
        val original = drawStroke(viewModel, "old")

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.Failed, viewModel.state.value.phase)
        assertEquals(listOf(original), viewModel.state.value.renderModel.strokes)
        assertEquals(0, persistence.markerCalls)
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `marker cleanup failure preserves failed state without starting provider`() = runTest(dispatcher) {
        val persistence = MarkerFailurePersistence(writeBeforeThrow = true, cleanupShouldFail = true)
        val provider = FakeModelProvider(emptyList())
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences())
        val original = drawStroke(viewModel, "old")

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.Failed, viewModel.state.value.phase)
        assertEquals(listOf(original), viewModel.state.value.renderModel.strokes)
        assertEquals(listOf("marker-attempt", "marker-written", "clean-draft"), persistence.events)
        assertTrue(persistence.current.interrupted)
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `cancel after marker write cleans pre-token marker and preserves future token`() = runTest(dispatcher) {
        val persistence = CancellableMarkerPersistence(blockCleanup = true)
        val provider = FakeModelProvider(emptyList())
        val handle = SavedStateHandle()
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences(), handle)
        val original = drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        persistence.markerWritten.await()
        handle[LEGACY_ACTIVE_KEY] = true

        viewModel.onIntent(PaperUiIntent.Cancel)
        dispatcher.scheduler.runCurrent()
        assertTrue(persistence.cleanupStarted.isCompleted)
        val futureToken = 999L
        handle[RUN_TOKEN_KEY] = futureToken
        persistence.allowCleanup.complete(Unit)
        advanceUntilIdle()

        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertTrue(persistence.markerCancelled.isCompleted)
        assertEquals(listOf(original), persistence.current.strokes)
        assertFalse(persistence.current.interrupted)
        assertFalse(handle.get<Boolean>(LEGACY_ACTIVE_KEY) == true)
        assertEquals(futureToken, handle.get<Long>(RUN_TOKEN_KEY))
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `cancel cleanup failure keeps marker recoverable without uncaught failure`() = runTest(dispatcher) {
        val persistence = CancellableMarkerPersistence(cleanupShouldFail = true)
        val provider = FakeModelProvider(emptyList())
        val (_, viewModel) = ownedViewModel(provider, persistence, LifecyclePreferences())
        val original = drawStroke(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        persistence.markerWritten.await()

        viewModel.onIntent(PaperUiIntent.Cancel)
        advanceUntilIdle()

        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertTrue(persistence.markerCancelled.isCompleted)
        assertEquals(listOf(original), persistence.current.strokes)
        assertTrue(persistence.current.interrupted)
        assertEquals(listOf("marker-written", "clean-draft", "cleanup-failed"), persistence.events)
        assertTrue(provider.recordedRequests.isEmpty())
    }

    private fun ownedViewModel(
        provider: FakeModelProvider,
        persistence: PaperPersistence,
        preferences: LifecyclePreferences,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): Pair<ViewModelStore, PaperViewModel> {
        val instance = PaperViewModel(
            modelSelection = ModelSelection {
                SelectedModel(provider, "fake", ModelCapabilities(streaming = true, vision = false))
            },
            persistence = persistence,
            preferences = preferences,
            turnInputRouter = TurnInputRouter(
                PageRasterizer(File("build/tmp/lifecycle"), 1000, 1000, dispatcher = dispatcher),
                object : HandwritingRecognizer {
                    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale) =
                        Result.success("recognized page")
                },
            ),
            stateMachine = ConversationStateMachine(),
            orchestratorFactory = ::ConversationOrchestrator,
            savedStateHandle = savedStateHandle,
            workerDispatcher = dispatcher,
            clock = TestAppClock(dispatcher.scheduler),
        )
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return instance as T
            }
        }
        return store to ViewModelProvider(store, factory)[PaperViewModel::class.java]
    }

    private fun drawStroke(viewModel: PaperViewModel, id: String = "lifecycle-stroke"): PaperStroke {
        startStroke(viewModel, id)
        endStroke(viewModel, id)
        val first = NormalizedPoint(.1f, .2f, .01f)
        val second = NormalizedPoint(.4f, .5f, .02f)
        return PaperStroke(id, PaperTool.PEN, listOf(first, second))
    }

    private fun startStroke(viewModel: PaperViewModel, id: String) {
        val first = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted(id, PaperTool.PEN, first))
    }

    private fun endStroke(viewModel: PaperViewModel, id: String) {
        viewModel.onPaperIntent(PaperIntent.PointAdded(id, NormalizedPoint(.4f, .5f, .02f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded(id))
    }
}

private const val RUN_TOKEN_KEY = "paper_active_run_token"
private const val LEGACY_ACTIVE_KEY = "paper_active_turn"

private class LifecyclePersistence : PaperPersistence {
    override suspend fun load() = PaperRecovery()
    override suspend fun saveDraft(recovery: PaperRecovery) = Unit
    override suspend fun markStreaming(strokes: List<PaperStroke>) = Unit
    override suspend fun clearStreamingAndDraft() = Unit
}

private class StaticRecoveryPersistence(private var current: PaperRecovery) : PaperPersistence {
    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { current = PaperRecovery(strokes, true) }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}

private class MarkerFailurePersistence(
    private val writeBeforeThrow: Boolean,
    private val cleanupShouldFail: Boolean = false,
) : PaperPersistence {
    val events = mutableListOf<String>()
    var current = PaperRecovery()
    private var initialDraftSaved = false

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        if (initialDraftSaved) events += "clean-draft"
        initialDraftSaved = true
        if (events.isNotEmpty() && cleanupShouldFail) error("cleanup failure")
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        events += "marker-attempt"
        if (writeBeforeThrow) {
            current = PaperRecovery(strokes, interrupted = true)
            events += "marker-written"
        }
        error("marker failure")
    }
    override suspend fun clearStreamingAndDraft() = Unit
}

private class DraftSaveFailurePersistence : PaperPersistence {
    var markerCalls = 0
    override suspend fun load() = PaperRecovery()
    override suspend fun saveDraft(recovery: PaperRecovery) { error("draft save failure") }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { markerCalls++ }
    override suspend fun clearStreamingAndDraft() = Unit
}

private class CancellableMarkerPersistence(
    private val blockCleanup: Boolean = false,
    private val cleanupShouldFail: Boolean = false,
) : PaperPersistence {
    val markerWritten = CompletableDeferred<Unit>()
    val markerCancelled = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val allowCleanup = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    var current = PaperRecovery()
    private var initialDraftSaved = false

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        if (!initialDraftSaved) {
            initialDraftSaved = true
            current = recovery
            return
        }
        events += "clean-draft"
        cleanupStarted.complete(Unit)
        if (blockCleanup) allowCleanup.await()
        if (cleanupShouldFail) {
            events += "cleanup-failed"
            error("cleanup failure")
        }
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        current = PaperRecovery(strokes, interrupted = true)
        events += "marker-written"
        markerWritten.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            markerCancelled.complete(Unit)
        }
    }
    override suspend fun clearStreamingAndDraft() = Unit
}

private class BlockingCleanupPersistence : PaperPersistence {
    val cleanupStarted = CompletableDeferred<Unit>()
    val allowCleanup = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    var current = PaperRecovery()

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        if (!current.interrupted) {
            current = recovery
            return
        }
        cleanupStarted.complete(Unit)
        allowCleanup.await()
        events += "save-clean-draft"
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        events += "mark-streaming"
        current = PaperRecovery(strokes, interrupted = true)
    }
    override suspend fun clearStreamingAndDraft() {
        events += "clear-streaming"
        current = PaperRecovery()
    }
}

private class DraftRacePersistence(
    private val blockCleanup: Boolean = false,
    private val blockMarker: Boolean = false,
) : PaperPersistence {
    val cleanupStarted = CompletableDeferred<Unit>()
    val allowCleanup = CompletableDeferred<Unit>()
    val markerStarted = CompletableDeferred<Unit>()
    val allowMarker = CompletableDeferred<Unit>()
    val saves = mutableListOf<String>()
    var current = PaperRecovery()
    private var initialOldSaved = false

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        val id = recovery.strokes.singleOrNull()?.id
        if (id == "old") {
            if (initialOldSaved) {
                cleanupStarted.complete(Unit)
                if (blockCleanup) allowCleanup.await()
                saves += "old-cleanup"
            } else {
                initialOldSaved = true
            }
        } else if (recovery.strokes.any { it.id == "new" }) {
            saves += "new-save"
        }
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        markerStarted.complete(Unit)
        if (blockMarker) withContext(NonCancellable) { allowMarker.await() }
        current = PaperRecovery(strokes, interrupted = true)
    }
    override suspend fun clearStreamingAndDraft() {
        current = PaperRecovery()
    }
}

private class SlowRecoveryPersistence : PaperPersistence {
    val loadStarted = CompletableDeferred<Unit>()
    val allowLoad = CompletableDeferred<Unit>()
    var current = PaperRecovery(
        listOf(PaperStroke("old", PaperTool.PEN, listOf(NormalizedPoint(.8f, .8f, .01f)))),
        interrupted = true,
    )

    override suspend fun load(): PaperRecovery {
        loadStarted.complete(Unit)
        allowLoad.await()
        return current
    }
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { current = PaperRecovery(strokes, true) }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}

private class PendingSavePersistence : PaperPersistence {
    val saveStarted = CompletableDeferred<Unit>()
    val allowSave = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()
    var markerCalls = 0
    var current = PaperRecovery()

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        saveStarted.complete(Unit)
        allowSave.await()
        events += "clean-save"
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        markerCalls++
        events += "mark-streaming"
        current = PaperRecovery(strokes, interrupted = true)
    }
    override suspend fun clearStreamingAndDraft() = Unit
}

private class TerminalRacePersistence(
    private val blockClear: Boolean = false,
    private val blockFailureSave: Boolean = false,
) : PaperPersistence {
    val clearStarted = CompletableDeferred<Unit>()
    val allowClear = CompletableDeferred<Unit>()
    val failureSaveStarted = CompletableDeferred<Unit>()
    val allowFailureSave = CompletableDeferred<Unit>()
    var current = PaperRecovery()
    private var initialOldSaved = false

    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) {
        val id = recovery.strokes.singleOrNull()?.id
        if (id == "old" && initialOldSaved && blockFailureSave) {
            failureSaveStarted.complete(Unit)
            withContext(NonCancellable) { allowFailureSave.await() }
        }
        if (id == "old") initialOldSaved = true
        current = recovery
    }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        current = PaperRecovery(strokes, interrupted = true)
    }
    override suspend fun clearStreamingAndDraft() {
        clearStarted.complete(Unit)
        if (blockClear) withContext(NonCancellable) { allowClear.await() }
        current = PaperRecovery()
    }
}

private class LifecyclePreferences : PaperPreferences {
    override val portraitLocked = MutableStateFlow(false)
    override val settingsEntryMode = MutableStateFlow(SettingsEntryMode.MAGIC_RUNE_BUTTON)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) { settingsEntryMode.value = mode }
}
