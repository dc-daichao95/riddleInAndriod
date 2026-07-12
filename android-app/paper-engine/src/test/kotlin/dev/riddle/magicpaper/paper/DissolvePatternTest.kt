package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DissolvePatternTest {
    @Test
    fun `high bit hashes use Rust unsigned remainder`() {
        val pattern = DissolvePattern(stages = 14)

        assertFalse(pattern.shouldErase(1, 0, 12)) // Rust hash 0xF2150407, remainder 13
        assertTrue(pattern.shouldErase(1, 0, 13))
        assertFalse(pattern.shouldErase(7, 11, 11)) // Rust hash 0x85F983EC, remainder 12
        assertTrue(pattern.shouldErase(99, 99, 4)) // Rust hash 0x8654E53C, remainder 4
    }

    @Test
    fun `all ink is selected by final dissolve stage`() {
        val pattern = DissolvePattern(stages = 14)
        val pixels = (0 until 100).flatMap { x -> (0 until 100).map { y -> x to y } }
        assertTrue(pixels.all { (x, y) -> pattern.shouldErase(x, y, 13) })
        assertEquals(pattern.shouldErase(7, 11, 5), pattern.shouldErase(7, 11, 5))
    }
}
