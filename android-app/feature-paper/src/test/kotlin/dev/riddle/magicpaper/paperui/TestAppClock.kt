package dev.riddle.magicpaper.paperui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestAppClock(
    private val scheduler: TestCoroutineScheduler,
) : AppClock {
    override fun elapsedRealtimeMillis() = scheduler.currentTime
    override fun wallClockMillis() = 1_000_000L
}
