package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DissolvePatternTest {
    @Test
    fun `all ink is selected by final dissolve stage`() {
        val pattern = DissolvePattern(stages = 14)
        val pixels = (0 until 100).flatMap { x -> (0 until 100).map { y -> x to y } }
        assertTrue(pixels.all { (x, y) -> pattern.shouldErase(x, y, 13) })
        assertEquals(pattern.shouldErase(7, 11, 5), pattern.shouldErase(7, 11, 5))
    }
}
