package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrokeReducerTest {
    @Test
    fun `stylus pressure maps into bounded normalized radius`() {
        val reducer = StrokeReducer(minRadius = 0.0015f, maxRadius = 0.006f)
        assertEquals(0.0015f, reducer.radius(PointerTool.STYLUS, 0f), 0.00001f)
        assertEquals(0.006f, reducer.radius(PointerTool.STYLUS, 1f), 0.00001f)
    }

    @Test
    fun `finger is ignored while stylus is active`() {
        val reducer = StrokeReducer()
        reducer.onStylusProximity(true)
        assertFalse(reducer.accept(PointerTool.FINGER))
        assertTrue(reducer.accept(PointerTool.STYLUS))
    }
}
