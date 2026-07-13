package dev.riddle.magicpaper.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URI

@Composable
fun ProviderSettingsRoute(viewModel: ProviderSettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshAsync() }
    ProviderSettingsScreen(
        state = state,
        onSave = viewModel::saveAsync,
        onValidate = viewModel::validateAsync,
        onEnabled = viewModel::setEnabledAsync,
        onSelect = { viewModel.selectAsync(it) },
        onDelete = viewModel::deleteAsync,
        onEditorChange = viewModel::updateEditor,
        onPreset = viewModel::applyPreset,
        onEdit = viewModel::editProfile,
        modifier = modifier,
    )
}

@Composable
fun ProviderSettingsScreen(
    state: ProviderSettingsUiState,
    onSave: (ProviderDraft, String) -> Unit,
    onValidate: (String, String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEditorChange: (ProviderEditorUiState) -> Unit,
    onPreset: (PresetKind, String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var credential by remember { mutableStateOf("") }
    val editor = state.editor
    val host = remember(editor.baseUrl) { runCatching { URI(editor.baseUrl.trim()).host }.getOrNull().orEmpty() }
    val openAiName = stringResource(R.string.provider_preset_openai)
    val deepSeekName = stringResource(R.string.provider_preset_deepseek)

    fun localizedPresetName(kind: PresetKind): String = when (kind) {
            PresetKind.OPENAI -> openAiName
            PresetKind.DEEPSEEK -> deepSeekName
            PresetKind.CUSTOM_HTTPS -> ""
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.provider_settings_title), style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(stringResource(R.string.provider_presets_label), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { onPreset(preset.kind, localizedPresetName(preset.kind)) },
                        enabled = state.controlsEnabled,
                    ) { Text(presetLabel(preset.kind)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editor.displayName, { onEditorChange(editor.copy(displayName = it)) }, label = { Text(stringResource(R.string.provider_name_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(editor.baseUrl, { onEditorChange(editor.copy(baseUrl = it)) }, label = { Text(stringResource(R.string.provider_url_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(editor.modelId, { onEditorChange(editor.copy(modelId = it)) }, label = { Text(stringResource(R.string.provider_model_label)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    credential,
                    { credential = it },
                    label = { Text(stringResource(R.string.provider_api_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (host.isNotBlank()) Text(stringResource(R.string.provider_destination_host, host))
                Button(
                    enabled = state.controlsEnabled && (credential.isNotBlank() || state.profiles.any { it.configuration.id == editor.id }),
                    onClick = {
                        onSave(
                            editor.draft(),
                            credential,
                        )
                        credential = ""
                    },
                ) { Text(stringResource(R.string.provider_save)) }
            }
        }
        if (state.operationInProgress) item { Text(stringResource(R.string.provider_operation_in_progress)) }
        state.error?.let { error -> item { Text(errorLabel(error), color = MaterialTheme.colorScheme.error) } }
        items(state.profiles, key = { it.configuration.id }) { profile ->
            val configuration = profile.configuration
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(configuration.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(configuration.baseUrl)
                    Text(validationLabel(profile.validationStatus))
                    val selectDescription = stringResource(R.string.provider_select_named, configuration.displayName)
                    val enabledDescription = stringResource(R.string.provider_enabled_named, configuration.displayName)
                    val editDescription = stringResource(R.string.provider_edit_named, configuration.displayName)
                    val deleteDescription = stringResource(R.string.provider_delete_named, configuration.displayName)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(
                            selected = state.selectedProfileId == configuration.id,
                            onClick = { onSelect(configuration.id) },
                            enabled = state.controlsEnabled && configuration.enabled,
                            modifier = Modifier.semantics { contentDescription = selectDescription },
                        )
                        Text(stringResource(R.string.provider_select))
                        Switch(
                            checked = configuration.enabled,
                            onCheckedChange = { onEnabled(configuration.id, it) },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.semantics { contentDescription = enabledDescription },
                        )
                        Text(stringResource(R.string.provider_enabled_label))
                    }
                    if (URI(configuration.baseUrl).host?.isNotBlank() == true) {
                        val destinationHost = URI(configuration.baseUrl).host
                        OutlinedButton(
                            onClick = { onValidate(configuration.id, destinationHost) },
                            enabled = state.controlsEnabled,
                        ) {
                            Text(stringResource(R.string.provider_test_host, destinationHost))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onEdit(configuration.id); credential = "" },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.semantics { contentDescription = editDescription },
                        ) { Text(stringResource(R.string.provider_edit)) }
                        TextButton(
                            onClick = { onDelete(configuration.id) },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.semantics { contentDescription = deleteDescription },
                        ) { Text(stringResource(R.string.provider_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun presetLabel(kind: PresetKind) = stringResource(when (kind) {
    PresetKind.OPENAI -> R.string.provider_preset_openai
    PresetKind.DEEPSEEK -> R.string.provider_preset_deepseek
    PresetKind.CUSTOM_HTTPS -> R.string.provider_preset_custom
})

@Composable
private fun validationLabel(status: ValidationStatus) = stringResource(when (status) {
    ValidationStatus.NotTested -> R.string.provider_validation_not_tested
    ValidationStatus.Testing -> R.string.provider_validation_testing
    ValidationStatus.Valid -> R.string.provider_validation_valid
    ValidationStatus.Invalid -> R.string.provider_validation_invalid
})

@Composable
private fun errorLabel(error: SettingsError) = stringResource(when (error) {
    SettingsError.InvalidEndpoint -> R.string.provider_error_invalid_endpoint
    SettingsError.CredentialUnavailable -> R.string.provider_error_credential
    SettingsError.StorageUnavailable -> R.string.provider_error_storage
    SettingsError.InconsistentStorage -> R.string.provider_error_inconsistent_storage
    SettingsError.ValidationFailed -> R.string.provider_error_validation
})
