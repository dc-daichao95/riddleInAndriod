package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaperInputReducerTest {
    private val stylusPoint = InputPoint(7, PointerTool.STYLUS, 0.2f, 0.3f, 0.8f)
    private val fingerPoint = InputPoint(9, PointerTool.FINGER, 0.8f, 0.7f, 1f)

    @Test
    fun `finger joining and leaving cannot end or redirect active stylus`() {
        val reducer = PaperInputReducer(strokeIds = { "s1" })
        reducer.reduce(InputChange.Down(stylusPoint))

        assertTrue(reducer.reduce(InputChange.PointerDown(fingerPoint)).isEmpty())
        assertTrue(reducer.reduce(InputChange.PointerUp(pointerId = 9, point = fingerPoint)).isEmpty())
        val moved = reducer.reduce(InputChange.Move(listOf(fingerPoint, stylusPoint.copy(x = 0.4f))))
        assertEquals(0.4f, assertIs<PaperIntent.PointAdded>(moved.single()).point.x)
        assertIs<PaperIntent.StrokeEnded>(reducer.reduce(InputChange.Up(7, stylusPoint)).last())
    }

    @Test
    fun `stylus joining active finger does not replace its pointer lifecycle`() {
        val reducer = PaperInputReducer(strokeIds = { "f1" })
        reducer.reduce(InputChange.Down(fingerPoint))
        assertTrue(reducer.reduce(InputChange.PointerDown(stylusPoint)).isEmpty())
        assertIs<PaperIntent.PointAdded>(reducer.reduce(InputChange.Move(listOf(stylusPoint, fingerPoint.copy(x = 0.6f)))).single())
        assertIs<PaperIntent.StrokeEnded>(reducer.reduce(InputChange.Up(9, fingerPoint)).last())
    }

    @Test
    fun `historical samples are emitted in order only for active pointer`() {
        val reducer = PaperInputReducer(strokeIds = { "s1" })
        reducer.reduce(InputChange.Down(stylusPoint))
        val history = listOf(stylusPoint.copy(x = 0.25f), stylusPoint.copy(x = 0.3f))

        val intents = reducer.reduce(InputChange.Move(listOf(fingerPoint, stylusPoint.copy(x = 0.35f)), mapOf(7 to history)))

        assertEquals(listOf(0.25f, 0.3f, 0.35f), intents.map { assertIs<PaperIntent.PointAdded>(it).point.x })
    }

    @Test
    fun `eraser emits every sample and never stroke lifecycle`() {
        val reducer = PaperInputReducer()
        val eraser = stylusPoint.copy(pointerId = 3, tool = PointerTool.ERASER)
        assertIs<PaperIntent.Erase>(reducer.reduce(InputChange.Down(eraser)).single())
        val moved = reducer.reduce(InputChange.Move(listOf(eraser.copy(x = 0.5f)), mapOf(3 to listOf(eraser.copy(x = 0.4f)))))
        assertTrue(moved.all { it is PaperIntent.Erase })
        assertTrue(reducer.reduce(InputChange.Up(3, eraser)).all { it is PaperIntent.Erase })
    }

    @Test
    fun `cancel emits cancellation rather than successful end`() {
        val reducer = PaperInputReducer(strokeIds = { "s1" })
        reducer.reduce(InputChange.Down(stylusPoint))
        assertEquals(PaperIntent.StrokeCancelled("s1"), reducer.reduce(InputChange.Cancel).single())
    }

    @Test
    fun `down immediately creates a render-only preview dot`() {
        val reducer = PaperInputReducer(strokeIds = { "s1" })
        reducer.reduce(InputChange.Down(stylusPoint))
        assertEquals(1, reducer.preview.points.size)
        assertTrue(reducer.preview.segments.isEmpty())
    }

    @Test
    fun `stylus proximity rejects finger down until stylus exits`() {
        val reducer = PaperInputReducer(strokeIds = { "f1" })
        reducer.onStylusProximity(true)
        assertTrue(reducer.reduce(InputChange.Down(fingerPoint)).isEmpty())
        reducer.onStylusProximity(false)
        assertIs<PaperIntent.StrokeStarted>(reducer.reduce(InputChange.Down(fingerPoint)).single())
    }
}
