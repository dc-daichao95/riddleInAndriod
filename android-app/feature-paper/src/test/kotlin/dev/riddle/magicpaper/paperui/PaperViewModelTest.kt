package dev.riddle.magicpaper.paperui

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import dev.riddle.magicpaper.conversation.ConversationOrchestrator
import dev.riddle.magicpaper.conversation.ConversationStateMachine
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.conversation.HandwritingRecognizer
import dev.riddle.magicpaper.conversation.TurnInputRouter
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelDescriptor
import dev.riddle.magicpaper.model.ModelDiscoveryResult
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderDescriptor
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ValidationResult
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.model.SettingsEntryMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaperViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `opening settings cancels inactivity commit and preserves draft`() = runTest(dispatcher) {
        val persistence = FakePaperPersistence()
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val viewModel = viewModel(persistence, provider)
        viewModel.effects.test {
            drawStroke(viewModel)
            advanceTimeBy(1_000)
            viewModel.onPaperIntent(PaperIntent.OpenSettings)
            assertEquals(PaperEffect.OpenSettings, awaitItem())
            advanceTimeBy(3_000)
            assertTrue(provider.recordedRequests.isEmpty())
            assertEquals(1, viewModel.state.value.renderModel.strokes.size)
            assertEquals(1, persistence.savedDrafts.last().strokes.size)
        }
    }

    @Test fun `draft is restored and an interrupted stream is surfaced without replay`() = runTest(dispatcher) {
        val stroke = stroke()
        val persistence = FakePaperPersistence(PaperRecovery(listOf(stroke), interrupted = true))
        val provider = FakeModelProvider(listOf(ModelEvent.TextDelta("must not run")))
        val viewModel = viewModel(persistence, provider)
        advanceUntilIdle()
        assertEquals(listOf(stroke), viewModel.state.value.renderModel.strokes)
        assertEquals(PaperPhase.Interrupted, viewModel.state.value.phase)
        assertTrue(provider.recordedRequests.isEmpty())
    }

    @Test fun `fake provider streams reply after inactivity`() = runTest(dispatcher) {
        val provider = FakeModelProvider(listOf(
            ModelEvent.TextDelta("Magic "),
            ModelEvent.TextDelta("reply"),
            ModelEvent.Completed(FinishReason.STOP),
        ))
        val viewModel = viewModel(FakePaperPersistence(), provider)
        drawStroke(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()
        assertEquals(1, provider.recordedRequests.size)
        assertEquals("Magic reply", viewModel.state.value.reply)
        assertEquals(PaperPhase.Completed, viewModel.state.value.phase)
        assertTrue(viewModel.state.value.renderModel.strokes.isEmpty())
    }

    @Test fun `commit streams configured model without model discovery request`() = runTest(dispatcher) {
        val provider = CountingProvider()
        val viewModel = viewModel(FakePaperPersistence(), provider = provider, modelId = "configured-model")
        drawStroke(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        advanceUntilIdle()
        assertEquals(0, provider.listModelsCalls)
        assertEquals("configured-model", provider.streamedRequest?.modelId)
    }

    @Test fun `cancel stops an active stream and returns to listening`() = runTest(dispatcher) {
        val provider = FakeModelProvider(kotlinx.coroutines.flow.flow {
            emit(ModelEvent.TextDelta("partial"))
            kotlinx.coroutines.awaitCancellation()
        })
        val persistence = FakePaperPersistence()
        val viewModel = viewModel(persistence, provider)
        drawStroke(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.state.value.canCancel)
        viewModel.onIntent(PaperUiIntent.Cancel)
        advanceUntilIdle()
        assertEquals(PaperPhase.Cancelled, viewModel.state.value.phase)
        assertEquals("partial", viewModel.state.value.reply)
        assertFalse(persistence.savedDrafts.last().interrupted)
    }

    @Test fun `new stroke interrupts stream and rejects late reply deltas`() = runTest(dispatcher) {
        val lateEvents = kotlinx.coroutines.flow.MutableSharedFlow<ModelEvent>(extraBufferCapacity = 2)
        val persistence = FakePaperPersistence()
        val viewModel = viewModel(persistence, FakeModelProvider(lateEvents))
        drawStroke(viewModel)
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
        dispatcher.scheduler.runCurrent()
        lateEvents.tryEmit(ModelEvent.TextDelta("old"))
        dispatcher.scheduler.runCurrent()

        viewModel.onPaperIntent(PaperIntent.StrokeStarted("new", PaperTool.PEN, NormalizedPoint(.2f, .2f, .01f)))
        lateEvents.tryEmit(ModelEvent.TextDelta(" late"))
        dispatcher.scheduler.runCurrent()

        assertEquals("", viewModel.state.value.reply)
        assertFalse(persistence.savedDrafts.last().interrupted)
    }

    @Test fun `portrait preference is observed and updated`() = runTest(dispatcher) {
        val preferences = FakePaperPreferences()
        val viewModel = viewModel(FakePaperPersistence(), preferences = preferences)
        assertFalse(viewModel.state.value.portraitLocked)
        viewModel.onIntent(PaperUiIntent.SetPortraitLocked(true))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.portraitLocked)
        assertTrue(preferences.portraitLocked.value)
    }

    @Test fun `point additions do not replace compose stroke list`() = runTest(dispatcher) {
        val viewModel = viewModel(FakePaperPersistence())
        val point = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("s", PaperTool.PEN, point))
        val composeList = viewModel.state.value.renderModel.strokes
        repeat(50) { viewModel.onPaperIntent(PaperIntent.PointAdded("s", point.copy(x = .1f + it / 100f))) }
        assertSame(composeList, viewModel.state.value.renderModel.strokes)
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("s"))
        assertEquals(1, viewModel.state.value.renderModel.strokes.size)
    }

    @Test fun `help and return intents wire state without changing draft`() = runTest(dispatcher) {
        val viewModel = viewModel(FakePaperPersistence())
        drawStroke(viewModel)
        viewModel.onIntent(PaperUiIntent.ShowHelp)
        assertTrue(viewModel.state.value.helpVisible)
        viewModel.onIntent(PaperUiIntent.HideHelp)
        viewModel.onIntent(PaperUiIntent.SettingsClosed)
        assertFalse(viewModel.state.value.helpVisible)
        assertEquals(1, viewModel.state.value.renderModel.strokes.size)
    }

    private fun viewModel(
        persistence: FakePaperPersistence,
        provider: ModelProvider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
        preferences: FakePaperPreferences = FakePaperPreferences(),
        modelId: String = "fake",
    ) = PaperViewModel(
        modelSelection = ModelSelection { SelectedModel(provider, modelId, provider.descriptor.capabilities) },
        persistence = persistence,
        preferences = preferences,
        turnInputRouter = TurnInputRouter(
            PageRasterizer(File("build/tmp/view-model"), 1000, 1000, dispatcher = dispatcher),
            object : HandwritingRecognizer {
                override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale) = Result.success("recognized page")
            },
        ),
        stateMachine = ConversationStateMachine(),
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = SavedStateHandle(),
        workerDispatcher = dispatcher,
        clock = TestAppClock(dispatcher.scheduler),
    ).apply { onIntent(PaperUiIntent.SetMotionScale(0f)) }

    private fun drawStroke(viewModel: PaperViewModel) {
        val first = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("s", PaperTool.PEN, first))
        viewModel.onPaperIntent(PaperIntent.PointAdded("s", NormalizedPoint(.4f, .5f, .02f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("s"))
    }

    private fun stroke() = PaperStroke("saved", PaperTool.PEN, listOf(NormalizedPoint(.2f, .3f, .01f)))
}

