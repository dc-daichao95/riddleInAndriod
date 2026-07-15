package dev.riddle.magicpaper.paperui

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
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PaperIntent
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaperClockTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `wall clock jump does not change monotonic inactivity deadline`() = runTest(dispatcher) {
        val clock = FakeDeviceClock(elapsedRealtimeMillis = 10_000L, wallClockMillis = 1_000_000L)
        val provider = FakeModelProvider(listOf(ModelEvent.Completed(FinishReason.STOP)))
        val viewModel = viewModel(provider, clock)
        viewModel.onIntent(PaperUiIntent.SetMotionScale(0f))

        drawStroke(viewModel)
        dispatcher.scheduler.runCurrent()
        clock.wallClockMillis -= 900_000L
        advanceTimeBy(PaperViewModel.INACTIVITY_MILLIS - 1L)
        dispatcher.scheduler.runCurrent()
        assertTrue(provider.recordedRequests.isEmpty())

        clock.elapsedRealtimeMillis += PaperViewModel.INACTIVITY_MILLIS
        advanceTimeBy(1L)
        advanceUntilIdle()

        assertEquals(100_000L, clock.wallClockMillis)
        assertTrue(clock.elapsedRealtimeReads >= 3)
        assertEquals(0, clock.wallClockReads)
        assertEquals(1, provider.recordedRequests.size)
        assertEquals(PaperPhase.Completed, viewModel.state.value.phase)
    }

    @Test fun `deadline remaining time ignores wall clock jumps`() {
        val clock = FakeDeviceClock(elapsedRealtimeMillis = 10_000L, wallClockMillis = 1_000_000L)
        val deadline = clock.deadlineAfter(2_800L)

        clock.wallClockMillis += 86_400_000L
        assertEquals(2_800L, deadline.remainingMillis())
        clock.elapsedRealtimeMillis += 1_000L
        assertEquals(1_800L, deadline.remainingMillis())
        clock.wallClockMillis = 0L
        clock.elapsedRealtimeMillis += 1_800L
        assertEquals(0L, deadline.remainingMillis())
        assertEquals(0, clock.wallClockReads)
    }

    @Test fun `deadline waits again when elapsed clock has not reached due time`() = runTest(dispatcher) {
        val clock = FakeDeviceClock(elapsedRealtimeMillis = 10_000L, wallClockMillis = 1_000_000L)
        val deadline = clock.deadlineAfter(2_800L)
        val completed = async { deadline.await() }
        dispatcher.scheduler.runCurrent()

        clock.elapsedRealtimeMillis += 1_000L
        advanceTimeBy(2_800L)
        dispatcher.scheduler.runCurrent()
        assertTrue(!completed.isCompleted)

        clock.elapsedRealtimeMillis += 1_800L
        advanceTimeBy(1_800L)
        completed.await()
    }

    @Test fun `deadline wait is cancellable`() = runTest(dispatcher) {
        val clock = FakeDeviceClock(elapsedRealtimeMillis = 10_000L, wallClockMillis = 1_000_000L)
        var completedNormally = false
        val waiting = launch {
            clock.deadlineAfter(2_800L).await()
            completedNormally = true
        }
        dispatcher.scheduler.runCurrent()

        waiting.cancelAndJoin()
        clock.elapsedRealtimeMillis += 2_800L
        advanceUntilIdle()

        assertTrue(waiting.isCancelled)
        assertTrue(!completedNormally)
    }

    private fun viewModel(provider: FakeModelProvider, clock: AppClock) = PaperViewModel(
        modelSelection = ModelSelection {
            SelectedModel(provider, "fake", ModelCapabilities(streaming = true, vision = false))
        },
        persistence = ClockPersistence(),
        preferences = ClockPreferences(),
        turnInputRouter = TurnInputRouter(
            PageRasterizer(File("build/tmp/clock"), 1000, 1000, dispatcher = dispatcher),
            object : HandwritingRecognizer {
                override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale) =
                    Result.success("recognized page")
            },
        ),
        stateMachine = ConversationStateMachine(),
        orchestratorFactory = ::ConversationOrchestrator,
        savedStateHandle = SavedStateHandle(),
        workerDispatcher = dispatcher,
        clock = clock,
    )

    private fun drawStroke(viewModel: PaperViewModel) {
        val first = NormalizedPoint(.1f, .2f, .01f)
        viewModel.onPaperIntent(PaperIntent.StrokeStarted("clock-stroke", PaperTool.PEN, first))
        viewModel.onPaperIntent(PaperIntent.PointAdded("clock-stroke", NormalizedPoint(.4f, .5f, .02f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded("clock-stroke"))
    }
}

private class FakeDeviceClock(
    var elapsedRealtimeMillis: Long,
    var wallClockMillis: Long,
) : AppClock {
    var elapsedRealtimeReads = 0
        private set
    var wallClockReads = 0
        private set

    override fun elapsedRealtimeMillis(): Long {
        elapsedRealtimeReads++
        return elapsedRealtimeMillis
    }
    override fun wallClockMillis(): Long {
        wallClockReads++
        return wallClockMillis
    }
}

private class ClockPersistence : PaperPersistence {
    override suspend fun load() = PaperRecovery()
    override suspend fun saveDraft(recovery: PaperRecovery) = Unit
    override suspend fun markStreaming(strokes: List<PaperStroke>) = Unit
    override suspend fun clearStreamingAndDraft() = Unit
}

private class ClockPreferences : PaperPreferences {
    override val portraitLocked = MutableStateFlow(false)
    override val settingsEntryMode = MutableStateFlow(SettingsEntryMode.MAGIC_RUNE_BUTTON)
    override suspend fun setPortraitLocked(locked: Boolean) { portraitLocked.value = locked }
    override suspend fun setSettingsEntryMode(mode: SettingsEntryMode) { settingsEntryMode.value = mode }
}
