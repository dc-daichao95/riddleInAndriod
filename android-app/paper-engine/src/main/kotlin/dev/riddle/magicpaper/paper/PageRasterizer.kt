package dev.riddle.magicpaper.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed class PageRasterizationError(message: String) : Exception(message) {
    data object EmptyPage : PageRasterizationError("The page contains no visible ink")
    data object CacheWriteFailed : PageRasterizationError("The temporary page image could not be written")
    data class CacheCleanupFailed(val fileName: String) :
        PageRasterizationError("The temporary page image could not be deleted: $fileName")
}

const val PAGE_CACHE_PREFIX = "riddle-page-"

fun interface PageCacheFileDeletion {
    fun delete(file: File): Boolean
}

fun interface PageImageEncoder {
    fun encode(bitmap: Bitmap, file: File)
}

private val DEFAULT_FILE_DELETION = PageCacheFileDeletion(File::delete)
private val DEFAULT_IMAGE_ENCODER = PageImageEncoder { bitmap, file ->
    FileOutputStream(file).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
}

class RasterizedPage internal constructor(
    val file: File,
    val width: Int,
    val height: Int,
    private val cacheDirectory: File,
    private val fileDeletion: PageCacheFileDeletion,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        PageCacheOwnershipRegistry.withDirectory(cacheDirectory) { ownership ->
            try {
                if (file.exists() && !fileDeletion.delete(file)) {
                    throw PageRasterizationError.CacheCleanupFailed(file.name)
                }
            } finally {
                ownership.unregister(file)
            }
        }
    }
}

