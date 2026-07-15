package dev.riddle.magicpaper.paperui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.riddle.magicpaper.conversation.ConversationOrchestrator
import dev.riddle.magicpaper.conversation.ConversationStateMachine
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.conversation.HandwritingRecognizer
import dev.riddle.magicpaper.conversation.HandwritingRecognitionError
import dev.riddle.magicpaper.conversation.HandwritingRecognitionStatus
import dev.riddle.magicpaper.conversation.TurnInputRouter
import dev.riddle.magicpaper.model.*
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PaperIntent
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaperPipelineTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun `text model uses router state machine orchestrator and reaches completed`() = runTest(dispatcher) {
        val events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 4)
        val provider = FakeModelProvider(events)
        val persistence = PipelinePersistence()
        val recognizer = RecordingRecognizer("what is written")
        val viewModel = pipelineViewModel(provider, persistence, recognizer)

        draw(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(PaperPhase.Thinking, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.canCancel)
        assertEquals("what is written", provider.recordedRequests.single().messages.single().text)
        assertEquals(1, recognizer.calls)

        events.emit(ModelEvent.TextDelta("answer"))
        dispatcher.scheduler.runCurrent()
        assertEquals(PaperPhase.Streaming, viewModel.state.value.phase)
        events.emit(ModelEvent.Completed(FinishReason.STOP))
        advanceUntilIdle()
        assertEquals(PaperPhase.Completed, viewModel.state.value.phase)
        assertFalse(viewModel.state.value.canCancel)
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `question mark shows help without recognition or provider request`() = runTest(dispatcher) {
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val recognizer = RecordingRecognizer("should not run")
        val viewModel = pipelineViewModel(provider, PipelinePersistence(), recognizer)
        questionMark().forEach { stroke ->
            viewModel.onPaperIntent(PaperIntent.StrokeStarted(stroke.id, stroke.tool, stroke.points.first()))
            stroke.points.drop(1).forEach { viewModel.onPaperIntent(PaperIntent.PointAdded(stroke.id, it)) }
            viewModel.onPaperIntent(PaperIntent.StrokeEnded(stroke.id))
        }
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.helpVisible)
        assertEquals(0, recognizer.calls)
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `recognition preparation failure retains ink and never requests provider`() = runTest(dispatcher) {
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val recognizer = FailingPreparationRecognizer()
        val viewModel = pipelineViewModel(provider, PipelinePersistence(), recognizer)
        draw(viewModel)
        val originalInk = viewModel.state.value.renderModel.strokes

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.RecognitionPreparationFailed, viewModel.state.value.phase)
        assertEquals(originalInk, viewModel.state.value.renderModel.strokes)
        assertTrue(provider.recordedRequests.isEmpty())
        assertEquals(listOf(HandwritingRecognitionStatus.PREPARING_MODEL), recognizer.statuses)
    }

    @Test fun `recognition preparation and local recognition have cancellable visible phases`() = runTest(dispatcher) {
        val recognizer = GatedStatusRecognizer()
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            PipelinePersistence(),
            recognizer,
        )
        draw(viewModel)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(PaperPhase.PreparingRecognition, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.canCancel)

        recognizer.modelReady.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertEquals(PaperPhase.Recognizing, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.canCancel)

        recognizer.recognitionReady.complete(Unit)
        advanceUntilIdle()
        assertEquals(PaperPhase.Completed, viewModel.state.value.phase)
    }

    @Test fun `cancel in every preparation phase restores submitted ink and merges later writing`() = runTest(dispatcher) {
        listOf(PaperPhase.Preparing, PaperPhase.PreparingRecognition, PaperPhase.Recognizing).forEach { target ->
            val persistence = PhaseCancellationPersistence(blockMarker = target == PaperPhase.Preparing)
            val recognizer = GatedStatusRecognizer()
            val handle = SavedStateHandle()
            val viewModel = pipelineViewModel(
                FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
                persistence,
                recognizer,
                savedStateHandle = handle,
            )
            draw(viewModel, "old")
            advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
            dispatcher.scheduler.runCurrent()
            if (target == PaperPhase.Recognizing) {
                recognizer.modelReady.complete(Unit)
                dispatcher.scheduler.runCurrent()
            }
            assertEquals(target, viewModel.state.value.phase)

            viewModel.onIntent(PaperUiIntent.Cancel)
            assertEquals(listOf("old"), viewModel.state.value.renderModel.strokes.map { it.id })

            draw(viewModel, "new")
            viewModel.onIntent(PaperUiIntent.ShowHelp)
            persistence.markerGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("old", "new"), viewModel.state.value.renderModel.strokes.map { it.id })
            assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
            assertFalse(persistence.current.interrupted)
            assertEquals(null, handle.get<Long>("paper_active_run_token"))
        }
    }

    @Test fun `blank recognition is shown as recognition failure and retains ink`() = runTest(dispatcher) {
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val viewModel = pipelineViewModel(provider, PipelinePersistence(), RecordingRecognizer("   "))
        draw(viewModel, "blank")

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperPhase.RecognitionFailed, viewModel.state.value.phase)
        assertEquals(listOf("blank"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `writing between submitted ownership and Preparing publication preserves and merges ink`() = runTest(dispatcher) {
        val ownershipSet = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val persistence = PhaseCancellationPersistence(blockMarker = false)
        val handle = SavedStateHandle()
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            persistence,
            RecordingRecognizer("old"),
            savedStateHandle = handle,
            beforePreparingStatePublished = {
                ownershipSet.complete(Unit)
                withContext(NonCancellable) { allowPublication.await() }
            },
        )
        draw(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        ownershipSet.await()

        val first = NormalizedPoint(.2f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("new", PaperTool.PEN, first))
        assertEquals(listOf("old"), viewModel.state.value.renderModel.strokes.map { it.id })
        viewModel.onPaperIntent(PaperIntent.PointAdded("new", NormalizedPoint(.3f, .3f, .01f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("new"))
        viewModel.onIntent(PaperUiIntent.ShowHelp)
        allowPublication.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old", "new"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertNotEquals(PaperPhase.Preparing, viewModel.state.value.phase)
        assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
        assertEquals(null, handle.get<Long>("paper_active_run_token"))
    }

    @Test fun `settings between submitted ownership and Preparing publication retains ink and rejects late clear`() = runTest(dispatcher) {
        val ownershipSet = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val persistence = PhaseCancellationPersistence(blockMarker = false)
        val handle = SavedStateHandle()
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            persistence,
            RecordingRecognizer("old"),
            savedStateHandle = handle,
            beforePreparingStatePublished = {
                ownershipSet.complete(Unit)
                withContext(NonCancellable) { allowPublication.await() }
            },
        )
        draw(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        ownershipSet.await()

        viewModel.onIntent(PaperUiIntent.OpenSettings)
        assertEquals(listOf("old"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertNotEquals(PaperPhase.Preparing, viewModel.state.value.phase)
        allowPublication.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertNotEquals(PaperPhase.Preparing, viewModel.state.value.phase)
        assertEquals(listOf("old"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
        assertEquals(null, handle.get<Long>("paper_active_run_token"))
    }

    @Test fun `writing before submitted ownership cancels old turn and preserves merged draft`() = runTest(dispatcher) {
        val beforeOwnership = CompletableDeferred<Unit>()
        val allowOwnership = CompletableDeferred<Unit>()
        val persistence = PhaseCancellationPersistence(blockMarker = false)
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            persistence,
            RecordingRecognizer("old"),
            beforeSubmittedInkOwnership = {
                beforeOwnership.complete(Unit)
                allowOwnership.await()
            },
        )
        draw(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        beforeOwnership.await()

        draw(viewModel, "new")
        viewModel.onIntent(PaperUiIntent.ShowHelp)
        allowOwnership.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old", "new"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `writing after deadline before active ownership invalidates scheduled turn`() = runTest(dispatcher) {
        val deadlineReturned = CompletableDeferred<Unit>()
        val allowOwnership = CompletableDeferred<Unit>()
        val persistence = PhaseCancellationPersistence(blockMarker = false)
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            persistence,
            RecordingRecognizer("old"),
            afterInactivityDeadlineBeforeJobOwnership = {
                deadlineReturned.complete(Unit)
                withContext(NonCancellable) { allowOwnership.await() }
            },
        )
        draw(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        deadlineReturned.await()

        draw(viewModel, "new")
        viewModel.onIntent(PaperUiIntent.ShowHelp)
        allowOwnership.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old", "new"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertNotEquals(PaperPhase.Preparing, viewModel.state.value.phase)
        assertEquals(listOf("old", "new"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `settings before submitted ownership cancels old turn without clearing ink`() = runTest(dispatcher) {
        val beforeOwnership = CompletableDeferred<Unit>()
        val allowOwnership = CompletableDeferred<Unit>()
        val persistence = PhaseCancellationPersistence(blockMarker = false)
        val viewModel = pipelineViewModel(
            FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
            persistence,
            RecordingRecognizer("old"),
            beforeSubmittedInkOwnership = {
                beforeOwnership.complete(Unit)
                allowOwnership.await()
            },
        )
        draw(viewModel, "old")
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        beforeOwnership.await()

        viewModel.onIntent(PaperUiIntent.OpenSettings)
        allowOwnership.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("old"), viewModel.state.value.renderModel.strokes.map { it.id })
        assertEquals(listOf("old"), persistence.current.strokes.map { it.id })
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `recovery preference and stream delta updates do not overwrite each other`() = runTest(dispatcher) {
        val loadGate = CompletableDeferred<Unit>()
        val persistence = DelayedPipelinePersistence(loadGate)
        val preferences = PipelinePreferences()
        val events = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 2)
        val viewModel = pipelineViewModel(FakeModelProvider(events), persistence, RecordingRecognizer("new page"), preferences)
        draw(viewModel)
        loadGate.complete(Unit)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()

        preferences.portraitLocked.value = true
        events.emit(ModelEvent.TextDelta("delta"))
        dispatcher.scheduler.runCurrent()

        assertEquals("delta", viewModel.state.value.reply)
        assertTrue(viewModel.state.value.portraitLocked)
        assertEquals(PaperPhase.Streaming, viewModel.state.value.phase)
    }

    @Test fun `view model store clear cancels transport and clears persisted marker`() = runTest(dispatcher) {
        val closed = CompletableDeferred<Unit>()
        val persistence = PipelinePersistence()
        val provider = FakeModelProvider(flow {
            emit(ModelEvent.TextDelta("partial"))
            try { awaitCancellation() } finally { closed.complete(Unit) }
        })
        val instance = pipelineViewModel(provider, persistence, RecordingRecognizer("page"))
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return instance as T
            }
        }
        val owned = ViewModelProvider(store, factory)[PaperViewModel::class.java]
        draw(owned)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertTrue(persistence.current.interrupted)

        store.clear()
        advanceUntilIdle()

        assertTrue(closed.isCompleted)
        assertFalse(persistence.current.interrupted)
    }

    @Test fun `vision request owns raster image until provider completion then deletes it`() = runTest(dispatcher) {
        val directory = File("build/tmp/vision-${System.nanoTime()}").apply { mkdirs() }
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val viewModel = PaperViewModel(
            modelSelection = ModelSelection {
                SelectedModel(provider, "vision", ModelCapabilities(streaming = true, vision = true))
            },
            persistence = PipelinePersistence(),
            preferences = PipelinePreferences(),
            turnInputRouter = TurnInputRouter(
                PageRasterizer(directory, 1000, 1000, dispatcher = dispatcher),
                RecordingRecognizer("unused"),
            ),
            stateMachine = ConversationStateMachine(),
            orchestratorFactory = ::ConversationOrchestrator,
            savedStateHandle = SavedStateHandle(),
            workerDispatcher = dispatcher,
            clock = TestAppClock(dispatcher.scheduler),
        )
        draw(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertTrue(provider.recordedRequests.single().messages.single().imageDataUrl?.startsWith("data:image/png;base64,") == true)
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("riddle-page-") })
    }

    private fun pipelineViewModel(
        provider: ModelProvider,
        persistence: PaperPersistence,
        recognizer: HandwritingRecognizer,
        preferences: PipelinePreferences = PipelinePreferences(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        beforeSubmittedInkOwnership: suspend () -> Unit = {},
        beforePreparingStatePublished: suspend () -> Unit = {},
        afterInactivityDeadlineBeforeJobOwnership: suspend () -> Unit = {},
    ) = PaperViewModel(
        modelSelection = ModelSelection { SelectedModel(provider, "configured", provider.descriptor.capabilities) },
        persistence = persistence,
        preferences = preferences,
        turnInputRouter = TurnInputRouter(PageRasterizer(File("build/tmp/pipeline"), 1000, 1000, dispatcher = dispatcher), recognizer),
        stateMachine = ConversationStateMachine(),
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = savedStateHandle,
        workerDispatcher = dispatcher,
        clock = TestAppClock(dispatcher.scheduler),
            beforeSubmittedInkOwnership = beforeSubmittedInkOwnership,
            beforePreparingStatePublished = beforePreparingStatePublished,
            afterInactivityDeadlineBeforeJobOwnership = afterInactivityDeadlineBeforeJobOwnership,
    )

    private fun draw(viewModel: PaperViewModel, id: String = "s") {
        val a = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted(id, PaperTool.PEN, a))
        viewModel.onPaperIntent(PaperIntent.PointAdded(id, NormalizedPoint(.4f, .5f, .01f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded(id))
    }

    private fun questionMark() = listOf(
        PaperStroke("hook", PaperTool.PEN, listOf(point(.25f,.3f),point(.3f,.15f),point(.5f,.1f),point(.7f,.2f),point(.68f,.38f),point(.52f,.52f),point(.5f,.6f))),
        PaperStroke("dot", PaperTool.PEN, listOf(point(.5f,.8f), point(.5f,.82f))),
    )
    private fun point(x: Float, y: Float) = NormalizedPoint(x,y,.01f)
}

private class RecordingRecognizer(private val result: String) : HandwritingRecognizer {
    var calls = 0
    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String> {
        calls++
        return Result.success(result)
    }
}

private class FailingPreparationRecognizer : HandwritingRecognizer {
    val statuses = mutableListOf<HandwritingRecognitionStatus>()

    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String> =
        Result.failure(HandwritingRecognitionError.ModelDownloadFailed(locale.toLanguageTag()))

    override suspend fun recognize(
        strokes: List<PaperStroke>,
        locale: Locale,
        onStatus: (HandwritingRecognitionStatus) -> Unit,
    ): Result<String> {
        onStatus(HandwritingRecognitionStatus.PREPARING_MODEL)
        statuses += HandwritingRecognitionStatus.PREPARING_MODEL
        return Result.failure(HandwritingRecognitionError.ModelDownloadFailed(locale.toLanguageTag()))
    }
}

private class GatedStatusRecognizer : HandwritingRecognizer {
    val modelReady = CompletableDeferred<Unit>()
    val recognitionReady = CompletableDeferred<Unit>()

    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String> =
        Result.success("recognized")

    override suspend fun recognize(
        strokes: List<PaperStroke>,
        locale: Locale,
        onStatus: (HandwritingRecognitionStatus) -> Unit,
    ): Result<String> {
        onStatus(HandwritingRecognitionStatus.PREPARING_MODEL)
        modelReady.await()
        onStatus(HandwritingRecognitionStatus.RECOGNIZING)
        recognitionReady.await()
        return Result.success("recognized")
    }
}

private class PipelinePersistence : PaperPersistence {
    var current = PaperRecovery()
    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { current = PaperRecovery(strokes, true) }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}

private class PhaseCancellationPersistence(blockMarker: Boolean) : PaperPersistence {
    var current = PaperRecovery()
    val markerGate = CompletableDeferred<Unit>().also { if (!blockMarker) it.complete(Unit) }
    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) {
        markerGate.await()
        current = PaperRecovery(strokes, true)
    }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}

private class PipelinePreferences : PaperPreferences {
    override val portraitLocked = MutableStateFlow(false)
    override val settingsEntryMode = MutableStateFlow(dev.riddle.magicpaper.model.SettingsEntryMode.MAGIC_RUNE_BUTTON)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: dev.riddle.magicpaper.model.SettingsEntryMode) {
        settingsEntryMode.value = mode
    }
}

private class DelayedPipelinePersistence(private val gate: CompletableDeferred<Unit>) : PaperPersistence {
    private var current = PaperRecovery(listOf(PaperStroke("old", PaperTool.PEN, listOf(NormalizedPoint(.8f, .8f, .01f)))))
    override suspend fun load(): PaperRecovery { gate.await(); return current }
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { current = PaperRecovery(strokes, true) }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}
