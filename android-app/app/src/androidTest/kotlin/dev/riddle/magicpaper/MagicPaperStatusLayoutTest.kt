package dev.riddle.magicpaper

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.riddle.magicpaper.paperui.MagicPaperScreen
import dev.riddle.magicpaper.paperui.PaperPhase
import dev.riddle.magicpaper.paperui.PaperUiState
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MagicPaperStatusLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun english_preparation_status_does_not_overlap_rune_at_320dp_and_2x_font() {
        assertStatusAndRuneDoNotOverlap(PaperPhase.PreparingRecognition, Locale.US)
    }

    @Test fun english_long_failure_does_not_overlap_rune_at_320dp_and_2x_font() {
        assertStatusAndRuneDoNotOverlap(PaperPhase.RecognitionPreparationFailed, Locale.US)
    }

    @Test fun chinese_preparation_status_does_not_overlap_rune_at_320dp_and_2x_font() {
        assertStatusAndRuneDoNotOverlap(PaperPhase.PreparingRecognition, Locale.SIMPLIFIED_CHINESE)
    }

    @Test fun chinese_long_failure_does_not_overlap_rune_at_320dp_and_2x_font() {
        assertStatusAndRuneDoNotOverlap(PaperPhase.RecognitionPreparationFailed, Locale.SIMPLIFIED_CHINESE)
    }

    private fun assertStatusAndRuneDoNotOverlap(phase: PaperPhase, locale: Locale) {
        compose.setContent {
            val baseContext = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val localizedContext = baseContext.createConfigurationContext(configuration)
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                Box(Modifier.width(320.dp).height(640.dp).testTag("layout_root")) {
                    MagicPaperScreen(
                        state = PaperUiState(phase = phase),
                        onPaperIntent = {},
                        onIntent = {},
                        contentInsets = WindowInsets(0),
                    )
                }
            }
        }

        val root = compose.onNodeWithTag("layout_root").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("paper_status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val rune = compose.onNodeWithTag("magic_rune_touch").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("paper_status").assertTextContains(
            when {
                locale.language == "zh" && phase == PaperPhase.PreparingRecognition -> "正在准备手写识别"
                locale.language == "zh" -> "无法准备手写识别"
                phase == PaperPhase.PreparingRecognition -> "Preparing handwriting recognition"
                else -> "Handwriting recognition could not be prepared"
            },
            substring = true,
        )
        compose.onNodeWithTag("paper_status").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        assertTrue(status.left >= root.left && status.right <= root.right)
        assertTrue(status.top >= root.top && status.bottom <= root.bottom)
        assertTrue("status=$status overlaps rune=$rune", status.right <= rune.left)
        assertTrue(rune.width >= 48f)
    }
}
