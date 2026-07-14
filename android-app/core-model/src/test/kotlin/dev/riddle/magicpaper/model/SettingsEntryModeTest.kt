package dev.riddle.magicpaper.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsEntryModeTest {
    @Test fun `missing and unknown persisted ids use magic rune`() {
        assertEquals(SettingsEntryMode.MAGIC_RUNE_BUTTON, SettingsEntryMode.fromPersistedId(null))
        assertEquals(SettingsEntryMode.MAGIC_RUNE_BUTTON, SettingsEntryMode.fromPersistedId("future-entry"))
    }

    @Test fun `explicit legacy id round trips`() {
        val legacy = SettingsEntryMode.THREE_FINGER_LONG_PRESS
        assertEquals(legacy, SettingsEntryMode.fromPersistedId(legacy.persistedId))
    }
}
