package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkDissolveBitmapTest {
    @Test fun `successive dissolve stages reuse one source ink raster`() {
        val stroke = PaperStroke(
            id = "cached-source",
            tool = PaperTool.PEN,
            points = listOf(NormalizedPoint(.1f, .3f, .04f), NormalizedPoint(.9f, .7f, .04f)),
        )
        val cache = InkBitmapCache()

        val source = cache.build(listOf(stroke), 320, 240, dissolveStage = null)
        val stageZero = cache.build(listOf(stroke), 320, 240, dissolveStage = 0)
        val stageOne = cache.build(listOf(stroke), 320, 240, dissolveStage = 1)
        val stageThirteen = cache.build(listOf(stroke), 320, 240, dissolveStage = 13)

        assertSame(source.bitmap, stageZero.bitmap)
        assertSame(stageZero.bitmap, stageOne.bitmap)
        assertSame(stageZero.bitmap, stageThirteen.bitmap)
    }

    @Test fun `recycled derived bitmap is never reused by a later stage`() {
        val stroke = PaperStroke(
            "recycled",
            PaperTool.PEN,
            listOf(NormalizedPoint(.1f, .2f, .03f), NormalizedPoint(.8f, .7f, .03f)),
        )
        val cache = InkBitmapCache()
        val first = cache.build(listOf(stroke), 240, 180, dissolveStage = 0)
        first.bitmap.recycle()

        val next = cache.build(listOf(stroke), 240, 180, dissolveStage = 1)

        assertNotSame(first.bitmap, next.bitmap)
        assertTrue(!next.bitmap.isRecycled)
    }

    @Test fun `backward and out of range stages safely rebuild or clamp through the pattern`() {
        val stroke = PaperStroke(
            "bounds",
            PaperTool.PEN,
            listOf(NormalizedPoint(.1f, .5f, .08f), NormalizedPoint(.9f, .5f, .08f)),
        )
        val cache = InkBitmapCache()
        val late = cache.build(listOf(stroke), 240, 180, dissolveStage = 8)
        val lateCoverage = nonTransparentCount(late.bitmap)
        val earlier = cache.build(listOf(stroke), 240, 180, dissolveStage = 2)

        assertNotSame(late.bitmap, earlier.bitmap)
        assertTrue(nonTransparentCount(earlier.bitmap) > lateCoverage)
        assertEquals(0, nonTransparentCount(cache.build(listOf(stroke), 240, 180, dissolveStage = 99).bitmap))
    }

    @Test fun `nontransparent ink coverage decreases monotonically across all fourteen stages`() {
        val stroke = PaperStroke(
            id = "coverage",
            tool = PaperTool.PEN,
            points = listOf(
                NormalizedPoint(.05f, .5f, .18f),
                NormalizedPoint(.95f, .5f, .18f),
            ),
        )
        val cache = InkBitmapCache()
        val coverage = (0..13).map { stage ->
            val bitmap = cache.build(listOf(stroke), 512, 192, stage).bitmap
            (0 until bitmap.width).sumOf { x ->
                (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) ushr 24 != 0 }
            }
        }

        assertTrue(coverage.zipWithNext().all { (before, after) -> after < before }, coverage.toString())
        assertEquals(0, coverage.last())
    }

    private fun nonTransparentCount(bitmap: android.graphics.Bitmap): Int =
        (0 until bitmap.width).sumOf { x ->
            (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) ushr 24 != 0 }
        }
}
