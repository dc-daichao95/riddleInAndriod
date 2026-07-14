package dev.riddle.magicpaper

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riddle.magicpaper.paperui.MagicPaperRoute
import dev.riddle.magicpaper.paperui.PaperUiIntent
import dev.riddle.magicpaper.paperui.PaperViewModel
import dev.riddle.magicpaper.settings.AppSettingsScreen
import dev.riddle.magicpaper.settings.ProviderSettingsRoute

class MainActivity : ComponentActivity() {
    private val container get() = (application as RiddleApplication).container
    val paperViewModel: PaperViewModel by viewModels { container.paperViewModelFactory }
    private val providerSettingsViewModel: dev.riddle.magicpaper.settings.ProviderSettingsViewModel by viewModels {
        container.providerSettingsViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme {
                val state by paperViewModel.state.collectAsStateWithLifecycle()
                var settingsVisible by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(state.portraitLocked) {
                    this@MainActivity.requestedOrientation = if (state.portraitLocked) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                BackHandler(enabled = settingsVisible) {
                    settingsVisible = false
                    paperViewModel.onIntent(PaperUiIntent.SettingsClosed)
                }
                if (settingsVisible) {
                    AppSettingsScreen(
                        portraitLocked = state.portraitLocked,
                        onPortraitLockedChange = { paperViewModel.onIntent(PaperUiIntent.SetPortraitLocked(it)) },
                        settingsEntryMode = state.settingsEntryMode,
                        onSettingsEntryModeChange = {
                            paperViewModel.onIntent(PaperUiIntent.SetSettingsEntryMode(it))
                        },
                        onBack = {
                            settingsVisible = false
                            paperViewModel.onIntent(PaperUiIntent.SettingsClosed)
                        },
                        providerSettings = { ProviderSettingsRoute(providerSettingsViewModel) },
                    )
                } else {
                    MagicPaperRoute(paperViewModel, onOpenSettings = { settingsVisible = true })
                }
            }
        }
    }
}
