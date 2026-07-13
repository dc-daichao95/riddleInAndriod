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
    fun `normal one then two then three finger contact sequence can open settings`() {
        val policy = ThreeFingerLongPressPolicy()
        assertNull(policy.onTouchFrame(frame(1), 0))
        assertNull(policy.onTouchFrame(frame(2), 10))
        assertNull(policy.onTouchFrame(frame(3), 20))
        assertEquals(SettingsEntryEvent.OpenSettings, policy.onTouchFrame(frame(3), 2_020))
    }

    @Test
    fun `movement cancels hidden gesture`() {
        val policy = ThreeFingerLongPressPolicy()
        policy.onTouchFrame(frame(3), 0)
        assertNull(policy.onTouchFrame(movedFirstContact(0.1f), 2_000))
    }

    @Test
    fun `stylus input cancels hidden gesture`() {
        val policy = ThreeFingerLongPressPolicy()
        policy.onTouchFrame(frame(3), 0)
        assertNull(policy.onTouchFrame(frame(3, tool = PointerTool.STYLUS), 2_000))
    }

    @Test
    fun `exactly three contacts are required`() {
        listOf(2, 4).forEach { contactCount ->
            val policy = ThreeFingerLongPressPolicy()
            assertNull(policy.onTouchFrame(frame(contactCount), 0))
            assertNull(policy.onTouchFrame(frame(3), 2_000))
        }
    }

    @Test
    fun `movement at slop is accepted and movement over slop is cancelled`() {
        val atBoundary = ThreeFingerLongPressPolicy(slopNormalized = 0.02f)
        atBoundary.onTouchFrame(frame(3), 0)
        assertEquals(SettingsEntryEvent.OpenSettings, atBoundary.onTouchFrame(movedFirstContact(0.02f), 2_000))

        val overBoundary = ThreeFingerLongPressPolicy(slopNormalized = 0.02f)
        overBoundary.onTouchFrame(frame(3), 0)
        assertNull(overBoundary.onTouchFrame(movedFirstContact(0.0201f), 2_000))
    }

    @Test
    fun `settings emits once until every contact releases`() {
        val policy = ThreeFingerLongPressPolicy()
        policy.onTouchFrame(frame(3), 0)
        assertEquals(SettingsEntryEvent.OpenSettings, policy.onTouchFrame(frame(3), 2_000))
        assertNull(policy.onTouchFrame(frame(3), 4_000))
        assertNull(policy.onTouchFrame(frame(1), 5_000))
        assertNull(policy.onTouchFrame(TouchFrame(emptyList()), 6_000))

        assertNull(policy.onTouchFrame(frame(3), 7_000))
        assertEquals(SettingsEntryEvent.OpenSettings, policy.onTouchFrame(frame(3), 9_000))
    }

    private fun frame(
        contacts: Int,
        tool: PointerTool = PointerTool.FINGER,
    ) = TouchFrame(
        (0 until contacts).map { index ->
            TouchContact(index, NormalizedPoint(0.2f * (index + 1), 0.5f, 0f), tool)
        },
    )

    private fun movedFirstContact(movement: Float): TouchFrame = TouchFrame(
        frame(3).contacts.map { contact ->
            if (contact.id == 0) {
                contact.copy(position = contact.position.copy(x = contact.position.x + movement))
            } else {
                contact
            }
        },
    )
}
