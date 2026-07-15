package dev.riddle.magicpaper.model

import java.util.Locale

enum class HandwritingLanguage(val persistedId: String) {
    AUTOMATIC("automatic"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    TRADITIONAL_CHINESE("traditional_chinese"),
    ENGLISH("english");

    fun canonicalLanguageTag(systemLocale: Locale): String = when (this) {
        SIMPLIFIED_CHINESE -> "zh-Hans"
        TRADITIONAL_CHINESE -> "zh-Hant"
        ENGLISH -> "en-US"
        AUTOMATIC -> systemLocale.canonicalHandwritingLanguageTag()
    }

    companion object {
        fun fromPersistedId(value: String?): HandwritingLanguage =
            entries.firstOrNull { it.persistedId == value } ?: AUTOMATIC
    }
}

private fun Locale.canonicalHandwritingLanguageTag(): String = when {
    language.equals("zh", ignoreCase = true) && country.uppercase(Locale.ROOT) in
        setOf("TW", "HK", "MO") -> "zh-Hant"
    language.equals("zh", ignoreCase = true) -> "zh-Hans"
    else -> "en-US"
}
