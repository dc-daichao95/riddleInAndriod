package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.PageGeometry
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputRoutingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `vision model receives image without OCR`() = runTest {
        val recognizer = FakeRecognizer()
        val router = router(recognizer)

        val input = router.route(ModelCapabilities(vision = true), page, geometry).getOrThrow()

        input as TurnInput.PageImage
        input.image.use { assertEquals(0, recognizer.calls) }
    }

    @Test
    fun `text model receives local recognition`() = runTest {
        val recognizer = FakeRecognizer().apply { result = Result.success("hello") }
        val router = router(recognizer)

        val input = router.route(ModelCapabilities(vision = false), page, geometry).getOrThrow()

        assertEquals(TurnInput.RecognizedText("hello"), input)
        assertEquals(Locale.JAPAN, recognizer.locale)
    }

    @Test
    fun `recognized text is trimmed before staging`() = runTest {
        val recognizer = FakeRecognizer().apply { result = Result.success("  hello  ") }

        val input = router(recognizer).route(ModelCapabilities(vision = false), page, geometry).getOrThrow()

        assertEquals(TurnInput.RecognizedText("hello"), input)
    }

    @Test
    fun `blank OCR prevents Provider request`() = runTest {
        val recognizer = FakeRecognizer().apply { result = Result.success("   ") }

        val result = router(recognizer).route(ModelCapabilities(vision = false), page, geometry)

        assertEquals(InputRoutingError.BlankRecognition, result.exceptionOrNull())
    }

    @Test
    fun `typed recognition prerequisite failure is preserved`() = runTest {
        val missingModel = HandwritingRecognitionError.ModelNotDownloaded("ja-JP")
        val recognizer = FakeRecognizer().apply { result = Result.failure(missingModel) }

        val error = router(recognizer).route(ModelCapabilities(vision = false), page, geometry).exceptionOrNull()

        assertEquals(InputRoutingError.RecognitionFailed(missingModel), error)
    }

    private fun router(recognizer: HandwritingRecognizer) = TurnInputRouter(
        rasterizer = PageRasterizer(
            cacheDirectory = temporaryFolder.root,
        ),
        recognizer = recognizer,
        localeProvider = { Locale.JAPAN },
    )

    private class FakeRecognizer : HandwritingRecognizer {
        var calls = 0
        var locale: Locale? = null
        var result: Result<String> = Result.success("recognized")

        override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String> {
            calls += 1
            this.locale = locale
            return result
        }
    }

    private val page = listOf(
        PaperStroke(
            id = "stroke",
            tool = PaperTool.PEN,
            points = listOf(NormalizedPoint(.2f, .3f, .01f), NormalizedPoint(.8f, .7f, .01f)),
        ),
    )
    private val geometry = PageGeometry.fullPage(1_000, 1_000)
}
