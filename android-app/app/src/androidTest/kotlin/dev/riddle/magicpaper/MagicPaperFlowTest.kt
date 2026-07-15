package dev.riddle.magicpaper

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.Base64
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.riddle.magicpaper.paper.MagicPaperView
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.conversation.FakeModelProvider
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.paperui.PaperPhase
import dev.riddle.magicpaper.paperui.PaperUiIntent
import dev.riddle.magicpaper.paperui.SelectedModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MagicPaperFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var initialAccelerometerRotation: String = "1"
    private var initialUserRotation: String = "0"

    @Before fun unlockPortraitPreference() {
        initialAccelerometerRotation = executeShellCommandAndWait("settings get system accelerometer_rotation").trim()
        initialUserRotation = executeShellCommandAndWait("settings get system user_rotation").trim()
        compose.activity.paperViewModel.onIntent(PaperUiIntent.SetPortraitLocked(false))
        compose.waitUntil(2_000) {
            !compose.activity.paperViewModel.state.value.portraitLocked &&
                compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @After fun resetPortraitPreference() {
        compose.activity.paperViewModel.onIntent(PaperUiIntent.SetPortraitLocked(false))
        compose.waitUntil(2_000) {
            compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        restoreDeviceRotation()
    }

    @Test fun touch_input_dissolves_thinks_streams_completes_rotates_and_recreates() {
        val phases = CopyOnWriteArrayList<PaperPhase>()
        val dissolveSeen = AtomicBoolean(false)
        val collection: Job = compose.activity.lifecycleScope.launch {
            compose.activity.paperViewModel.state.collect { state ->
                if (phases.lastOrNull() != state.phase) phases.add(state.phase)
                if (state.phase == PaperPhase.Preparing && state.renderModel.dissolveStage != null) dissolveSeen.set(true)
            }
        }
        try {
            drawStrokeThroughView(.2f, .3f, .6f, .7f)
            compose.waitUntil(8_000) { compose.activity.paperViewModel.state.value.phase == PaperPhase.Completed }
            compose.onNodeWithTag("paper_reply").assertExists()
            assertTrue(phases.contains(PaperPhase.Preparing))
            assertTrue(dissolveSeen.get())
            assertTrue(phases.contains(PaperPhase.Thinking))
            assertTrue(phases.contains(PaperPhase.Streaming))
            assertTrue(phases.contains(PaperPhase.Completed))

            drawStrokeThroughView(.15f, .25f, .55f, .65f)
            // Pause auto-submit so this slice isolates configuration/recreation geometry.
            compose.activity.paperViewModel.onIntent(PaperUiIntent.ShowHelp)
            compose.waitUntil(2_000) { compose.activity.paperViewModel.state.value.helpVisible }
            val beforeRotation = compose.activity.paperViewModel.state.value.renderModel.strokes.single().points
            val beforeRendered = compose.runOnUiThread { renderedInkBounds(requireNotNull(findPaper(compose.activity.window.decorView))) }
            val retainedViewModel = compose.activity.paperViewModel
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(5_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            assertSame(retainedViewModel, compose.activity.paperViewModel)
            assertEquals(beforeRotation, compose.activity.paperViewModel.state.value.renderModel.strokes.single().points)
            val afterRendered = compose.runOnUiThread { renderedInkBounds(requireNotNull(findPaper(compose.activity.window.decorView))) }
            assertEquals(beforeRendered.left, afterRendered.left, .03f)
            assertEquals(beforeRendered.top, afterRendered.top, .03f)
            assertEquals(beforeRendered.right, afterRendered.right, .03f)
            assertEquals(beforeRendered.bottom, afterRendered.bottom, .03f)

            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            assertSame(retainedViewModel, compose.activity.paperViewModel)
            assertEquals(beforeRotation, compose.activity.paperViewModel.state.value.renderModel.strokes.single().points)
        } finally {
            collection.cancel()
        }
    }

    @Test fun rune_opens_settings_and_portrait_lock_round_trips() {
        drawStrokeThroughView(.2f, .3f, .6f, .7f)
        compose.onNodeWithTag("magic_rune_touch").performClick()
        compose.onNodeWithText(compose.activity.getString(dev.riddle.magicpaper.settings.R.string.settings_title)).assertExists()
        compose.onNodeWithTag("portrait_lock").performClick()
        compose.waitUntil(2_000) { compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("magic_paper").assertExists()
        assertTrue(compose.activity.paperViewModel.state.value.renderModel.strokes.isNotEmpty())
    }

    @Test fun paper_and_changing_status_have_localized_accessibility_semantics() {
        compose.onNodeWithTag("magic_paper").assertContentDescriptionEquals(
            compose.activity.getString(dev.riddle.magicpaper.paperui.R.string.paper_surface_label),
        )
        compose.onNodeWithTag("paper_status", useUnmergedTree = true).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        compose.onNodeWithTag("paper_status").assertIsDisplayed().assertTextEquals("Paper ready")
    }

    @Test fun page_raster_geometry_tracks_the_current_window_instead_of_application_construction() {
        val container = (compose.activity.application as RiddleApplication).container
        runBlocking { container.profileRepository.select(null) }
        val fakeProvider = FakeModelProvider(flow {
            delay(500)
            emit(ModelEvent.TextDelta("The paper remembers."))
            delay(500)
            emit(ModelEvent.Completed(FinishReason.STOP))
        })
        container.selectModelForTests(
            SelectedModel(
                fakeProvider,
                "fake",
                ModelCapabilities(streaming = true, vision = true),
            ),
        )
        compose.activity.paperViewModel.onIntent(PaperUiIntent.Cancel)
        compose.waitUntil(2_000) { !compose.activity.paperViewModel.state.value.canCancel }
        runBlocking { container.memoryRepository.clearDraft() }
        compose.activity.getSharedPreferences("paper-session-v1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        compose.activity.paperViewModel.state.value.renderModel.strokes.forEach { stroke ->
            stroke.points.firstOrNull()?.let { point ->
                compose.activity.paperViewModel.onPaperIntent(PaperIntent.Erase(point))
            }
        }
        compose.waitUntil(2_000) { compose.activity.paperViewModel.state.value.renderModel.strokes.isEmpty() }
        fakeProvider.clearRecordedRequests()
        setDeviceRotation(0)
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        setDeviceRotation(1)
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        var paper = requireNotNull(findPaper(compose.activity.window.decorView))
        compose.waitUntil(2_000) {
            val geometry = container.pageGeometry.snapshot() ?: return@waitUntil false
            geometry.pageWidthPx == paper.width && geometry.pageHeightPx == paper.height
        }
        val landscapeGeometry = checkNotNull(container.pageGeometry.snapshot())
        assertEquals(paper.width, landscapeGeometry.pageWidthPx)
        assertEquals(paper.height, landscapeGeometry.pageHeightPx)
        assertEquals("a cleared page must not submit while rotating", 0, fakeProvider.recordedRequests.size)
        drawAcrossSafeBounds(paper, landscapeGeometry)
        compose.waitUntil(12_000) { fakeProvider.recordedRequests.size == 1 }
        val geometryAtProviderRequest = checkNotNull(container.pageGeometry.snapshot())
        val landscapeImage = decodeProviderImage(fakeProvider.recordedRequests.single().messages.last().imageDataUrl)
        assertTrue(
            "landscape PNG ${landscapeImage.width}x${landscapeImage.height}, " +
                "claimed=$landscapeGeometry, final=$geometryAtProviderRequest",
            landscapeImage.width > landscapeImage.height,
        )
        assertAspectMatchesSafeBounds(landscapeImage, landscapeGeometry)
        landscapeImage.recycle()

        val retained = compose.activity.paperViewModel
        setDeviceRotation(0)
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertSame(retained, compose.activity.paperViewModel)
        paper = requireNotNull(findPaper(compose.activity.window.decorView))
        compose.waitUntil(2_000) {
            val geometry = container.pageGeometry.snapshot() ?: return@waitUntil false
            geometry.pageWidthPx == paper.width && geometry.pageHeightPx == paper.height
        }
        val portraitGeometry = checkNotNull(container.pageGeometry.snapshot())
        drawAcrossSafeBounds(paper, portraitGeometry)
        compose.waitUntil(12_000) { fakeProvider.recordedRequests.size == 2 }
        val portraitImage = decodeProviderImage(fakeProvider.recordedRequests.last().messages.last().imageDataUrl)
        assertTrue(
            "portrait PNG ${portraitImage.width}x${portraitImage.height}, safe=${portraitGeometry.safeBounds}",
            portraitImage.height > portraitImage.width,
        )
        assertAspectMatchesSafeBounds(portraitImage, portraitGeometry)
        portraitImage.recycle()
    }

    private fun drawAcrossSafeBounds(
        paper: MagicPaperView,
        geometry: dev.riddle.magicpaper.paper.PageGeometry,
    ) {
        val bounds = geometry.safeBounds
        val inset = 8f
        drawStrokeThroughView(
            (bounds.left + inset) / paper.width,
            (bounds.top + inset) / paper.height,
            (bounds.right - inset) / paper.width,
            (bounds.bottom - inset) / paper.height,
        )
    }

    private fun setDeviceRotation(rotation: Int) {
        executeShellCommandAndWait("settings put system accelerometer_rotation 0")
        executeShellCommandAndWait("settings put system user_rotation $rotation")
    }

    private fun restoreDeviceRotation() {
        restoreSystemSetting("user_rotation", initialUserRotation)
        restoreSystemSetting("accelerometer_rotation", initialAccelerometerRotation)
    }

    private fun restoreSystemSetting(name: String, value: String) {
        if (value == "null") executeShellCommandAndWait("settings delete system $name")
        else executeShellCommandAndWait("settings put system $name $value")
    }

    private fun executeShellCommandAndWait(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun decodeProviderImage(dataUrl: String?): Bitmap {
        requireNotNull(dataUrl)
        val encoded = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
        check(encoded.isNotEmpty())
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun assertAspectMatchesSafeBounds(
        bitmap: Bitmap,
        geometry: dev.riddle.magicpaper.paper.PageGeometry,
    ) {
        val expected = geometry.safeBounds.width.toFloat() / geometry.safeBounds.height
        val actual = bitmap.width.toFloat() / bitmap.height
        assertEquals(expected, actual, .12f)
    }

    private fun drawStrokeThroughView(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        compose.activity.runOnUiThread {
            val paper = findPaper(compose.activity.window.decorView) ?: error("MagicPaperView not found")
            val down = android.os.SystemClock.uptimeMillis()
            singlePointer(paper, down, down, MotionEvent.ACTION_DOWN, fromX, fromY)
            singlePointer(paper, down, down + 20, MotionEvent.ACTION_MOVE, toX, toY)
            singlePointer(paper, down, down + 30, MotionEvent.ACTION_UP, toX, toY)
        }
        compose.waitForIdle()
    }

    private fun singlePointer(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x * view.width; this.y = y * view.height; pressure = 1f; size = .05f
        }
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
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
        val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply { x = 120f + index * 80f; y = 240f; pressure = 1f; size = .05f } }
        val event = MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun findPaper(view: View): MagicPaperView? {
        if (view is MagicPaperView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPaper(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun renderedInkBounds(view: MagicPaperView): RectF {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            val background = view.context.getColor(dev.riddle.magicpaper.paper.R.color.magic_paper_background)
            var left = view.width
            var top = view.height
            var right = -1
            var bottom = -1
            for (y in 0 until view.height step 2) for (x in 0 until view.width step 2) {
                if (bitmap.getPixel(x, y) != background) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            check(right >= left && bottom >= top) { "No rendered ink found" }
            RectF(left.toFloat() / view.width, top.toFloat() / view.height, right.toFloat() / view.width, bottom.toFloat() / view.height)
        } finally {
            bitmap.recycle()
        }
    }
}
