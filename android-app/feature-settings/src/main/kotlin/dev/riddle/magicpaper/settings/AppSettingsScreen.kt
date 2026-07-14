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

@Composable
fun AppSettingsScreen(
    portraitLocked: Boolean,
    onPortraitLockedChange: (Boolean) -> Unit,
    settingsEntryMode: SettingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON,
    onSettingsEntryModeChange: (SettingsEntryMode) -> Unit = {},
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
        Text(
            stringResource(R.string.settings_entry_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SettingsEntryMode.entries.forEach { mode ->
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
        Box(Modifier.fillMaxWidth().weight(1f)) {
            providerSettings()
        }
    }
}

private fun entryModeLabel(mode: SettingsEntryMode): Int = when (mode) {
    SettingsEntryMode.MAGIC_RUNE_BUTTON -> R.string.settings_entry_magic_rune
    SettingsEntryMode.THREE_FINGER_LONG_PRESS -> R.string.settings_entry_three_finger
}
