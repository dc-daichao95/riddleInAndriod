package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkBitmapCacheTest {
    private val thickStroke = PaperStroke(
        id = "thick",
        tool = PaperTool.PEN,
        points = listOf(NormalizedPoint(0.1f, 0.5f, 0.05f), NormalizedPoint(0.9f, 0.5f, 0.05f)),
    )

    @Test
    fun `dissolve selects transverse pixels independently across thick stroke`() {
        val cached = InkBitmapCache().build(listOf(thickStroke), width = 100, height = 100, dissolveStage = 5)
        val transverseAlpha = (45..55).map { y -> cached.bitmap.getPixel(50, y) ushr 24 }

        assertTrue(transverseAlpha.any { it == 0 })
        assertTrue(transverseAlpha.any { it != 0 })
    }

    @Test
    fun `large stroke cache is view bounded and bitmap represented`() {
        val cached = InkBitmapCache().build(listOf(thickStroke), width = 2048, height = 1536, dissolveStage = 5)

        assertTrue(cached.bitmap.width <= 2048)
        assertTrue(cached.bitmap.height <= 1536)
        assertEquals(1, cached.drawCallCount)
    }
}
