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
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed class PageRasterizationError(message: String) : Exception(message) {
    data object EmptyPage : PageRasterizationError("The page contains no visible ink")
    data object CacheWriteFailed : PageRasterizationError("The temporary page image could not be written")
}

class RasterizedPage internal constructor(
    val file: File,
    val width: Int,
    val height: Int,
) : Closeable {
    override fun close() {
        if (file.exists() && !file.delete()) file.deleteOnExit()
    }
}

class PageRasterizer(
    private val cacheDirectory: File,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val paddingPixels: Int = 24,
    private val maxLongSide: Int = 800,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(paddingPixels >= 0)
        require(maxLongSide > 0)
    }

    suspend fun rasterize(strokes: List<PaperStroke>): Result<RasterizedPage> = withContext(dispatcher) {
        try {
            val visible = strokes.filter { it.tool == PaperTool.PEN && it.points.isNotEmpty() }
            if (visible.isEmpty()) return@withContext Result.failure(PageRasterizationError.EmptyPage)
            Result.success(render(visible))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(PageRasterizationError.CacheWriteFailed)
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

        cacheDirectory.mkdirs()
        val file = File.createTempFile("riddle-page-", ".png", cacheDirectory)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }
        return RasterizedPage(file, outputWidth, outputHeight)
    }
}
