package dev.riddle.magicpaper.paper

import android.graphics.RectF
import android.util.SizeF
import dev.riddle.magicpaper.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
class PageTransformTest {
    @Test
    fun `round trip preserves normalized geometry in portrait and landscape`() {
        val point = NormalizedPoint(0.25f, 0.75f, 0.02f)
        listOf(SizeF(1080f, 2400f), SizeF(2400f, 1080f), SizeF(2560f, 1600f)).forEach { size ->
            val transform = PageTransform(RectF(20f, 40f, size.width - 20f, size.height - 40f))
            val pixels = transform.toPixels(point)
            val restored = transform.toNormalized(pixels)
            assertEquals(point.radius * min(size.width - 40f, size.height - 80f), pixels.radius, 0.0001f)
            assertEquals(point.x, restored.x, 0.0001f)
            assertEquals(point.y, restored.y, 0.0001f)
            assertEquals(point.radius, restored.radius, 0.0001f)
        }
    }
}
