package dev.riddle.magicpaper.paperui

import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.math.abs

data class QuestionMarkClassifierConfig(
    val minWidth: Float = .35f,
    val minHeight: Float = .55f,
    val maxDotExtent: Float = .08f,
    val maxDotCenterOffset: Float = .16f,
)

/** Conservative normalized-stroke heuristic; false negatives submit normally, false positives would steal input. */
class QuestionMarkClassifier(
    private val config: QuestionMarkClassifierConfig = QuestionMarkClassifierConfig(),
) {
    fun isLargeQuestionMark(strokes: List<PaperStroke>): Boolean {
        val ink = strokes.filter { it.tool == PaperTool.PEN && it.points.isNotEmpty() }
        if (ink.size != 2) return false
        val all = ink.flatMap(PaperStroke::points)
        val left = all.minOf { it.x }
        val right = all.maxOf { it.x }
        val top = all.minOf { it.y }
        val bottom = all.maxOf { it.y }
        if (right - left < config.minWidth || bottom - top < config.minHeight) return false

        val dot = ink.minBy { stroke -> stroke.points.maxOf { it.y } - stroke.points.minOf { it.y } }
        val hook = ink.first { it !== dot }
        if (hook.points.size < 5) return false
        val dotWidth = dot.points.maxOf { it.x } - dot.points.minOf { it.x }
        val dotHeight = dot.points.maxOf { it.y } - dot.points.minOf { it.y }
        if (dotWidth > config.maxDotExtent || dotHeight > config.maxDotExtent) return false

        val hookBottom = hook.points.maxOf { it.y }
        val dotTop = dot.points.minOf { it.y }
        val hookCenter = (hook.points.minOf { it.x } + hook.points.maxOf { it.x }) / 2f
        val dotCenter = (dot.points.minOf { it.x } + dot.points.maxOf { it.x }) / 2f
        val topIndex = hook.points.indices.minBy { hook.points[it].y }
        return dotTop > hookBottom &&
            abs(dotCenter - hookCenter) <= config.maxDotCenterOffset &&
            topIndex in 1 until hook.points.lastIndex
    }
}
