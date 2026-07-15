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
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PaperIntent
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputDissolveTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `input dissolve retains source through stages zero to thirteen then clears`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val observed = mutableListOf<Pair<Int?, List<PaperStroke>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state
                .drop(1)
                .map { it.renderModel.dissolveStage to it.renderModel.strokes }
                .distinctUntilChanged()
                .collect(observed::add)
        }

        val source = draw(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, viewModel.state.value.renderModel.dissolveStage)
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)

        repeat(13) {
            advanceTimeBy(70L)
            dispatcher.scheduler.runCurrent()
        }

        assertEquals(13, viewModel.state.value.renderModel.dissolveStage)
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)
        assertEquals((0..13).toList(), observed.mapNotNull { it.first })

        advanceTimeBy(69L)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)

        advanceTimeBy(1L)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.renderModel.strokes.isEmpty())
    }

    @Test fun `dissolve deadline ignores wall clock jumps and advances only at elapsed boundary`() = runTest(dispatcher) {
        val clock = DissolveClock(testScheduler.currentTime, wallClockMillis = 1_000_000L)
        val viewModel = viewModel(clock = clock)
        draw(viewModel)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        clock.elapsedRealtimeMillis += PaperViewModel.INACTIVITY_MILLIS
        dispatcher.scheduler.runCurrent()
        assertEquals(0, viewModel.state.value.renderModel.dissolveStage)

        clock.wallClockMillis += 86_400_000L
        advanceTimeBy(69L)
        clock.elapsedRealtimeMillis += 69L
        dispatcher.scheduler.runCurrent()
        assertEquals(0, viewModel.state.value.renderModel.dissolveStage)

        clock.wallClockMillis = 0L
        advanceTimeBy(1L)
        clock.elapsedRealtimeMillis += 1L
        dispatcher.scheduler.runCurrent()
        assertEquals(1, viewModel.state.value.renderModel.dissolveStage)
        assertEquals(0, clock.wallClockReads)

        viewModel.onIntent(PaperUiIntent.Cancel)
        dispatcher.scheduler.runCurrent()
    }

    @Test fun `cancelling dissolve restores source and stale stages cannot clear it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val source = draw(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 140L)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, viewModel.state.value.renderModel.dissolveStage)

        viewModel.onIntent(PaperUiIntent.Cancel)
        dispatcher.scheduler.runCurrent()

        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)
        assertEquals(null, viewModel.state.value.renderModel.dissolveStage)

        advanceTimeBy(2_000L)
        advanceUntilIdle()
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)
    }

    @Test fun `recognition failure after dissolve restores source ink`() = runTest(dispatcher) {
        val failure = Result.failure<String>(IllegalStateException("recognition failed"))
        val viewModel = viewModel(recognizer = DissolveRecognizer(failure))
        val source = draw(viewModel)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 14L * 70L)
        advanceUntilIdle()

        assertEquals(PaperPhase.RecognitionFailed, viewModel.state.value.phase)
        assertEquals(listOf(source), viewModel.state.value.renderModel.strokes)
        assertEquals(null, viewModel.state.value.renderModel.dissolveStage)
    }

    @Test fun `configuration recreation retains dissolve cursor and monotonic deadline`() = runTest(dispatcher) {
        val instance = viewModel()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return instance as T
            }
        }
        val beforeRecreation = ViewModelProvider(store, factory)[PaperViewModel::class.java]
        draw(beforeRecreation)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 4L * 70L)
        dispatcher.scheduler.runCurrent()
        assertEquals(4, beforeRecreation.state.value.renderModel.dissolveStage)

        val afterRecreation = ViewModelProvider(store, factory)[PaperViewModel::class.java]
        assertSame(beforeRecreation, afterRecreation)
        advanceTimeBy(69L)
        dispatcher.scheduler.runCurrent()
        assertEquals(4, afterRecreation.state.value.renderModel.dissolveStage)

        advanceTimeBy(1L)
        dispatcher.scheduler.runCurrent()
        assertEquals(5, afterRecreation.state.value.renderModel.dissolveStage)
        afterRecreation.onIntent(PaperUiIntent.Cancel)
        store.clear()
    }

    @Test fun `process death during real dissolve restores interrupted source without provider replay`() = runTest(dispatcher) {
        val persistence = DissolvePersistence()
        val activeHandle = SavedStateHandle()
        val activeProvider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val active = viewModel(provider = activeProvider, persistence = persistence, savedStateHandle = activeHandle)
        val source = draw(active)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 3L * PaperViewModel.INPUT_DISSOLVE_STAGE_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, active.state.value.renderModel.dissolveStage)

        val durableAtProcessDeath = persistence.load()
        assertTrue(durableAtProcessDeath.interrupted)
        assertEquals(listOf(source), durableAtProcessDeath.strokes)

        val recoveryProvider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val recoveredState = activeHandle.keys().associateWith { key -> activeHandle.get<Any?>(key) }
        val recreated = viewModel(
            provider = recoveryProvider,
            persistence = persistence,
            savedStateHandle = SavedStateHandle(recoveredState),
        )
        dispatcher.scheduler.runCurrent()

        assertEquals(PaperPhase.Interrupted, recreated.state.value.phase)
        assertEquals(listOf(source), recreated.state.value.renderModel.strokes)
        assertEquals(null, recreated.state.value.renderModel.dissolveStage)
        assertTrue(activeProvider.recordedRequests.isEmpty())
        assertTrue(recoveryProvider.recordedRequests.isEmpty())
        active.onIntent(PaperUiIntent.Cancel)
    }

    @Test fun `new writing remains authoritative over stale dissolve callbacks`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val old = draw(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 140L)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, viewModel.state.value.renderModel.dissolveStage)

        val first = NormalizedPoint(.6f, .6f, .01f)
        val second = NormalizedPoint(.8f, .8f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("new", PaperTool.PEN, first))
        viewModel.onPaperIntent(PaperIntent.PointAdded("new", second))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("new"))
        dispatcher.scheduler.runCurrent()

        advanceTimeBy(500L)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(old, PaperStroke("new", PaperTool.PEN, listOf(first, second))), viewModel.state.value.renderModel.strokes)
        assertEquals(null, viewModel.state.value.renderModel.dissolveStage)
    }

    @Test fun `reduced motion emits every dissolve stage without inter-stage delay`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onIntent(PaperUiIntent.SetMotionScale(0f))
        val stages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state
                .map { it.renderModel.dissolveStage }
                .distinctUntilChanged()
                .collect { stage -> if (stage != null) stages += stage }
        }
        draw(viewModel)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals((0..13).toList(), stages)
        assertTrue(viewModel.state.value.renderModel.strokes.isEmpty())
    }

    @Test fun `recreated screen does not overwrite retained zero motion before a real source emission`() = runTest(dispatcher) {
        val retained = viewModel()
        retained.onIntent(PaperUiIntent.SetMotionScale(0f))
        val recreatedScreenSource = MutableSharedFlow<Float>()
        val forwarding = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            forwardObservedMotionScales(recreatedScreenSource, retained::onIntent)
        }
        val stages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            retained.state.map { it.renderModel.dissolveStage }.distinctUntilChanged().collect { stage ->
                if (stage != null) stages += stage
            }
        }
        draw(retained)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()

        assertEquals(PaperViewModel.INACTIVITY_MILLIS, testScheduler.currentTime)
        assertEquals((0..13).toList(), stages)
        forwarding.cancel()
    }

    @Test fun `first launch without a motion source emission keeps normal dissolve timing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        val uninitializedSource = MutableSharedFlow<Float>()
        val forwarding = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            forwardObservedMotionScales(uninitializedSource, viewModel::onIntent)
        }
        draw(viewModel)

        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, viewModel.state.value.renderModel.dissolveStage)

        advanceTimeBy(PaperViewModel.INPUT_DISSOLVE_STAGE_MILLIS - 1L)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, viewModel.state.value.renderModel.dissolveStage)

        advanceTimeBy(1L)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, viewModel.state.value.renderModel.dissolveStage)
        forwarding.cancel()
        viewModel.onIntent(PaperUiIntent.Cancel)
    }

    @Test fun `final teardown during normal dissolve cancels deadline and prevents late mutation`() = runTest(dispatcher) {
        val persistence = DissolvePersistence()
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val instance = viewModel(provider = provider, persistence = persistence)
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return instance as T
            }
        }
        val owned = ViewModelProvider(store, factory)[PaperViewModel::class.java]
        val source = draw(owned)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS + 4L * PaperViewModel.INPUT_DISSOLVE_STAGE_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertEquals(4, owned.state.value.renderModel.dissolveStage)

        store.clear()
        dispatcher.scheduler.runCurrent()
        val stateAfterClear = owned.state.value
        advanceTimeBy(2_000L)
        dispatcher.scheduler.runCurrent()

        assertEquals(stateAfterClear, owned.state.value)
        assertTrue(provider.recordedRequests.isEmpty())
        assertEquals(PaperRecovery(listOf(source), interrupted = false), persistence.load())
    }

    private fun viewModel(
        clock: AppClock = TestAppClock(dispatcher.scheduler),
        recognizer: HandwritingRecognizer = DissolveRecognizer(Result.success("recognized")),
        provider: FakeModelProvider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
        persistence: PaperPersistence = DissolvePersistence(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = PaperViewModel(
        modelSelection = ModelSelection {
            SelectedModel(provider, "fake", ModelCapabilities(streaming = true, vision = false))
        },
        persistence = persistence,
        preferences = DissolvePreferences(),
        turnInputRouter = TurnInputRouter(
            PageRasterizer(File("build/tmp/dissolve"), dispatcher = dispatcher),
            recognizer,
        ),
        pageGeometry = AtomicPageGeometryPort(dev.riddle.magicpaper.paper.PageGeometry.fullPage(1_000, 1_000)),
        stateMachine = ConversationStateMachine(),
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = savedStateHandle,
        workerDispatcher = dispatcher,
        clock = clock,
    )

    private fun draw(viewModel: PaperViewModel): PaperStroke {
        val points = listOf(
            NormalizedPoint(.1f, .2f, .01f),
            NormalizedPoint(.4f, .5f, .02f),
        )
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("dissolve", PaperTool.PEN, points.first()))
        viewModel.onPaperIntent(PaperIntent.PointAdded("dissolve", points.last()))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("dissolve"))
        return PaperStroke("dissolve", PaperTool.PEN, points)
    }
}

private class DissolveClock(
    var elapsedRealtimeMillis: Long,
    var wallClockMillis: Long,
) : AppClock {
    var wallClockReads = 0
        private set

    override fun elapsedRealtimeMillis() = elapsedRealtimeMillis
    override fun wallClockMillis(): Long {
        wallClockReads++
        return wallClockMillis
    }
}

private class DissolvePersistence(
    private var current: PaperRecovery = PaperRecovery(),
) : PaperPersistence {
    override suspend fun load() = current
    override suspend fun saveDraft(recovery: PaperRecovery) { current = recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { current = PaperRecovery(strokes, interrupted = true) }
    override suspend fun clearStreamingAndDraft() { current = PaperRecovery() }
}

private class DissolvePreferences : PaperPreferences {
    override val portraitLocked = MutableStateFlow(false)
    override val settingsEntryMode = MutableStateFlow(SettingsEntryMode.MAGIC_RUNE_BUTTON)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) { settingsEntryMode.value = mode }
}

private class DissolveRecognizer(
    private val result: Result<String>,
) : HandwritingRecognizer {
    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale) = result
}
