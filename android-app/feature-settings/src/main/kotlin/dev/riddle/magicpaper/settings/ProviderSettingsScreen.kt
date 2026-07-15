package dev.riddle.magicpaper.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.net.URI

@Composable
fun ProviderSettingsRoute(viewModel: ProviderSettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshAsync() }
    ProviderSettingsScreen(
        state = state,
        onReviewEndpoint = viewModel::reviewEndpoint,
        onValidateAndDiscover = viewModel::validateAndDiscoverAsync,
        onValidateManualModel = viewModel::validateManualModelAsync,
        onSelectDiscoveredModel = viewModel::selectDiscoveredModel,
        onSaveValidated = viewModel::saveValidatedProfileAsync,
        onAbandon = viewModel::abandonEditorAsync,
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
@OptIn(ExperimentalMaterial3Api::class)
fun ProviderSettingsScreen(
    state: ProviderSettingsUiState,
    onReviewEndpoint: () -> Unit,
    onValidateAndDiscover: (ProviderDraft, CharArray, String, () -> Unit) -> Unit,
    onValidateManualModel: (String) -> Unit,
    onSelectDiscoveredModel: (String) -> Unit,
    onSaveValidated: () -> Unit,
    onAbandon: () -> Unit,
    onValidate: (String, String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEditorChange: (ProviderEditorUiState) -> Unit,
    onPreset: (PresetKind, String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val credential = remember { TextFieldState() }
    val manualFallback = state.modelSelection as? ProviderModelSelection.ManualFallback
    var manualModel by remember(manualFallback?.presetModelId) {
        mutableStateOf(manualFallback?.presetModelId.orEmpty())
    }
    var modelsExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentAbandon by rememberUpdatedState(onAbandon)
    fun clearCredential() = credential.edit { replace(0, length, "") }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                clearCredential()
                currentAbandon()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            clearCredential()
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentAbandon()
        }
    }
    val editor = state.editor
    val openAiName = stringResource(R.string.provider_preset_openai)
    val deepSeekName = stringResource(R.string.provider_preset_deepseek)
    val apiKeyDescription = stringResource(R.string.provider_api_key_content_description)

    fun localizedPresetName(kind: PresetKind): String = when (kind) {
            PresetKind.OPENAI -> openAiName
            PresetKind.DEEPSEEK -> deepSeekName
            PresetKind.CUSTOM_HTTPS -> ""
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("provider_list"),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.provider_settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("provider_title").semantics { heading() }) }
        item {
            Text(stringResource(R.string.provider_presets_label), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { onPreset(preset.kind, localizedPresetName(preset.kind)) },
                        enabled = state.controlsEnabled,
                        modifier = Modifier.testTag("provider_preset_${preset.kind.name.lowercase()}"),
                    ) { Text(presetLabel(preset.kind)) }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.provider_step, state.setupStage.ordinal + 1, 3), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                if (state.setupStage == ProviderSetupStage.ENDPOINT) {
                    OutlinedTextField(editor.displayName, { onEditorChange(editor.copy(displayName = it)) }, label = { Text(stringResource(R.string.provider_name_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editor.baseUrl, { onEditorChange(editor.copy(baseUrl = it)) }, label = { Text(stringResource(R.string.provider_url_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    Button(onClick = onReviewEndpoint, enabled = state.controlsEnabled, modifier = Modifier.fillMaxWidth().testTag("provider_continue")) {
                        Text(stringResource(R.string.provider_continue))
                    }
                }
                if (state.setupStage == ProviderSetupStage.CREDENTIAL_VALIDATION) {
                    Text(stringResource(R.string.provider_destination_host, state.confirmedHost.orEmpty()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("provider_confirmed_host").semantics { heading() })
                    Text(stringResource(R.string.provider_api_key_label), style = MaterialTheme.typography.labelLarge)
                    BasicSecureTextField(
                        state = credential,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                            .padding(16.dp)
                            .testTag("provider_api_key")
                            .semantics {
                            contentDescription = apiKeyDescription
                        },
                    )
                    Button(
                        enabled = state.controlsEnabled && (credential.text.isNotBlank() || state.profiles.any { it.configuration.id == editor.id }),
                        onClick = {
                            onValidateAndDiscover(
                                editor.draft(),
                                credential.text.toString().toCharArray(),
                                state.confirmedHost.orEmpty(),
                                ::clearCredential,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("provider_validate_models"),
                    ) { Text(stringResource(R.string.provider_validate_models)) }
                    if (state.manualModelAllowed) {
                        val disclosure = when (manualFallback?.reason) {
                            ManualFallbackReason.EMPTY_CATALOG -> R.string.provider_manual_disclosure_empty
                            else -> R.string.provider_manual_disclosure
                        }
                        Text(stringResource(disclosure), modifier = Modifier.testTag("provider_manual_disclosure"))
                        OutlinedTextField(manualModel, { manualModel = it }, label = { Text(stringResource(R.string.provider_model_label)) }, modifier = Modifier.fillMaxWidth().testTag("provider_manual_model"))
                        OutlinedButton(onClick = { onValidateManualModel(manualModel) }, enabled = manualModel.isNotBlank(), modifier = Modifier.testTag("provider_manual_validate")) {
                            Text(stringResource(R.string.provider_manual_validate))
                        }
                    }
                }
                if (state.setupStage == ProviderSetupStage.MODEL_SELECTION) {
                    ExposedDropdownMenuBox(expanded = modelsExpanded, onExpandedChange = { modelsExpanded = it }) {
                        OutlinedTextField(
                            value = state.selectedDiscoveredModelId.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.provider_model_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelsExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("provider_model_selector"),
                        )
                        ExposedDropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                            state.discoveredModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName.ifBlank { model.id }) },
                                    onClick = { onSelectDiscoveredModel(model.id); modelsExpanded = false },
                                )
                            }
                        }
                    }
                    Button(onClick = onSaveValidated, enabled = state.selectedDiscoveredModelId != null && state.controlsEnabled, modifier = Modifier.fillMaxWidth().testTag("provider_save_validated")) {
                        Text(stringResource(R.string.provider_save))
                    }
                }
            }
        }
        if (state.operationInProgress || state.setupInProgress) item { Text(stringResource(R.string.provider_operation_in_progress), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.error?.let { error -> item { Text(errorLabel(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) } }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(
                            selected = state.selectedProfileId == configuration.id,
                            onClick = { onSelect(configuration.id) },
                            enabled = state.controlsEnabled && configuration.enabled,
                            modifier = Modifier.testTag("provider_select_${configuration.id}").semantics { contentDescription = selectDescription },
                        )
                        Text(stringResource(R.string.provider_select))
                        Switch(
                            checked = configuration.enabled,
                            onCheckedChange = { onEnabled(configuration.id, it) },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.testTag("provider_enabled_${configuration.id}").semantics { contentDescription = enabledDescription },
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onEdit(configuration.id); clearCredential() },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.testTag("provider_edit_${configuration.id}").semantics { contentDescription = editDescription },
                        ) { Text(stringResource(R.string.provider_edit)) }
                        TextButton(
                            onClick = { onDelete(configuration.id) },
                            enabled = state.controlsEnabled,
                            modifier = Modifier.testTag("provider_delete_${configuration.id}").semantics { contentDescription = deleteDescription },
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
    SettingsError.Authentication -> R.string.provider_error_authentication
    SettingsError.Authorization -> R.string.provider_error_authorization
    SettingsError.RateLimited -> R.string.provider_error_rate_limited
    SettingsError.Network -> R.string.provider_error_network
    SettingsError.Timeout -> R.string.provider_error_timeout
    SettingsError.InvalidResponse -> R.string.provider_error_invalid_response
    SettingsError.Cancelled -> R.string.provider_error_cancelled
})
