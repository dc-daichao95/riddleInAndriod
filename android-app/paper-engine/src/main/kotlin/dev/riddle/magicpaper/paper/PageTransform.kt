package dev.riddle.magicpaper.paper

import android.graphics.RectF
import dev.riddle.magicpaper.model.NormalizedPoint
import kotlin.math.min

data class PixelPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
)

class PageTransform(
    safeDrawingRect: RectF,
) {
    private val left = safeDrawingRect.left
    private val top = safeDrawingRect.top
    private val width = safeDrawingRect.right - safeDrawingRect.left
    private val height = safeDrawingRect.bottom - safeDrawingRect.top
    private val radiusScale = min(width, height)

    init {
        require(width > 0f && height > 0f) { "safe drawing rectangle must have positive dimensions" }
    }

    fun toPixels(point: NormalizedPoint): PixelPoint = PixelPoint(
        x = left + point.x * width,
        y = top + point.y * height,
        radius = point.radius * radiusScale,
    )

    fun toNormalized(point: PixelPoint): NormalizedPoint = NormalizedPoint(
        x = (point.x - left) / width,
        y = (point.y - top) / height,
        radius = point.radius / radiusScale,
    )
}
