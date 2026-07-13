package dev.riddle.magicpaper.paper

import android.graphics.BitmapFactory
import android.graphics.Color
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PageRasterizerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rasterized page crops to ink bounds plus padding and is grayscale`() = runTest {
        val rasterizer = PageRasterizer(
            cacheDirectory = temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            paddingPixels = 10,
        )

        val page = rasterizer.rasterize(
            listOf(stroke(point(.25f, .4f, .01f), point(.75f, .6f, .01f))),
        ).getOrThrow()

        page.use {
            assertEquals(540, it.width)
            assertEquals(240, it.height)
            val bitmap = BitmapFactory.decodeFile(it.file.absolutePath)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { pixel -> pixel != Color.WHITE })
            assertTrue(pixels.all { pixel ->
                Color.red(pixel) == Color.green(pixel) && Color.green(pixel) == Color.blue(pixel)
            })
        }
    }

    @Test
    fun `rasterized page bounds the long side to 800 pixels`() = runTest {
        val rasterizer = PageRasterizer(
            cacheDirectory = temporaryFolder.root,
            sourceWidth = 1_600,
            sourceHeight = 1_200,
            paddingPixels = 0,
        )

        rasterizer.rasterize(
            listOf(stroke(point(0f, 0f, 0f), point(1f, 1f, 0f))),
        ).getOrThrow().use {
            assertEquals(800, maxOf(it.width, it.height))
            assertEquals(600, minOf(it.width, it.height))
        }
    }

    @Test
    fun `empty page is rejected without creating a cache file`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root, sourceWidth = 1_000, sourceHeight = 1_000)

        val result = rasterizer.rasterize(emptyList())

        assertIs<PageRasterizationError.EmptyPage>(result.exceptionOrNull())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `closing rasterized page deletes its temporary cache file`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root, sourceWidth = 1_000, sourceHeight = 1_000)
        val page = rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f)))).getOrThrow()
        assertTrue(page.file.exists())

        page.close()

        assertTrue(!page.file.exists())
    }

    private fun stroke(vararg points: NormalizedPoint) = PaperStroke("stroke", PaperTool.PEN, points.toList())

    private fun point(x: Float, y: Float, radius: Float) = NormalizedPoint(x, y, radius)
}
