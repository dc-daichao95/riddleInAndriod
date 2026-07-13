package dev.riddle.magicpaper.paperui

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionMarkClassifierTest {
    private val classifier = QuestionMarkClassifier()

    @Test fun `large normalized question mark opens help`() {
        assertTrue(classifier.isLargeQuestionMark(largeQuestionMark()))
    }

    @Test fun `small question mark and ordinary writing do not open help`() {
        val small = largeQuestionMark().map { stroke ->
            stroke.copy(points = stroke.points.map { it.copy(x = .45f + it.x * .15f, y = .45f + it.y * .15f) })
        }
        val line = listOf(stroke("line", listOf(point(.1f, .5f), point(.9f, .5f))))
        assertFalse(classifier.isLargeQuestionMark(small))
        assertFalse(classifier.isLargeQuestionMark(line))
    }

    @Test fun `large threshold is configurable`() {
        val strict = QuestionMarkClassifier(QuestionMarkClassifierConfig(minWidth = .8f))
        assertFalse(strict.isLargeQuestionMark(largeQuestionMark()))
    }

    private fun largeQuestionMark() = listOf(
        stroke("hook", listOf(
            point(.25f, .30f), point(.30f, .15f), point(.50f, .10f), point(.70f, .20f),
            point(.68f, .38f), point(.52f, .52f), point(.50f, .60f),
        )),
        stroke("dot", listOf(point(.50f, .80f), point(.50f, .82f))),
    )

    private fun stroke(id: String, points: List<NormalizedPoint>) = PaperStroke(id, PaperTool.PEN, points)
    private fun point(x: Float, y: Float) = NormalizedPoint(x, y, .01f)
}
