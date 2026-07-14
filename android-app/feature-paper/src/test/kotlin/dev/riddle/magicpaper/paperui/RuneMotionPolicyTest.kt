package dev.riddle.magicpaper.paperui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class RuneMotionPolicyTest {
    @Test fun `zero scale uses static brightness without shimmer`() {
        val policy = RuneMotionPolicy(durationScale = 0f)

        assertEquals(.72f, policy.initialBrightness)
        assertTrue(policy.shimmerKeyframes.isEmpty())
    }

    @Test fun `nonzero scale defines exactly one short shimmer sequence`() {
        val policy = RuneMotionPolicy(durationScale = 1f)

        assertEquals(.46f, policy.initialBrightness)
        assertEquals(
            listOf(
                RuneBrightnessKeyframe(.92f, 360),
                RuneBrightnessKeyframe(.58f, 420),
            ),
            policy.shimmerKeyframes,
        )
    }

    @Test fun `android source registers before initial read and unregisters on cancellation`() = runTest {
        val settings = RacingMotionScaleSettings()
        val source = AndroidMotionScaleSource(settings)

        assertEquals(0f, source.scales().first())
        assertEquals(listOf("register", "read", "unregister"), settings.events)

        val collecting = launch { source.scales().collect {} }
        testScheduler.advanceUntilIdle()
        collecting.cancelAndJoin()
        assertEquals(2, settings.unregisterCount)
    }
}

private class RacingMotionScaleSettings : MotionScaleSettings {
    val events = mutableListOf<String>()
    var unregisterCount = 0
    private var scale = 1f

    override fun currentScale(): Float {
        events += "read"
        return scale
    }

    override fun register(onChange: () -> Unit): () -> Unit {
        events += "register"
        scale = 0f
        return {
            events += "unregister"
            unregisterCount += 1
        }
    }
}
