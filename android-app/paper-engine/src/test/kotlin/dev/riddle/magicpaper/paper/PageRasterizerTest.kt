package dev.riddle.magicpaper.paper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageRasterizerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `one rasterizer uses each committed page geometry across portrait landscape split and fold bounds`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root, paddingPixels = 0)
        val cases = listOf(
            PageGeometry(100, 200, SafePageBounds(0, 0, 100, 200)) to (100 to 200),
            PageGeometry(200, 100, SafePageBounds(0, 0, 200, 100)) to (200 to 100),
            PageGeometry(600, 900, SafePageBounds(10, 20, 590, 880)) to (540 to 800),
            PageGeometry(2_200, 1_000, SafePageBounds(100, 0, 2_100, 1_000)) to (800 to 400),
        )

        cases.forEach { (geometry, expected) ->
            rasterizer.rasterize(
                listOf(stroke(point(0f, 0f, 0f), point(1f, 1f, 0f))),
                geometry,
            ).getOrThrow().use { page ->
                assertEquals(expected.first, page.width)
                assertEquals(expected.second, page.height)
            }
        }
    }

    @Test
    fun `ink completely outside committed safe bounds is rejected`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root, paddingPixels = 0)
        val result = rasterizer.rasterize(
            listOf(stroke(point(.05f, .05f, .01f))),
            PageGeometry(1_000, 1_000, SafePageBounds(200, 200, 900, 900)),
        )

        assertIs<PageRasterizationError.EmptyPage>(result.exceptionOrNull())
    }

    @Test
    fun `aggregate overlap without actual safe ink is rejected before encoding`() = runTest {
        var encodes = 0
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
            paddingPixels = 0,
            imageEncoder = PageImageEncoder { _, _ -> encodes += 1 },
        )
        val safeGeometry = PageGeometry(1_000, 1_000, SafePageBounds(400, 400, 600, 600))
        val disjointSides = listOf(
            PaperStroke("left", PaperTool.PEN, listOf(point(.1f, .4f, 0f), point(.1f, .6f, 0f))),
            PaperStroke("right", PaperTool.PEN, listOf(point(.9f, .4f, 0f), point(.9f, .6f, 0f))),
        )
        val surroundingPolyline = listOf(
            PaperStroke(
                "surround",
                PaperTool.PEN,
                listOf(
                    point(.1f, .1f, 0f), point(.9f, .1f, 0f), point(.9f, .9f, 0f),
                    point(.1f, .9f, 0f), point(.1f, .1f, 0f),
                ),
            ),
        )

        listOf("disjoint sides" to disjointSides, "surrounding polyline" to surroundingPolyline).forEach { (case, strokes) ->
            val error = rasterizer.rasterize(strokes, safeGeometry).exceptionOrNull()
            assertTrue(error is PageRasterizationError.EmptyPage, "$case returned $error")
        }
        assertEquals(0, encodes)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `rasterized page crops to ink bounds plus padding and is grayscale`() = runTest {
        val rasterizer = PageRasterizer(
            cacheDirectory = temporaryFolder.root,
            paddingPixels = 10,
        )

        val page = rasterizer.rasterize(
            listOf(stroke(point(.25f, .4f, .01f), point(.75f, .6f, .01f))),
        ).getOrThrow()

        page.use {
            assertEquals(540, it.width)
            assertEquals(240, it.height)
            val encoded = it.file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { pixel -> pixel != Color.WHITE })
            assertTrue(pixels.all { pixel ->
                Color.red(pixel) == Color.green(pixel) && Color.green(pixel) == Color.blue(pixel)
            })
            bitmap.recycle()
        }
    }

    @Test
    fun `rasterized page bounds the long side to 800 pixels`() = runTest {
        val rasterizer = PageRasterizer(
            cacheDirectory = temporaryFolder.root,
            paddingPixels = 0,
        )

        rasterizer.rasterize(
            listOf(stroke(point(0f, 0f, 0f), point(1f, 1f, 0f))),
            PageGeometry.fullPage(1_600, 1_200),
        ).getOrThrow().use {
            assertEquals(800, maxOf(it.width, it.height))
            assertEquals(600, minOf(it.width, it.height))
        }
    }

    @Test
    fun `empty page is rejected without creating a cache file`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root)

        val result = rasterizer.rasterize(emptyList())

        assertIs<PageRasterizationError.EmptyPage>(result.exceptionOrNull())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `closing rasterized page deletes its temporary cache file`() = runTest {
        val rasterizer = PageRasterizer(temporaryFolder.root)
        val page = rasterizer.rasterize(listOf(stroke(point(.5f, .5f, .02f)))).getOrThrow()
        assertTrue(page.file.exists())

        page.close()

        assertTrue(!page.file.exists())
    }

    @Test
    fun `closing rasterized page surfaces typed immediate deletion failure`() = runTest {
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
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
            nowMillis = { 7_200_000L },
            staleAfterMillis = 3_600_000L,
        )

        assertIs<PageRasterizationError.EmptyPage>(rasterizer.rasterize(emptyList()).exceptionOrNull())

        assertTrue(!staleOwned.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `rasterizers sharing a cache directory preserve active page until one-shot close unregisters it`() = runTest {
        val owner = PageRasterizer(
            temporaryFolder.root,
            fileDeletion = PageCacheFileDeletion { false },
        )
        val sweeper = PageRasterizer(
            temporaryFolder.root.canonicalFile,
            nowMillis = { 7_200_000L },
            staleAfterMillis = 3_600_000L,
        )
        val page = owner.rasterize(listOf(stroke(point(.5f, .5f, .02f)))).getOrThrow()
        page.file.setLastModified(0L)

        assertIs<PageRasterizationError.EmptyPage>(sweeper.rasterize(emptyList()).exceptionOrNull())
        assertTrue(page.file.exists(), "another rasterizer must not sweep an active page")

        assertFailsWith<PageRasterizationError.CacheCleanupFailed> { page.close() }
        page.close()
        assertTrue(page.file.exists(), "failed close remains for managed stale cleanup")

        assertIs<PageRasterizationError.EmptyPage>(sweeper.rasterize(emptyList()).exceptionOrNull())
        assertTrue(!page.file.exists(), "closed page is eligible for a later stale sweep")
    }

    @Test
    fun `concurrent sweep cannot race page creation before directory registration`() = runTest {
        val encodingStarted = CountDownLatch(1)
        val allowEncodingToFinish = CountDownLatch(1)
        val sweepStarted = CountDownLatch(1)
        val owner = PageRasterizer(
            temporaryFolder.root,
            imageEncoder = PageImageEncoder { _, file ->
                file.setLastModified(0L)
                encodingStarted.countDown()
                check(allowEncodingToFinish.await(5, TimeUnit.SECONDS))
            },
        )
        val sweeper = PageRasterizer(
            temporaryFolder.root,
            nowMillis = {
                sweepStarted.countDown()
                7_200_000L
            },
            staleAfterMillis = 3_600_000L,
        )
        val pageResult = async(Dispatchers.Default) {
            owner.rasterize(listOf(stroke(point(.5f, .5f, .02f))))
        }
        assertTrue(encodingStarted.await(5, TimeUnit.SECONDS))
        val sweepResult = async(Dispatchers.Default) { sweeper.rasterize(emptyList()) }
        assertTrue(sweepStarted.await(5, TimeUnit.SECONDS))

        assertTrue(!sweepResult.isCompleted, "sweep must wait for atomic file registration")
        allowEncodingToFinish.countDown()
        val page = pageResult.await().getOrThrow()
        assertIs<PageRasterizationError.EmptyPage>(sweepResult.await().exceptionOrNull())
        assertTrue(page.file.exists())
        page.close()
    }

    @Test
    fun `cancellation during encoding deletes the partial cache file`() = runTest {
        val rasterizer = PageRasterizer(
            temporaryFolder.root,
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

    private suspend fun PageRasterizer.rasterize(strokes: List<PaperStroke>) =
        rasterize(strokes, PageGeometry.fullPage(1_000, 1_000))
}
