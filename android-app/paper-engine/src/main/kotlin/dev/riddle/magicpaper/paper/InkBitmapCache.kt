package dev.riddle.magicpaper.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.math.max
import kotlin.math.min

data class CachedInkBitmap(
    val bitmap: Bitmap,
    val drawCallCount: Int = 1,
)

class InkBitmapCache(
    private val pattern: DissolvePattern = DissolvePattern(),
    private val inkColor: Int = Color.BLACK,
) {
    private var sourceCache: SourceCache? = null

    fun build(strokes: List<PaperStroke>, width: Int, height: Int, dissolveStage: Int?): CachedInkBitmap {
        require(width > 0 && height > 0)
        val cached = sourceCache
        if (cached != null && !cached.bitmap.isRecycled && cached.strokes == strokes && cached.width == width && cached.height == height &&
            dissolveStage != null && (cached.dissolveStage == null || dissolveStage >= cached.dissolveStage)
        ) {
            if (cached.dissolveStage == null || dissolveStage > cached.dissolveStage) {
                applyDissolve(cached.bitmap, dissolveStage)
            }
            sourceCache = cached.copy(dissolveStage = dissolveStage)
            return CachedInkBitmap(cached.bitmap)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val radiusScale = min(width, height)
        strokes.filter { it.tool == PaperTool.PEN }.forEach { stroke ->
            if (stroke.points.size == 1) {
                val point = stroke.points.single()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(point.x * width, point.y * height, point.radius * radiusScale, paint)
            } else {
                paint.style = Paint.Style.STROKE
                stroke.points.zipWithNext().forEach { (from, to) ->
                    paint.strokeWidth = max(1f, (from.radius + to.radius) * radiusScale)
                    canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, paint)
                }
            }
        }
        if (dissolveStage != null) applyDissolve(bitmap, dissolveStage)
        sourceCache = SourceCache(strokes.toList(), width, height, dissolveStage, bitmap)
        return CachedInkBitmap(bitmap)
    }

    private fun applyDissolve(bitmap: Bitmap, stage: Int) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (y in 0 until bitmap.height) {
            val row = y * bitmap.width
            for (x in 0 until bitmap.width) {
                val index = row + x
                if (pixels[index] ushr 24 != 0 && pattern.shouldErase(x, y, stage)) pixels[index] = Color.TRANSPARENT
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private data class SourceCache(
        val strokes: List<PaperStroke>,
        val width: Int,
        val height: Int,
        val dissolveStage: Int?,
        val bitmap: Bitmap,
    )
}
