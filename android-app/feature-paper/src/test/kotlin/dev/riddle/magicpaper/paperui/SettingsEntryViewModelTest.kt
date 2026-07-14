package dev.riddle.magicpaper.paperui

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
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
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.model.SettingsEntryMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rune intent pauses inactivity preserves draft and emits one settings effect`() = runTest(dispatcher) {
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val persistence = EntryPersistence()
        val viewModel = viewModel(provider, persistence)

        viewModel.effects.test {
            drawStroke(viewModel)
            advanceTimeBy(1_000)
            viewModel.onIntent(PaperUiIntent.OpenSettings)

            assertEquals(PaperEffect.OpenSettings, awaitItem())
            expectNoEvents()
            advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS)
            expectNoEvents()
            assertTrue(provider.recordedRequests.isEmpty())
            assertEquals(1, viewModel.state.value.renderModel.strokes.size)
            assertEquals(1, persistence.savedDrafts.last().strokes.size)
        }
    }

    @Test
    fun `entry preference is exposed in paper state`() = runTest(dispatcher) {
        val preferences = EntryPreferences(SettingsEntryMode.THREE_FINGER_LONG_PRESS)
        val viewModel = viewModel(preferences = preferences)
        dispatcher.scheduler.runCurrent()

        assertEquals(SettingsEntryMode.THREE_FINGER_LONG_PRESS, viewModel.state.value.settingsEntryMode)
    }

    @Test
    fun `settings entry selection is persisted through provider neutral mode intent`() = runTest(dispatcher) {
        val preferences = EntryPreferences()
        val viewModel = viewModel(preferences = preferences)
        dispatcher.scheduler.runCurrent()

        viewModel.onIntent(PaperUiIntent.SetSettingsEntryMode(SettingsEntryMode.THREE_FINGER_LONG_PRESS))
        dispatcher.scheduler.runCurrent()

        assertEquals(SettingsEntryMode.THREE_FINGER_LONG_PRESS, preferences.settingsEntryMode.value)
        assertEquals(SettingsEntryMode.THREE_FINGER_LONG_PRESS, viewModel.state.value.settingsEntryMode)
    }

    private fun viewModel(
        provider: FakeModelProvider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP))),
        persistence: EntryPersistence = EntryPersistence(),
        preferences: EntryPreferences = EntryPreferences(),
    ) = PaperViewModel(
        modelSelection = ModelSelection {
            SelectedModel(provider, "fake", ModelCapabilities(streaming = true, vision = false))
        },
        persistence = persistence,
        preferences = preferences,
        turnInputRouter = TurnInputRouter(
            PageRasterizer(File("build/tmp/settings-entry-view-model"), 1000, 1000, dispatcher = dispatcher),
            object : HandwritingRecognizer {
                override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale) = Result.success("draft")
            },
        ),
        stateMachine = ConversationStateMachine(),
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = SavedStateHandle(),
        workerDispatcher = dispatcher,
    )

    private fun drawStroke(viewModel: PaperViewModel) {
        val first = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("settings-entry", PaperTool.PEN, first))
        viewModel.onPaperIntent(PaperIntent.PointAdded("settings-entry", NormalizedPoint(.4f, .5f, .02f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("settings-entry"))
    }
}

private class EntryPersistence : PaperPersistence {
    val savedDrafts = mutableListOf<PaperRecovery>()
    override suspend fun load() = PaperRecovery()
    override suspend fun saveDraft(recovery: PaperRecovery) { savedDrafts += recovery }
    override suspend fun markStreaming(strokes: List<PaperStroke>) = Unit
    override suspend fun clearStreamingAndDraft() = Unit
}

private class EntryPreferences(initialMode: SettingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON) : PaperPreferences {
    override val portraitLocked = MutableStateFlow(false)
    override val settingsEntryMode = MutableStateFlow(initialMode)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) { settingsEntryMode.value = mode }
}
