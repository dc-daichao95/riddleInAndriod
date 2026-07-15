package dev.riddle.magicpaper.paper

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class PageGeometryTest {
    @Test fun `portrait landscape split and fold bounds normalize inside the rendered page`() {
        listOf(
            PageGeometry(1080, 2400, SafePageBounds(0, 96, 1080, 2280)),
            PageGeometry(2400, 1080, SafePageBounds(80, 48, 2320, 1032)),
            PageGeometry(700, 1080, SafePageBounds(0, 64, 700, 984)),
            PageGeometry(2200, 1000, SafePageBounds(120, 0, 2080, 1000)),
        ).forEach { geometry ->
            assertEquals(geometry.safeBounds.width.toFloat() / geometry.pageWidthPx, geometry.normalizedSafeBounds.width, .0001f)
            assertEquals(geometry.safeBounds.height.toFloat() / geometry.pageHeightPx, geometry.normalizedSafeBounds.height, .0001f)
        }
    }

    @Test fun `safe bounds must be positive and contained by the rendered page`() {
        assertFailsWith<IllegalArgumentException> {
            PageGeometry(100, 200, SafePageBounds(-1, 0, 100, 200))
        }
        assertFailsWith<IllegalArgumentException> {
            PageGeometry(100, 200, SafePageBounds(20, 20, 20, 180))
        }
    }
}
