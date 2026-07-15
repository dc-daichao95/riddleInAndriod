package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.HandwritingLanguage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandwritingLanguagePolicyTest {
    @Test
    fun `stable language choices migrate unknown persisted values to automatic`() {
        assertEquals(
            listOf("automatic", "simplified_chinese", "traditional_chinese", "english"),
            HandwritingLanguage.entries.map { it.persistedId },
        )
        assertEquals(HandwritingLanguage.AUTOMATIC, HandwritingLanguage.fromPersistedId(null))
        assertEquals(HandwritingLanguage.AUTOMATIC, HandwritingLanguage.fromPersistedId("corrupt"))
        assertEquals(
            HandwritingLanguage.TRADITIONAL_CHINESE,
            HandwritingLanguage.fromPersistedId("traditional_chinese"),
        )
    }

    @Test
    fun `automatic canonicalization maps supported Chinese regions and English`() {
        val cases = mapOf(
            "zh-CN" to "zh-Hans",
            "zh-SG" to "zh-Hans",
            "zh-TW" to "zh-Hant",
            "zh-HK" to "zh-Hant",
            "zh-MO" to "zh-Hant",
            "en-GB" to "en-US",
            "ja-JP" to "en-US",
        )

        cases.forEach { (locale, expected) ->
            assertEquals(
                expected,
                HandwritingLanguage.AUTOMATIC.canonicalLanguageTag(Locale.forLanguageTag(locale)),
                locale,
            )
        }
    }

    @Test
    fun `explicit selection overrides an English system locale`() {
        assertEquals(
            "zh-Hans",
            HandwritingLanguage.SIMPLIFIED_CHINESE.canonicalLanguageTag(Locale.US),
        )
        assertEquals(
            "zh-Hant",
            HandwritingLanguage.TRADITIONAL_CHINESE.canonicalLanguageTag(Locale.US),
        )
        assertEquals("en-US", HandwritingLanguage.ENGLISH.canonicalLanguageTag(Locale.TAIWAN))
    }

    @Test
    fun `text request preserves Chinese source and asks for same-language response`() {
        val source = "  \u4f60\u597d\n"

        val request = HandwritingRequestPolicy.text(
            modelId = "model",
            recognizedText = source,
            languageTag = "zh-Hans",
        )

        assertEquals(MessageRole.SYSTEM, request.messages[0].role)
        assertTrue(request.messages[0].text.contains("same language"))
        assertTrue(request.messages[0].text.contains("zh-Hans"))
        assertEquals(source, request.messages[1].text)
    }

    @Test
    fun `vision request uses selected language policy instead of an English-only prompt`() {
        val request = HandwritingRequestPolicy.vision(
            modelId = "model",
            imageDataUrl = "data:image/png;base64,AA==",
            languageTag = "zh-Hant",
        )

        assertTrue(request.messages[0].text.contains("same language"))
        assertTrue(request.messages[0].text.contains("zh-Hant"))
        assertTrue(request.messages[1].text.contains("zh-Hant"))
        assertEquals("data:image/png;base64,AA==", request.messages[1].imageDataUrl)
    }
}
