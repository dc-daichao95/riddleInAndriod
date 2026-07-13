package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MlKitHandwritingRecognizerTest {
    @Test
    fun `ML Kit Ink preserves stroke order with deterministic monotonic timestamps`() {
        val strokes = listOf(
            PaperStroke(
                "first",
                PaperTool.PEN,
                listOf(NormalizedPoint(.1f, .2f, .01f), NormalizedPoint(.3f, .4f, .01f)),
            ),
            PaperStroke("eraser", PaperTool.ERASER, listOf(NormalizedPoint(.5f, .6f, .01f))),
            PaperStroke("second", PaperTool.PEN, listOf(NormalizedPoint(.7f, .8f, .01f))),
        )

        val ink = strokes.toMlKitInk()

        assertEquals(2, ink.strokes.size)
        assertEquals(listOf(.1f, .3f), ink.strokes[0].points.map { it.x })
        assertEquals(listOf(.7f), ink.strokes[1].points.map { it.x })
        val timestamps = ink.strokes.flatMap { stroke -> stroke.points.map { requireNotNull(it.timestamp) } }
        assertEquals(listOf(0L, 1L, 2L), timestamps)
        assertTrue(timestamps.zipWithNext().all { (first, second) -> first < second })
    }

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
