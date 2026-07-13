package dev.riddle.magicpaper

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.paper.MagicPaperView
import dev.riddle.magicpaper.paperui.PaperUiIntent
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class MagicPaperFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun paper_draws_streams_opens_settings_and_preserves_draft() {
        compose.onNodeWithTag("magic_paper").assertExists()
        drawStroke("first")
        compose.mainClock.advanceTimeBy(2_800)
        compose.waitUntil(5_000) { compose.activity.paperViewModel.state.value.reply == "The paper remembers." }
        compose.onNodeWithTag("paper_reply").assertExists()

        drawStroke("draft")
        val beforeRotation = compose.activity.paperViewModel.state.value.renderModel.strokes.single().points
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertEquals(beforeRotation, compose.activity.paperViewModel.state.value.renderModel.strokes.single().points)
        synthesizeThreeFingerHold()
        compose.onNodeWithText(compose.activity.getString(dev.riddle.magicpaper.settings.R.string.settings_title)).assertExists()
        compose.onNodeWithTag("portrait_lock").performClick()
        compose.waitUntil(2_000) {
            compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("magic_paper").assertExists()
        assertEquals(1, compose.activity.paperViewModel.state.value.renderModel.strokes.size)
    }

    @Test fun disabling_portrait_lock_restores_unspecified_orientation() {
        compose.activity.paperViewModel.onIntent(PaperUiIntent.SetPortraitLocked(true))
        compose.waitForIdle()
        compose.activity.paperViewModel.onIntent(PaperUiIntent.SetPortraitLocked(false))
        compose.waitUntil(2_000) {
            compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun drawStroke(id: String) {
        val viewModel = compose.activity.paperViewModel
        viewModel.onPaperIntent(PaperIntent.StrokeStarted(id, PaperTool.PEN, NormalizedPoint(.2f, .3f, .01f)))
        viewModel.onPaperIntent(PaperIntent.PointAdded(id, NormalizedPoint(.6f, .7f, .02f)))
        viewModel.onPaperIntent(PaperIntent.StrokeEnded(id))
    }

    private fun synthesizeThreeFingerHold() {
        compose.activity.runOnUiThread {
            val paper = findPaper(compose.activity.window.decorView) ?: error("MagicPaperView not found")
            val downTime = android.os.SystemClock.uptimeMillis()
            dispatch(paper, downTime, downTime, MotionEvent.ACTION_DOWN, 1)
            dispatch(paper, downTime, downTime + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            dispatch(paper, downTime, downTime + 20, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3)
            dispatch(paper, downTime, downTime + 2_020, MotionEvent.ACTION_MOVE, 3)
        }
        compose.waitForIdle()
    }

    private fun dispatch(view: View, downTime: Long, eventTime: Long, action: Int, count: Int) {
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
            x = 120f + index * 80f
            y = 240f
            pressure = 1f
            size = .05f
        } }
        val event = MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun findPaper(view: View): MagicPaperView? {
        if (view is MagicPaperView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPaper(view.getChildAt(index))?.let { return it }
        return null
    }
}
