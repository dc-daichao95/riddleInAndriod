package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertTrue

class DissolveSelectionTest {
    @Test
    fun `long segment dissolves into spatially selected runs`() {
        val selection = DissolveSelection(DissolvePattern(stages = 14))

        val runs = selection.survivingRuns(PixelCoordinate(0, 0), PixelCoordinate(40, 0), stage = 5)

        val selectedPixels = runs.flatMap { it.pixels }
        assertTrue(selectedPixels.isNotEmpty())
        assertTrue(selectedPixels.size < 41)
        assertTrue(runs.size > 1)
    }

    @Test
    fun `final stage selects no surviving segment pixels`() {
        val selection = DissolveSelection(DissolvePattern(stages = 14))
        assertTrue(selection.survivingRuns(PixelCoordinate(0, 0), PixelCoordinate(40, 20), 13).isEmpty())
    }
}
