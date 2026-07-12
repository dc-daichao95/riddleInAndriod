package dev.riddle.magicpaper.paper

import kotlin.math.abs

data class PixelCoordinate(val x: Int, val y: Int)

data class PixelRun(val pixels: List<PixelCoordinate>) {
    init {
        require(pixels.isNotEmpty())
    }
}

class DissolveSelection(private val pattern: DissolvePattern) {
    fun survivingRuns(from: PixelCoordinate, to: PixelCoordinate, stage: Int): List<PixelRun> {
        val runs = mutableListOf<PixelRun>()
        var current = mutableListOf<PixelCoordinate>()
        linePixels(from, to).forEach { pixel ->
            if (!pattern.shouldErase(pixel.x, pixel.y, stage)) {
                current += pixel
            } else if (current.isNotEmpty()) {
                runs += PixelRun(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) runs += PixelRun(current)
        return runs
    }

    private fun linePixels(from: PixelCoordinate, to: PixelCoordinate): List<PixelCoordinate> {
        var x = from.x
        var y = from.y
        val dx = abs(to.x - from.x)
        val sx = if (from.x < to.x) 1 else -1
        val dy = -abs(to.y - from.y)
        val sy = if (from.y < to.y) 1 else -1
        var error = dx + dy
        return buildList {
            while (true) {
                add(PixelCoordinate(x, y))
                if (x == to.x && y == to.y) break
                val doubled = 2 * error
                if (doubled >= dy) {
                    error += dy
                    x += sx
                }
                if (doubled <= dx) {
                    error += dx
                    y += sy
                }
            }
        }
    }
}
