package dev.riddle.magicpaper.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.riddle.magicpaper.model.SettingsEntryMode
import dev.riddle.magicpaper.model.HandwritingLanguage

@Composable
fun AppSettingsScreen(
    portraitLocked: Boolean,
    onPortraitLockedChange: (Boolean) -> Unit,
    settingsEntryMode: SettingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON,
    onSettingsEntryModeChange: (SettingsEntryMode) -> Unit = {},
    handwritingLanguage: HandwritingLanguage = HandwritingLanguage.AUTOMATIC,
    onHandwritingLanguageChange: (HandwritingLanguage) -> Unit = {},
    onBack: () -> Unit,
    providerSettings: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                Text(stringResource(R.string.settings_back))
            }
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("settings_scroll")) {
            item(key = "portrait_lock") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.portrait_lock), Modifier.weight(1f))
                    Switch(
                        checked = portraitLocked,
                        onCheckedChange = onPortraitLockedChange,
                        modifier = Modifier.testTag("portrait_lock"),
                    )
                }
            }
            item(key = "settings_entry_title") {
                Text(
                    stringResource(R.string.settings_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            SettingsEntryMode.entries.forEach { mode ->
                item(key = "settings_entry_${mode.persistedId}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = settingsEntryMode == mode,
                                onClick = { onSettingsEntryModeChange(mode) },
                                role = Role.RadioButton,
                            )
                            .testTag("settings_entry_${mode.persistedId}")
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settingsEntryMode == mode, onClick = null)
                        Text(stringResource(entryModeLabel(mode)), Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "handwriting_language_title") {
                Text(
                    stringResource(R.string.handwriting_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            HandwritingLanguage.entries.forEach { language ->
                item(key = "handwriting_language_${language.persistedId}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = handwritingLanguage == language,
                                onClick = { onHandwritingLanguageChange(language) },
                                role = Role.RadioButton,
                            )
                            .testTag("handwriting_language_${language.persistedId}")
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = handwritingLanguage == language, onClick = null)
                        Text(stringResource(handwritingLanguageLabel(language)), Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "provider_settings") {
                Box(Modifier.fillMaxWidth().fillParentMaxHeight()) {
                    providerSettings()
                }
            }
        }
    }
}

private fun handwritingLanguageLabel(language: HandwritingLanguage): Int = when (language) {
    HandwritingLanguage.AUTOMATIC -> R.string.handwriting_language_automatic
    HandwritingLanguage.SIMPLIFIED_CHINESE -> R.string.handwriting_language_simplified_chinese
    HandwritingLanguage.TRADITIONAL_CHINESE -> R.string.handwriting_language_traditional_chinese
    HandwritingLanguage.ENGLISH -> R.string.handwriting_language_english
}

private fun entryModeLabel(mode: SettingsEntryMode): Int = when (mode) {
    SettingsEntryMode.MAGIC_RUNE_BUTTON -> R.string.settings_entry_magic_rune
    SettingsEntryMode.THREE_FINGER_LONG_PRESS -> R.string.settings_entry_three_finger
}
