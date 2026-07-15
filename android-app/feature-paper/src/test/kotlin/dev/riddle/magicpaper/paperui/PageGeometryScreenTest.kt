package dev.riddle.magicpaper.paperui

import androidx.compose.ui.unit.IntSize
import dev.riddle.magicpaper.paper.SafePageBounds
import kotlin.test.assertEquals
import org.junit.Test

class PageGeometryScreenTest {
    @Test fun `split and wide window insets become contained safe page bounds`() {
        assertEquals(
            SafePageBounds(12, 48, 676, 904),
            pageGeometry(IntSize(700, 1_000), 12, 48, 24, 96).safeBounds,
        )
        assertEquals(
            SafePageBounds(100, 0, 2_100, 1_000),
            pageGeometry(IntSize(2_200, 1_000), 100, 0, 100, 0).safeBounds,
        )
    }

    @Test fun `transient insets larger than a tiny window retain a valid one pixel safe page`() {
        val geometry = pageGeometry(IntSize(10, 8), 20, 20, 20, 20)

        assertEquals(SafePageBounds(9, 7, 10, 8), geometry.safeBounds)
    }
}
