package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreeFingerLongPressPolicyTest {
    @Test
    fun `three stable fingers open settings after two seconds`() {
        val policy = ThreeFingerLongPressPolicy(holdMillis = 2_000, slopNormalized = 0.02f)
        assertNull(policy.onTouchFrame(frame(3), 1_000))
        assertNull(policy.onTouchFrame(frame(3), 2_999))
        assertEquals(SettingsEntryEvent.OpenSettings, policy.onTouchFrame(frame(3), 3_000))
    }

    @Test
    fun `movement or stylus input cancels hidden gesture`() {
        val policy = ThreeFingerLongPressPolicy()
        policy.onTouchFrame(frame(3), 0)
        assertNull(policy.onTouchFrame(frame(3, movement = 0.1f), 2_000))
        assertNull(policy.onTouchFrame(frame(3, tool = PointerTool.STYLUS), 4_000))
    }

    private fun frame(
        contacts: Int,
        movement: Float = 0f,
        tool: PointerTool = PointerTool.FINGER,
    ) = TouchFrame(
        (0 until contacts).map { index ->
            TouchContact(index, NormalizedPoint(0.2f * (index + 1) + movement, 0.5f, 0f), tool)
        },
    )
}