class PageRasterizer(
    cacheDirectory: File,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val paddingPixels: Int = 24,
    private val maxLongSide: Int = 800,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fileDeletion: PageCacheFileDeletion = DEFAULT_FILE_DELETION,
    private val imageEncoder: PageImageEncoder = DEFAULT_IMAGE_ENCODER,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {
    private val cacheDirectory = cacheDirectory.canonicalFile

    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(paddingPixels >= 0)
        require(maxLongSide > 0)
        require(staleAfterMillis >= 0)
    }

    suspend fun rasterize(strokes: List<PaperStroke>): Result<RasterizedPage> {
        val pendingPage = AtomicReference<RasterizedPage?>()
        return try {
            withContext(dispatcher) {
                try {
                    sweepStaleFiles()
                    val visible = strokes.filter { it.tool == PaperTool.PEN && it.points.isNotEmpty() }
                    if (visible.isEmpty()) return@withContext Result.failure(PageRasterizationError.EmptyPage)
                    val page = render(visible)
                    pendingPage.set(page)
                    currentCoroutineContext().ensureActive()
                    Result.success(page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PageRasterizationError) {
                    Result.failure(error)
                } catch (_: Exception) {
                    Result.failure(PageRasterizationError.CacheWriteFailed)
                }
            }.also { pendingPage.set(null) }
        } catch (cancelled: CancellationException) {
            pendingPage.getAndSet(null)?.let { page ->
                try {
                    page.close()
                } catch (cleanup: PageRasterizationError.CacheCleanupFailed) {
                    cancelled.addSuppressed(cleanup)
                }
            }
            throw cancelled
        }
    }

    private fun sweepStaleFiles() {
        val cutoff = nowMillis() - staleAfterMillis
        PageCacheOwnershipRegistry.withDirectory(cacheDirectory) { ownership ->
            cacheDirectory.listFiles().orEmpty()
                .asSequence()
                .filter { file -> file.isFile && file.name.startsWith(PAGE_CACHE_PREFIX) }
                .filter { file -> file.parentFile?.canonicalFile == cacheDirectory }
                .filterNot(ownership::isActive)
                .filter { file -> file.lastModified() <= cutoff }
                .sortedBy(File::lastModified)
                .take(MAX_STALE_FILES_PER_SWEEP)
                .forEach { file ->
                    if (!fileDeletion.delete(file)) throw PageRasterizationError.CacheCleanupFailed(file.name)
                }
        }
    }

    private fun render(strokes: List<PaperStroke>): RasterizedPage {
        val radiusScale = min(sourceWidth, sourceHeight).toFloat()
        val left = max(0, floor(strokes.minOf { stroke ->
            stroke.points.minOf { it.x * sourceWidth - it.radius * radiusScale }
        }).toInt() - paddingPixels)
        val top = max(0, floor(strokes.minOf { stroke ->
            stroke.points.minOf { it.y * sourceHeight - it.radius * radiusScale }
        }).toInt() - paddingPixels)
        val right = min(sourceWidth, ceil(strokes.maxOf { stroke ->
            stroke.points.maxOf { it.x * sourceWidth + it.radius * radiusScale }
        }).toInt() + paddingPixels)
        val bottom = min(sourceHeight, ceil(strokes.maxOf { stroke ->
            stroke.points.maxOf { it.y * sourceHeight + it.radius * radiusScale }
        }).toInt() + paddingPixels)

        val cropWidth = max(1, right - left)
        val cropHeight = max(1, bottom - top)
        val scale = min(1f, maxLongSide.toFloat() / max(cropWidth, cropHeight))
        val outputWidth = max(1, (cropWidth * scale).roundToInt())
        val outputHeight = max(1, (cropHeight * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            if (stroke.points.size == 1) {
                val point = stroke.points.single()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(
                    (point.x * sourceWidth - left) * scale,
                    (point.y * sourceHeight - top) * scale,
                    max(.5f, point.radius * radiusScale * scale),
                    paint,
                )
            } else {
                paint.style = Paint.Style.STROKE
                stroke.points.zipWithNext().forEach { (from, to) ->
                    paint.strokeWidth = max(1f, (from.radius + to.radius) * radiusScale * scale)
                    canvas.drawLine(
                        (from.x * sourceWidth - left) * scale,
                        (from.y * sourceHeight - top) * scale,
                        (to.x * sourceWidth - left) * scale,
                        (to.y * sourceHeight - top) * scale,
                        paint,
                    )
                }
            }
        }

        try {
            return PageCacheOwnershipRegistry.withDirectory(cacheDirectory) { ownership ->
                cacheDirectory.mkdirs()
                val file = File.createTempFile(PAGE_CACHE_PREFIX, ".png", cacheDirectory)
                try {
                    imageEncoder.encode(bitmap, file)
                } catch (failure: Exception) {
                    if (file.exists() && !fileDeletion.delete(file)) {
                        val cleanup = PageRasterizationError.CacheCleanupFailed(file.name)
                        if (failure is CancellationException) {
                            failure.addSuppressed(cleanup)
                            throw failure
                        }
                        cleanup.addSuppressed(failure)
                        throw cleanup
                    }
                    throw failure
                }
                ownership.register(file)
                RasterizedPage(file, outputWidth, outputHeight, cacheDirectory, fileDeletion)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 60 * 60 * 1_000L
        const val MAX_STALE_FILES_PER_SWEEP = 64
    }
}

private object PageCacheOwnershipRegistry {
    private class DirectoryState {
        val lock = Any()
        val activeFileRefCounts = mutableMapOf<String, Int>()
        var users: Int = 0
    }

    class DirectoryOwnership internal constructor(
        private val activeFileRefCounts: MutableMap<String, Int>,
    ) {
        fun register(file: File) {
            activeFileRefCounts[file.name] = activeFileRefCounts.getOrDefault(file.name, 0) + 1
        }

        fun unregister(file: File) {
            val count = activeFileRefCounts[file.name] ?: return
            if (count == 1) activeFileRefCounts.remove(file.name)
            else activeFileRefCounts[file.name] = count - 1
        }

        fun isActive(file: File): Boolean = activeFileRefCounts.containsKey(file.name)
    }

    private val directories = ConcurrentHashMap<String, DirectoryState>()

    fun <T> withDirectory(directory: File, block: (DirectoryOwnership) -> T): T {
        val key = directory.toPath().toAbsolutePath().normalize().toString()
        val state = checkNotNull(directories.compute(key) { _, existing ->
            (existing ?: DirectoryState()).also { current ->
                synchronized(current.lock) { current.users += 1 }
            }
        })
        try {
            return synchronized(state.lock) {
                block(DirectoryOwnership(state.activeFileRefCounts))
            }
        } finally {
            directories.compute(key) { _, current ->
                if (current !== state) return@compute current
                synchronized(state.lock) {
                    state.users -= 1
                    if (state.users == 0 && state.activeFileRefCounts.isEmpty()) null else state
                }
            }
        }
    }
}
