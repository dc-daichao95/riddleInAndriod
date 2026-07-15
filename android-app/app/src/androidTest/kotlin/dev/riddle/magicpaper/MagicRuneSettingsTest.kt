package dev.riddle.magicpaper

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.model.HandwritingLanguage
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.paperui.MagicPaperScreen
import dev.riddle.magicpaper.paperui.MotionScaleSource
import dev.riddle.magicpaper.paperui.PaperUiState
import dev.riddle.magicpaper.settings.AppSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import kotlinx.coroutines.flow.MutableStateFlow

class MagicRuneSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetPersistentSettings() {
        compose.activity.paperViewModel.onIntent(
            dev.riddle.magicpaper.paperui.PaperUiIntent.SetSettingsEntryMode(SettingsEntryMode.MAGIC_RUNE_BUTTON),
        )
        compose.activity.paperViewModel.onIntent(
            dev.riddle.magicpaper.paperui.PaperUiIntent.SetHandwritingLanguage(HandwritingLanguage.AUTOMATIC),
        )
        compose.waitUntil(2_000) {
            val state = compose.activity.paperViewModel.state.value
            state.settingsEntryMode == SettingsEntryMode.MAGIC_RUNE_BUTTON &&
                state.handwritingLanguage == HandwritingLanguage.AUTOMATIC
        }
    }

    @Test fun rune_is_only_shown_in_magic_rune_mode_with_localized_button_semantics_and_required_sizes() {
        showPaper(SettingsEntryMode.MAGIC_RUNE_BUTTON)

        compose.onNodeWithTag("magic_rune_touch")
            .assertExists()
            .assertContentDescriptionEquals(
                compose.activity.getString(dev.riddle.magicpaper.paperui.R.string.paper_open_settings),
            )
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertSizeDp(compose.onNodeWithTag("magic_rune_touch").fetchSemanticsNode().boundsInRoot, atLeast = 48f)
        assertSizeDp(compose.onNodeWithTag("magic_rune_visual", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot, exact = 30f)

        showPaper(SettingsEntryMode.THREE_FINGER_LONG_PRESS)
        compose.onNodeWithTag("magic_rune_touch").assertDoesNotExist()
    }

    @Test fun compact_large_font_layout_keeps_rune_and_settings_controls_inside_injected_insets() {
        val start = 21.dp
        val top = 29.dp
        val end = 25.dp
        val bottom = 37.dp
        val injectedInsets = WindowInsets(pxInt(start), pxInt(top), pxInt(end), pxInt(bottom))
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("inset_root")) {
                        MagicPaperScreen(
                            state = PaperUiState(settingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON),
                            onPaperIntent = {},
                            onIntent = {},
                            contentInsets = injectedInsets,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onNodeWithTag("inset_root").fetchSemanticsNode().boundsInRoot
        assertInside(
            compose.onNodeWithTag("magic_rune_touch").fetchSemanticsNode().boundsInRoot,
            root.copy(left = root.left + px(start), top = root.top + px(top), right = root.right - px(end), bottom = root.bottom - px(bottom)),
        )

        compose.activity.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("settings_inset_root")) {
                        AppSettingsScreen(
                            portraitLocked = false,
                            onPortraitLockedChange = {},
                            onBack = {},
                            providerSettings = {
                                LazyColumn(Modifier.fillMaxSize().testTag("provider_scroll")) {
                                    item { Button({}, Modifier.testTag("provider_primary")) { Text("Provider primary") } }
                                    item { Spacer(Modifier.height(600.dp)) }
                                    item { Button({}, Modifier.testTag("provider_last")) { Text("Provider last") } }
                                }
                            },
                            contentInsets = injectedInsets,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val settingsRoot = compose.onNodeWithTag("settings_inset_root").fetchSemanticsNode().boundsInRoot
        val safe = settingsRoot.copy(
            left = settingsRoot.left + px(start),
            top = settingsRoot.top + px(top),
            right = settingsRoot.right - px(end),
            bottom = settingsRoot.bottom - px(bottom),
        )
        assertInside(compose.onNodeWithTag("settings_back").fetchSemanticsNode().boundsInRoot, safe)
        assertInside(compose.onNodeWithTag("portrait_lock").fetchSemanticsNode().boundsInRoot, safe)
        compose.onNodeWithTag("provider_scroll").performScrollToIndex(2)
        assertInside(compose.onNodeWithTag("provider_last").fetchSemanticsNode().boundsInRoot, safe)
    }

    @Test fun help_describes_the_active_settings_entry_mode() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    MagicPaperScreen(
                        PaperUiState(helpVisible = true, settingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON),
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText(
            compose.activity.getString(dev.riddle.magicpaper.paperui.R.string.paper_help_body_rune),
        ).assertExists()

        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    MagicPaperScreen(
                        PaperUiState(helpVisible = true, settingsEntryMode = SettingsEntryMode.THREE_FINGER_LONG_PRESS),
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText(
            compose.activity.getString(dev.riddle.magicpaper.paperui.R.string.paper_help_body_three_finger),
        ).assertExists()
    }

    @Test fun settings_can_select_and_persist_each_extensible_entry_mode() {
        compose.onNodeWithTag("magic_rune_touch").performClick()
        compose.onNodeWithTag("settings_entry_three_finger_long_press").performClick()
        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.settingsEntryMode == SettingsEntryMode.THREE_FINGER_LONG_PRESS
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.settingsEntryMode == SettingsEntryMode.THREE_FINGER_LONG_PRESS
        }

        compose.activity.paperViewModel.onIntent(dev.riddle.magicpaper.paperui.PaperUiIntent.OpenSettings)
        compose.onNodeWithTag("settings_entry_magic_rune_button").performClick()
        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.settingsEntryMode == SettingsEntryMode.MAGIC_RUNE_BUTTON
        }
        pressBack()
        compose.onNodeWithTag("magic_rune_touch").assertExists()
    }

    @Test fun settings_can_select_and_persist_simplified_Chinese_handwriting() {
        compose.onNodeWithTag("magic_rune_touch").performClick()
        compose.onNodeWithTag("handwriting_language_simplified_chinese").performClick()
        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.handwritingLanguage ==
                HandwritingLanguage.SIMPLIFIED_CHINESE
        }

        compose.activityRule.scenario.recreate()

        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.handwritingLanguage ==
                HandwritingLanguage.SIMPLIFIED_CHINESE
        }
    }

    @Test fun runtime_motion_scale_changes_update_rune_without_unrelated_recomposition() {
        val scales = MutableStateFlow(0f)
        val source = MotionScaleSource { scales }
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    MagicPaperScreen(
                        PaperUiState(settingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON),
                        {},
                        {},
                        motionScaleSource = source,
                    )
                }
            }
        }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("magic_rune_static", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("magic_rune_shimmer_count_0", useUnmergedTree = true).assertExists()

        scales.value = 1f
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("magic_rune_shimmer_count_1", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }

        scales.value = 0f
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("magic_rune_static", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("magic_rune_shimmer_count_1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("magic_rune_static", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("magic_rune_shimmer_count_1", useUnmergedTree = true).assertExists()

        scales.value = 1f
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTag("magic_rune_shimmer_count_2", useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }

        scales.value = 1f
        compose.waitForIdle()
        compose.onNodeWithTag("magic_rune_shimmer_count_2", useUnmergedTree = true).assertExists()
    }

    @Test fun tapping_rune_preserves_draft_and_back_closes_settings_without_finishing_activity() {
        val existingStrokeCount = compose.activity.paperViewModel.state.value.renderModel.strokes.size
        val point = NormalizedPoint(.25f, .35f, .5f)
        compose.activity.paperViewModel.onPaperIntent(PaperIntent.StrokeStarted("draft", PaperTool.PEN, point))
        compose.activity.paperViewModel.onPaperIntent(PaperIntent.StrokeEnded("draft"))
        compose.waitUntil(2_000) {
            compose.activity.paperViewModel.state.value.renderModel.strokes.size == existingStrokeCount + 1
        }
        val draft = compose.activity.paperViewModel.state.value.renderModel.strokes

        compose.onNodeWithTag("magic_rune_touch").performClick()
        compose.onNodeWithTag("settings_back").assertExists()
        assertEquals(draft, compose.activity.paperViewModel.state.value.renderModel.strokes)

        pressBack()
        compose.onNodeWithTag("magic_paper").assertExists()
        assertEquals(draft, compose.activity.paperViewModel.state.value.renderModel.strokes)
        assertFalse(compose.activity.isFinishing)
        assertTrue(compose.activityRule.scenario.state.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun showPaper(mode: SettingsEntryMode) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    MagicPaperScreen(PaperUiState(settingsEntryMode = mode), {}, {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSizeDp(bounds: Rect, exact: Float? = null, atLeast: Float? = null) {
        val density = compose.activity.resources.displayMetrics.density
        val width = bounds.width / density
        val height = bounds.height / density
        exact?.let {
            assertTrue("width=$width dp", width in it - .6f..it + .6f)
            assertTrue("height=$height dp", height in it - .6f..it + .6f)
        }
        atLeast?.let {
            assertTrue("width=$width dp", width >= it)
            assertTrue("height=$height dp", height >= it)
        }
    }

    private fun assertInside(actual: Rect, expected: Rect) {
        val tolerancePx = .5f
        assertTrue(
            "$actual not inside $expected",
            actual.left >= expected.left - tolerancePx &&
                actual.top >= expected.top - tolerancePx &&
                actual.right <= expected.right + tolerancePx &&
                actual.bottom <= expected.bottom + tolerancePx,
        )
    }

    private fun px(value: androidx.compose.ui.unit.Dp): Float = value.value * compose.activity.resources.displayMetrics.density
    private fun pxInt(value: androidx.compose.ui.unit.Dp): Int = px(value).toInt()
}
