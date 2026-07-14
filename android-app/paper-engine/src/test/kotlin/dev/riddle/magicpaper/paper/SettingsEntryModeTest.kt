package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsEntryModeTest {
    @Test
    fun `missing persisted mode defaults to magic rune button`() {
        assertEquals(SettingsEntryMode.MAGIC_RUNE_BUTTON, SettingsEntryMode.fromPersistedId(null))
    }

    @Test
    fun `unknown or corrupt persisted mode defaults to magic rune button`() {
        listOf("unknown", "THREE FINGER", "\u0000corrupt").forEach { stored ->
            assertEquals(SettingsEntryMode.MAGIC_RUNE_BUTTON, SettingsEntryMode.fromPersistedId(stored))
        }
    }

    @Test
    fun `explicit legacy persisted id retains three finger long press`() {
        assertEquals(
            SettingsEntryMode.THREE_FINGER_LONG_PRESS,
            SettingsEntryMode.fromPersistedId(SettingsEntryMode.THREE_FINGER_LONG_PRESS.persistedId),
        )
    }

    @Test
    fun `default mode ignores three finger frames`() {
        val policy = SettingsEntryMode.fromPersistedId(null).createPolicy()

        assertNull(policy.onTouchFrame(frame(3), 0))
        assertNull(policy.onTouchFrame(frame(3), 2_000))
    }

    private fun frame(count: Int) = TouchFrame(
        List(count) { index ->
            TouchContact(index, NormalizedPoint(.2f + index * .1f, .3f, .01f), PointerTool.FINGER)
        },
    )
}
