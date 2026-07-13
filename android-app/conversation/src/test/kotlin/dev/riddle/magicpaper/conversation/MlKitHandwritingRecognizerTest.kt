package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class MlKitHandwritingRecognizerTest {
    @Test
    fun `unsupported locale uses explicit fallback`() = runTest {
        val backend = FakeDigitalInkBackend(supportedTags = setOf("en-US"), downloadedTags = setOf("en-US"))
        val recognizer = MlKitHandwritingRecognizer(
            backend = backend,
            fallbackLanguageTag = "en-US",
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = recognizer.recognize(page, Locale.forLanguageTag("zz-ZZ"))

        assertEquals("recognized", result.getOrThrow())
        assertEquals("en-US", backend.recognizedLanguageTag)
    }

    @Test
    fun `missing model is a prerequisite error and does not trigger recognition`() = runTest {
        val backend = FakeDigitalInkBackend(supportedTags = setOf("ja-JP"), downloadedTags = emptySet())
        val recognizer = MlKitHandwritingRecognizer(
            backend = backend,
            fallbackLanguageTag = "en-US",
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val error = recognizer.recognize(page, Locale.JAPAN).exceptionOrNull()

        assertEquals(HandwritingRecognitionError.ModelNotDownloaded("ja-JP"), error)
        assertEquals(0, backend.recognitionCalls)
        assertEquals(0, backend.downloadCalls)
    }

    private class FakeDigitalInkBackend(
        private val supportedTags: Set<String>,
        private val downloadedTags: Set<String>,
    ) : DigitalInkRecognitionBackend {
        var recognizedLanguageTag: String? = null
        var recognitionCalls = 0
        var downloadCalls = 0

        override fun supports(languageTag: String): Boolean = languageTag in supportedTags

        override suspend fun isModelDownloaded(languageTag: String): Boolean = languageTag in downloadedTags

        override suspend fun recognize(languageTag: String, strokes: List<PaperStroke>): String {
            recognitionCalls += 1
            recognizedLanguageTag = languageTag
            return "recognized"
        }
    }

    private val page = listOf(
        PaperStroke("stroke", PaperTool.PEN, listOf(NormalizedPoint(.1f, .2f, .01f))),
    )
}