private class FakePaperPersistence(initial: PaperRecovery = PaperRecovery()) : PaperPersistence {
    private val recovery = initial
    val savedDrafts = mutableListOf<PaperRecovery>()
    override suspend fun load(): PaperRecovery = recovery
    override suspend fun saveDraft(recovery: PaperRecovery) { savedDrafts += recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) { savedDrafts += PaperRecovery(strokes, true) }
    override suspend fun clearStreamingAndDraft() { savedDrafts += PaperRecovery() }
}

private class FakePaperPreferences(initial: Boolean = false) : PaperPreferences {
    override val portraitLocked = MutableStateFlow(initial)
    override val settingsEntryMode = MutableStateFlow(SettingsEntryMode.MAGIC_RUNE_BUTTON)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) { settingsEntryMode.value = mode }
}

private class CountingProvider : ModelProvider {
    var listModelsCalls = 0
    var streamedRequest: ModelRequest? = null
    override val descriptor = ProviderDescriptor(ProviderType.OPENAI_COMPATIBLE, "Counting", ModelCapabilities(streaming = true))
    override fun stream(request: ModelRequest): Flow<ModelEvent> {
        streamedRequest = request
        return flowOf(ModelEvent.Completed(FinishReason.STOP))
    }
    override suspend fun listModels(): ModelDiscoveryResult {
        listModelsCalls++
        return ModelDiscoveryResult.Success(emptyList())
    }
    override suspend fun validate(configuration: ProviderConfiguration) = ValidationResult.Valid
}
