package dev.riddle.magicpaper.paper

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MagicPaperViewTest {
    @Test
    fun `stylus down is translated into stroke start`() {
        val view = view()
        val intents = mutableListOf<PaperIntent>()
        view.onPaperIntent = intents::add
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 200f, 300f))

        assertTrue(intents.single() is PaperIntent.StrokeStarted)
    }

    @Test
    fun `unrelated hover exit does not clear stylus palm rejection`() {
        val view = view()
        val intents = mutableListOf<PaperIntent>()
        view.onPaperIntent = intents::add
        view.onHoverEvent(event(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.TOOL_TYPE_STYLUS, 20f, 30f))
        view.onHoverEvent(event(MotionEvent.ACTION_HOVER_EXIT, MotionEvent.TOOL_TYPE_MOUSE, 20f, 30f))

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, 40f, 50f))

        assertTrue(intents.isEmpty())
    }

    private fun view() = MagicPaperView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 1000, 1000)
    }

    private fun event(action: Int, tool: Int, x: Float, y: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 7
            toolType = tool
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.8f
        }
        return MotionEvent.obtain(0, 1, action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
