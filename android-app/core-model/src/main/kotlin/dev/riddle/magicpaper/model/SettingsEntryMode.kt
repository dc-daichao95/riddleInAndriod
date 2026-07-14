package dev.riddle.magicpaper.model

enum class SettingsEntryMode(val persistedId: String) {
    MAGIC_RUNE_BUTTON("magic_rune_button"),
    THREE_FINGER_LONG_PRESS("three_finger_long_press"),
    ;

    companion object {
        fun fromPersistedId(id: String?): SettingsEntryMode =
            entries.firstOrNull { it.persistedId == id } ?: MAGIC_RUNE_BUTTON
    }
}
