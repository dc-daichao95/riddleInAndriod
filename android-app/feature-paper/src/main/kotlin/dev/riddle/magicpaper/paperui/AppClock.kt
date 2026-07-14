package dev.riddle.magicpaper.paperui

import kotlinx.coroutines.delay

interface AppClock {
    fun elapsedRealtimeMillis(): Long
    fun wallClockMillis(): Long
}

class MonotonicDeadline internal constructor(
    private val clock: AppClock,
    private val dueAtElapsedRealtimeMillis: Long,
) {
    fun remainingMillis(): Long {
        val now = clock.elapsedRealtimeMillis()
        return if (now >= dueAtElapsedRealtimeMillis) 0L else dueAtElapsedRealtimeMillis - now
    }

    suspend fun await() {
        while (true) {
            val remaining = remainingMillis()
            if (remaining == 0L) return
            delay(remaining)
        }
    }
}

fun AppClock.deadlineAfter(durationMillis: Long): MonotonicDeadline {
    require(durationMillis >= 0L)
    val now = elapsedRealtimeMillis()
    val dueAt = if (now > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE else now + durationMillis
    return MonotonicDeadline(this, dueAt)
}
