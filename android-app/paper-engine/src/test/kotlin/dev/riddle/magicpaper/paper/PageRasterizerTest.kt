package dev.riddle.magicpaper.paper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
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

    @Test
    fun `closing rasterized page surfaces typed immediate deletion failure`() = runTest {
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            fileDeletion = PageCacheFileDeletion { false },
        )
        val page = rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f)))).getOrThrow()

        val error = assertFailsWith<PageRasterizationError.CacheCleanupFailed> { page.close() }

        assertEquals(page.file.name, error.fileName)
        assertTrue(page.file.exists())
        page.file.delete()
    }

    @Test
    fun `encoding failure surfaces cleanup failure when partial cache file cannot be deleted`() = runTest {
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            fileDeletion = PageCacheFileDeletion { false },
            imageEncoder = PageImageEncoder { _, _ -> error("encoding failed") },
        )

        val error = rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f)))).exceptionOrNull()

        assertIs<PageRasterizationError.CacheCleanupFailed>(error)
        assertEquals(1, temporaryFolder.root.listFiles().orEmpty().count { it.name.startsWith(PAGE_CACHE_PREFIX) })
        temporaryFolder.root.listFiles().orEmpty().forEach(File::delete)
    }

    @Test
    fun `pre-raster sweep deletes only bounded stale owned cache files`() = runTest {
        val staleOwned = temporaryFolder.newFile("${PAGE_CACHE_PREFIX}stale.png").apply { setLastModified(0L) }
        val unrelated = temporaryFolder.newFile("other-cache.png").apply { setLastModified(0L) }
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            nowMillis = { 7_200_000L },
            staleAfterMillis = 3_600_000L,
        )

        assertIs<PageRasterizationError.EmptyPage>(rasterizer.rasterize(emptyList()).exceptionOrNull())

        assertTrue(!staleOwned.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `cancellation during encoding deletes the partial cache file`() = runTest {
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            imageEncoder = PageImageEncoder { _, _ -> throw kotlinx.coroutines.CancellationException("cancel") },
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f))))
        }
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation after encoding deletes image that cannot be handed to caller`() = runTest {
        lateinit var operation: Deferred<Result<RasterizedPage>>
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            sourceWidth = 1_000,
            sourceHeight = 1_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            imageEncoder = PageImageEncoder { bitmap, file ->
                FileOutputStream(file).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                operation.cancel()
            },
        )
        operation = async {
            rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f))))
        }

        assertFailsWith<kotlinx.coroutines.CancellationException> { operation.await() }

        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    private fun stroke(vararg points: NormalizedPoint) = PaperStroke("stroke", PaperTool.PEN, points.toList())

    private fun point(x: Float, y: Float, radius: Float) = NormalizedPoint(x, y, radius)
}
