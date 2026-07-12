package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint

class StrokeReducer(
    private val minRadius: Float = 0.0015f,
    private val maxRadius: Float = 0.006f,
    private val fingerRadius: Float = 0.004f,
) {
    private var stylusInProximity = false

    init {
        require(minRadius.isFinite() && maxRadius.isFinite() && minRadius in 0f..maxRadius && maxRadius <= 1f)
        require(fingerRadius.isFinite() && fingerRadius in 0f..1f)
    }

    fun onStylusProximity(inProximity: Boolean) {
        stylusInProximity = inProximity
    }

    fun accept(tool: PointerTool): Boolean = tool != PointerTool.FINGER || !stylusInProximity

    fun radius(tool: PointerTool, pressure: Float): Float = when (tool) {
        PointerTool.FINGER -> fingerRadius
        PointerTool.STYLUS, PointerTool.ERASER -> {
            val normalizedPressure = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
            minRadius + (maxRadius - minRadius) * normalizedPressure
        }
    }

    fun point(tool: PointerTool, x: Float, y: Float, pressure: Float): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
        radius = radius(tool, pressure),
    )
}
