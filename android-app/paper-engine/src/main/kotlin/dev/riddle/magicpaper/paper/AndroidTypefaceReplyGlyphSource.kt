package dev.riddle.magicpaper.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/** Rasterizes the two injected bundled typefaces, then thins and traces ink into normalized paths. */
class AndroidTypefaceReplyGlyphSource(
    latin: Typeface,
    cjk: Typeface,
    private val textSizePixels: Float = 96f,
    private val cancellation: ReplyCancellationProbe = ReplyCancellationProbe {},
) : ReplyGlyphSource {
    private val paints = mapOf(
        ReplyFont.LATIN to glyphPaint(latin),
        ReplyFont.CJK to glyphPaint(cjk),
    )

    override fun supports(font: ReplyFont, cluster: String): Boolean =
        paints[font]?.hasGlyph(cluster) == true

    override fun strokes(font: ReplyFont, cluster: String): List<List<ReplyPoint>> {
        cancellation.check()
        val paint = paints[font] ?: return emptyList()
        if (cluster.isBlank()) return emptyList()
        val metrics = paint.fontMetrics
        val width = max(1, ceil(paint.measureText(cluster)).toInt() + PADDING * 2).coerceAtMost(MAX_BITMAP_DIMENSION)
        val height = max(1, ceil(metrics.descent - metrics.ascent).toInt() + PADDING * 2).coerceAtMost(MAX_BITMAP_DIMENSION)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawText(cluster, PADDING.toFloat(), PADDING - metrics.ascent, paint)
            cancellation.check()
            val mask = BooleanArray(width * height)
            val pixels = IntArray(mask.size)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            pixels.forEachIndexed { index, color ->
                if (index % CANCELLATION_STRIDE == 0) cancellation.check()
                mask[index] = Color.alpha(color) >= 128
            }
            thin(mask, width, height)
            trace(mask, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun glyphPaint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = textSizePixels
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private fun thin(mask: BooleanArray, width: Int, height: Int) {
        fun index(x: Int, y: Int) = y * width + x
        var changed: Boolean
        var rounds = 0
        do {
            cancellation.check()
            changed = false
            repeat(2) { phase ->
                val clear = ArrayList<Int>()
                for (y in 1 until height - 1) {
                    cancellation.check()
                    for (x in 1 until width - 1) {
                    if (!mask[index(x, y)]) continue
                    val p = booleanArrayOf(
                        mask[index(x, y - 1)], mask[index(x + 1, y - 1)], mask[index(x + 1, y)],
                        mask[index(x + 1, y + 1)], mask[index(x, y + 1)], mask[index(x - 1, y + 1)],
                        mask[index(x - 1, y)], mask[index(x - 1, y - 1)],
                    )
                    val neighbors = p.count { it }
                    if (neighbors !in 2..6) continue
                    val transitions = p.indices.count { !p[it] && p[(it + 1) % p.size] }
                    if (transitions != 1) continue
                    val keep = if (phase == 0) {
                        !(p[0] && p[2] && p[4]) && !(p[2] && p[4] && p[6])
                    } else {
                        !(p[0] && p[2] && p[6]) && !(p[0] && p[4] && p[6])
                    }
                    if (keep) clear += index(x, y)
                    }
                }
                if (clear.isNotEmpty()) {
                    changed = true
                    clear.forEach { mask[it] = false }
                }
            }
            rounds++
        } while (changed && rounds < MAX_THINNING_ROUNDS)
    }

    private fun trace(mask: BooleanArray, width: Int, height: Int): List<List<ReplyPoint>> {
        fun ink(x: Int, y: Int) = x in 0 until width && y in 0 until height && mask[y * width + x]
        fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && ink(x + dx, y + dy)) add(x + dx to y + dy)
            }
        }
        val starts = ArrayList<Pair<Int, Int>>()
        for (y in 0 until height) {
            cancellation.check()
            for (x in 0 until width) if (ink(x, y) && neighbors(x, y).size == 1) starts += x to y
        }
        for (y in 0 until height) {
            cancellation.check()
            for (x in 0 until width) if (ink(x, y)) starts += x to y
        }
        val visited = BooleanArray(mask.size)
        val paths = ArrayList<List<ReplyPoint>>()
        starts.forEach { start ->
            if (visited[start.second * width + start.first]) return@forEach
            val path = ArrayList<ReplyPoint>()
            var current = start
            while (true) {
                if (path.size % CANCELLATION_STRIDE == 0) cancellation.check()
                visited[current.second * width + current.first] = true
                path += ReplyPoint(
                    current.first.toFloat() / max(1, width - 1),
                    current.second.toFloat() / max(1, height - 1),
                )
                val next = neighbors(current.first, current.second)
                    .firstOrNull { !visited[it.second * width + it.first] } ?: break
                current = next
            }
            if (path.size >= 2) paths += path
        }
        return paths.sortedBy { path -> path.minOf(ReplyPoint::x) }
    }

    private companion object {
        const val PADDING = 4
        const val MAX_BITMAP_DIMENSION = 1_024
        const val MAX_THINNING_ROUNDS = 512
        const val CANCELLATION_STRIDE = 256
    }
}
