package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperInputDeltaTest {
    @Test
    fun `each event returns only newly added preview geometry`() {
        val reducer = PaperInputReducer(strokeIds = { "s1" })
        val down = InputPoint(1, PointerTool.STYLUS, 0.1f, 0.2f, 1f)

        val first = reducer.reduce(InputChange.Down(down))
        val second = reducer.reduce(InputChange.Move(listOf(down.copy(x = 0.2f))))
        val third = reducer.reduce(InputChange.Move(listOf(down.copy(x = 0.3f))))

        assertEquals(1, first.previewDelta.inkDots.size)
        assertTrue(first.previewDelta.inkSegments.isEmpty())
        assertEquals(1, second.previewDelta.inkSegments.size)
        assertEquals(1, third.previewDelta.inkSegments.size)
    }

    @Test
    fun `eraser emits dirty geometry but never ink preview geometry`() {
        val reducer = PaperInputReducer()
        val eraser = InputPoint(3, PointerTool.ERASER, 0.4f, 0.5f, 1f)

        val down = reducer.reduce(InputChange.Down(eraser))
        val move = reducer.reduce(InputChange.Move(listOf(eraser.copy(x = 0.6f))))

        assertTrue(down.previewDelta.inkDots.isEmpty())
        assertTrue(move.previewDelta.inkSegments.isEmpty())
        assertEquals(1, down.previewDelta.dirtyPoints.size)
        assertEquals(1, move.previewDelta.dirtySegments.size)
        assertTrue((down.intents + move.intents).all { it is PaperIntent.Erase })
    }
}
