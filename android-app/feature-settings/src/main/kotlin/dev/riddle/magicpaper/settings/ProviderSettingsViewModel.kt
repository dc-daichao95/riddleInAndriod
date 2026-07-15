package dev.riddle.magicpaper.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riddle.magicpaper.model.*
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.UUID

fun interface ProviderFactory {
    fun create(configuration: ProviderConfiguration): ModelProvider
}

interface ProviderProfileRepository {
    suspend fun list(): List<ProviderConfiguration>
    suspend fun upsert(profile: ProviderConfiguration)
    suspend fun delete(id: String)
    suspend fun select(id: String?)
    suspend fun selectedId(): String?
}

data class ProviderDraft(
    val id: String,
    val type: ProviderType,
    val displayName: String,
    val baseUrl: String,
    val credentialAlias: String,
    val defaultModelId: String?,
    val enabled: Boolean,
    val capabilities: ModelCapabilities,
)

enum class PresetKind { OPENAI, DEEPSEEK, CUSTOM_HTTPS }

data class ProviderPreset(
    val kind: PresetKind,
    val type: ProviderType,
    val baseUrl: String?,
    val defaultModelId: String?,
    val capabilities: ModelCapabilities,
)

enum class ValidationStatus { NotTested, Testing, Valid, Invalid }
enum class ProviderSetupStage { ENDPOINT, CREDENTIAL_VALIDATION, MODEL_SELECTION }
enum class ManualFallbackReason { UNSUPPORTED_DISCOVERY, EMPTY_CATALOG }

sealed interface ProviderModelSelection {
    data object None : ProviderModelSelection
    data class Catalog(val models: List<ModelDescriptor>, val selectedModelId: String) : ProviderModelSelection
    data class ManualFallback(val presetModelId: String, val reason: ManualFallbackReason) : ProviderModelSelection
    data class ValidatedManual(val model: ModelDescriptor) : ProviderModelSelection
}

data class ProviderProfileUiModel(
    val configuration: ProviderConfiguration,
    val validationStatus: ValidationStatus = ValidationStatus.NotTested,
)

data class ProviderEditorUiState(
    val id: String = UUID.randomUUID().toString(),
    val type: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    val displayName: String = "",
    val baseUrl: String = "",
    val modelId: String = "",
    val credentialAlias: String = "provider-$id",
    val capabilities: ModelCapabilities = ModelCapabilities(streaming = true, systemMessages = true),
) {
    fun draft() = ProviderDraft(id, type, displayName, baseUrl, credentialAlias, modelId, true, capabilities)
}

data class ProviderSettingsUiState(
    val profiles: List<ProviderProfileUiModel> = emptyList(),
    val presets: List<ProviderPreset> = DEFAULT_PRESETS,
    val selectedProfileId: String? = null,
    val editor: ProviderEditorUiState = ProviderEditorUiState(),
    val operationInProgress: Boolean = false,
    val setupInProgress: Boolean = false,
    val error: SettingsError? = null,
    val setupStage: ProviderSetupStage = ProviderSetupStage.ENDPOINT,
    val confirmedHost: String? = null,
    val discoveredModels: List<ModelDescriptor> = emptyList(),
    val selectedDiscoveredModelId: String? = null,
    val manualModelAllowed: Boolean = false,
    val discoveryError: ModelError? = null,
    val modelSelection: ProviderModelSelection = ProviderModelSelection.None,
) {
    val controlsEnabled: Boolean get() = !operationInProgress && !setupInProgress
}

enum class SettingsError {
    InvalidEndpoint, CredentialUnavailable, StorageUnavailable, InconsistentStorage, ValidationFailed,
    Authentication, Authorization, RateLimited, Network, Timeout, InvalidResponse, Cancelled,
}

sealed interface SettingsOperation {
    data object Success : SettingsOperation
    data class InvalidEndpoint(val reason: SettingsError = SettingsError.InvalidEndpoint) : SettingsOperation
    data class HostConfirmationRequired(val host: String) : SettingsOperation
    data object ManualModelRequired : SettingsOperation
    data class Failed(val reason: SettingsError) : SettingsOperation
}

class ProviderSettingsViewModel(
    private val profiles: ProviderProfileRepository,
    private val credentials: CredentialStore,
    private val providerFactory: ProviderFactory,
    private val credentialTransactions: CredentialTransactionCoordinator,
) : ViewModel() {
    private class ErasableSecret(private val value: CharArray) {
        fun consume(): String = String(value).also { clear() }
        fun clear() = value.fill('\u0000')
    }

    private data class PendingValidation(
        val profile: ProviderConfiguration,
        val candidate: CandidateCredential?,
        val generation: Long,
        val presetModelId: String?,
    )

    private var pendingValidation: PendingValidation? = null
    private var activeSetupJob: Job? = null
    private var setupGeneration = 0L
    private val mutableState = MutableStateFlow(ProviderSettingsUiState())
    val state: StateFlow<ProviderSettingsUiState> = mutableState.asStateFlow()

    fun refreshAsync() = launchOperation { refresh() }
    fun validateAsync(id: String, confirmedHost: String) = launchOperation { validate(id, confirmedHost) }
    fun deleteAsync(id: String) = launchOperation { delete(id) }
    fun setEnabledAsync(id: String, enabled: Boolean) = launchOperation { setEnabled(id, enabled) }
    fun selectAsync(id: String?) = launchOperation { select(id) }
    fun validateAndDiscoverAsync(
        draft: ProviderDraft,
        secret: CharArray,
        confirmedHost: String,
        onSecretAccepted: () -> Unit = {},
    ) {
        val submission = ErasableSecret(secret)
        val job = launchSetupOperation { generation ->
            validateAndDiscoverInternal(draft, submission, confirmedHost, generation, onSecretAccepted)
        }
        if (job == null) submission.clear() else job.invokeOnCompletion { submission.clear() }
    }
    fun validateManualModelAsync(modelId: String) {
        val generation = pendingValidation?.generation ?: return
        launchSetupOperation(generation) { validateManualModel(it, modelId) }
    }
    fun saveValidatedProfileAsync() = launchOperation { saveValidatedProfile() }
    fun abandonEditorAsync() = cancelAndAbandonAsync()

    fun cancelAndAbandonAsync() {
        val job = activeSetupJob
        val cleanupGeneration = ++setupGeneration
        activeSetupJob = null
        val candidate = pendingValidation?.candidate
        pendingValidation = null
        job?.cancel()
        resetSetupState()
        mutableState.value = mutableState.value.copy(setupInProgress = false)
        viewModelScope.launch {
            if (candidate != null) {
                val result = withContext(NonCancellable) { credentialTransactions.abandon(candidate) }
                if (setupGeneration == cleanupGeneration) transactionResult(result)
            }
        }
    }

    suspend fun validateAndDiscover(
        draft: ProviderDraft,
        secret: String,
        confirmedHost: String,
    ): SettingsOperation = validateAndDiscoverInternal(
        draft, ErasableSecret(secret.toCharArray()), confirmedHost, ++setupGeneration, {},
    )

    private suspend fun validateAndDiscoverInternal(
        draft: ProviderDraft,
        secret: ErasableSecret,
        confirmedHost: String,
        generation: Long,
        onSecretAccepted: () -> Unit,
    ): SettingsOperation {
        val abandoned = abandonEditor(invalidateGeneration = false)
        if (abandoned !is SettingsOperation.Success) return abandoned
        if (generation != setupGeneration) return SettingsOperation.Failed(SettingsError.Cancelled)
        val normalizedBaseUrl = try { normalizeBaseUrl(draft.baseUrl) } catch (_: IllegalArgumentException) {
            return fail(SettingsError.InvalidEndpoint, SettingsOperation.InvalidEndpoint())
        }
        val host = URI(normalizedBaseUrl).host
        if (!host.equals(confirmedHost.trim(), ignoreCase = true)) {
            return SettingsOperation.HostConfirmationRequired(host)
        }
        val existing = mutableState.value.profiles.firstOrNull { it.configuration.id == draft.id }?.configuration
        val candidate = when (val begin = acceptCandidate(secret, draft.id, existing?.credentialAlias)) {
                is CredentialTransactionResult.Success -> begin.value?.also {
                    if (generation == setupGeneration) onSecretAccepted()
                }
                CredentialTransactionResult.CredentialUnavailable -> return fail(SettingsError.CredentialUnavailable)
                CredentialTransactionResult.StorageUnavailable -> return fail(SettingsError.StorageUnavailable)
                CredentialTransactionResult.InconsistentStorage -> return fail(SettingsError.InconsistentStorage)
        }
        val alias = candidate?.candidateAlias ?: existing?.credentialAlias
            ?: return fail(SettingsError.CredentialUnavailable)
        val temporary = ProviderConfiguration(
            draft.id, draft.type, draft.displayName.trim(), normalizedBaseUrl, alias, null,
            draft.enabled, draft.capabilities,
        )
        if (generation != setupGeneration) {
            if (candidate != null) credentialTransactions.abandon(candidate)
            return SettingsOperation.Failed(SettingsError.Cancelled)
        }
        val presetModelId = draft.defaultModelId?.trim()?.takeIf(String::isNotEmpty)
        pendingValidation = PendingValidation(temporary, candidate, generation, presetModelId)
        mutableState.value = mutableState.value.copy(
            setupStage = ProviderSetupStage.CREDENTIAL_VALIDATION,
            confirmedHost = host,
            discoveredModels = emptyList(),
            selectedDiscoveredModelId = null,
            manualModelAllowed = false,
            discoveryError = null,
            modelSelection = ProviderModelSelection.None,
            error = null,
        )
        return try {
            when (val discovery = providerFactory.create(temporary).listModels()) {
                is ModelDiscoveryResult.Success -> {
                    if (generation != setupGeneration) return SettingsOperation.Failed(SettingsError.Cancelled)
                    val models = discovery.models.distinctBy { it.id }.sortedBy { it.id }
                    if (models.isEmpty()) enableManualFallback(generation, ManualFallbackReason.EMPTY_CATALOG)
                    else {
                        val selectedModelId = presetModelId?.takeIf { preset -> models.any { it.id == preset } }
                            ?: models.first().id
                        mutableState.value = mutableState.value.copy(
                            setupStage = ProviderSetupStage.MODEL_SELECTION,
                            discoveredModels = models,
                            selectedDiscoveredModelId = selectedModelId,
                            modelSelection = ProviderModelSelection.Catalog(models, selectedModelId),
                        )
                        SettingsOperation.Success
                    }
                }
                ModelDiscoveryResult.Unsupported -> enableManualFallback(generation, ManualFallbackReason.UNSUPPORTED_DISCOVERY)
                is ModelDiscoveryResult.Failed -> failDiscovery(discovery.error, generation)
            }
        } catch (_: TimeoutCancellationException) {
            failDiscovery(ModelError.Timeout, generation)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (generation == setupGeneration) failDiscovery(ModelError.Cancelled, generation)
            }
            throw cancelled
        } catch (_: Exception) {
            failDiscovery(ModelError.Network(), generation)
        }
    }

    suspend fun validateManualModel(modelId: String): SettingsOperation =
        validateManualModel(pendingValidation?.generation ?: setupGeneration, modelId)

    private suspend fun validateManualModel(generation: Long, modelId: String): SettingsOperation {
        val pending = pendingValidation ?: return fail(SettingsError.StorageUnavailable)
        if (pending.generation != generation || generation != setupGeneration) return fail(SettingsError.Cancelled)
        if (!mutableState.value.manualModelAllowed || modelId.isBlank()) return fail(SettingsError.InvalidEndpoint)
        val configuration = pending.profile.copy(defaultModelId = modelId.trim())
        return try {
            when (val validation = withTimeout(10_000) {
                providerFactory.create(configuration).validate(configuration)
            }) {
                ValidationResult.Valid -> {
                    if (generation != setupGeneration) return SettingsOperation.Failed(SettingsError.Cancelled)
                    pendingValidation = pending.copy(profile = configuration)
                    mutableState.value = mutableState.value.copy(
                        setupStage = ProviderSetupStage.MODEL_SELECTION,
                        discoveredModels = listOf(ModelDescriptor(modelId.trim(), modelId.trim(), configuration.capabilities)),
                        selectedDiscoveredModelId = modelId.trim(),
                        manualModelAllowed = false,
                        discoveryError = null,
                        error = null,
                        modelSelection = ProviderModelSelection.ValidatedManual(
                            ModelDescriptor(modelId.trim(), modelId.trim(), configuration.capabilities),
                        ),
                    )
                    SettingsOperation.Success
                }
                is ValidationResult.Invalid -> failDiscovery(validation.error, generation)
            }
        } catch (_: TimeoutCancellationException) {
            failDiscovery(ModelError.Timeout, generation)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (generation == setupGeneration) failDiscovery(ModelError.Cancelled, generation)
            }
            throw cancelled
        } catch (_: Exception) {
            failDiscovery(ModelError.Network(), generation)
        }
    }

    fun selectDiscoveredModel(modelId: String) {
        if (mutableState.value.discoveredModels.any { it.id == modelId }) {
            val selection = mutableState.value.modelSelection
            mutableState.value = mutableState.value.copy(
                selectedDiscoveredModelId = modelId,
                modelSelection = if (selection is ProviderModelSelection.Catalog) {
                    selection.copy(selectedModelId = modelId)
                } else selection,
            )
        }
    }

    suspend fun saveValidatedProfile(): SettingsOperation {
        val pending = pendingValidation ?: return fail(SettingsError.StorageUnavailable)
        if (pending.generation != setupGeneration) return fail(SettingsError.Cancelled)
        val selection = mutableState.value.modelSelection
        if (selection !is ProviderModelSelection.Catalog && selection !is ProviderModelSelection.ValidatedManual) {
            return fail(SettingsError.InvalidEndpoint)
        }
        val modelId = mutableState.value.selectedDiscoveredModelId ?: return fail(SettingsError.InvalidEndpoint)
        val profile = pending.profile.copy(defaultModelId = modelId)
        val result = pending.candidate?.let { credentialTransactions.commit(it, profile) }
            ?: credentialTransactions.commitMetadata(profile, selectionRequested = true)
        return when (result) {
            is CredentialTransactionResult.Success -> {
                replaceProfile(profile)
                mutableState.value = mutableState.value.copy(selectedProfileId = profile.id)
                pendingValidation = null
                SettingsOperation.Success
            }
            CredentialTransactionResult.CredentialUnavailable -> fail(SettingsError.CredentialUnavailable)
            CredentialTransactionResult.StorageUnavailable -> fail(SettingsError.StorageUnavailable)
            CredentialTransactionResult.InconsistentStorage -> fail(SettingsError.InconsistentStorage)
        }
    }

    suspend fun abandonEditor(invalidateGeneration: Boolean = true): SettingsOperation {
        if (invalidateGeneration) setupGeneration++
        val candidate = pendingValidation?.candidate
        pendingValidation = null
        val cleanup = if (candidate != null) credentialTransactions.abandon(candidate) else CredentialTransactionResult.Success(Unit)
        resetSetupState()
        return transactionResult(cleanup)
    }

    private fun resetSetupState() {
        mutableState.value = mutableState.value.copy(
            setupStage = ProviderSetupStage.ENDPOINT,
            confirmedHost = null,
            discoveredModels = emptyList(),
            selectedDiscoveredModelId = null,
            manualModelAllowed = false,
            discoveryError = null,
            modelSelection = ProviderModelSelection.None,
        )
    }

    private suspend fun acceptCandidate(
        submission: ErasableSecret,
        profileId: String,
        priorAlias: String?,
    ): CredentialTransactionResult<CandidateCredential?> {
        val secret = submission.consume()
        if (secret.isBlank()) return CredentialTransactionResult.Success(null)
        return when (val result = credentialTransactions.begin(profileId, priorAlias, true, secret)) {
            is CredentialTransactionResult.Success -> CredentialTransactionResult.Success(result.value)
            CredentialTransactionResult.CredentialUnavailable -> CredentialTransactionResult.CredentialUnavailable
            CredentialTransactionResult.StorageUnavailable -> CredentialTransactionResult.StorageUnavailable
            CredentialTransactionResult.InconsistentStorage -> CredentialTransactionResult.InconsistentStorage
        }
    }

    private fun enableManualFallback(generation: Long, reason: ManualFallbackReason): SettingsOperation {
        if (generation != setupGeneration) return SettingsOperation.Failed(SettingsError.Cancelled)
        val presetModelId = pendingValidation?.presetModelId.orEmpty()
        mutableState.value = mutableState.value.copy(
            manualModelAllowed = true,
            selectedDiscoveredModelId = presetModelId.takeIf(String::isNotEmpty),
            discoveryError = null,
            modelSelection = ProviderModelSelection.ManualFallback(presetModelId, reason),
        )
        return SettingsOperation.ManualModelRequired
    }

    private suspend fun failDiscovery(error: ModelError, generation: Long): SettingsOperation {
        val pending = pendingValidation?.takeIf { it.generation == generation }
        val candidate = pending?.candidate
        if (pending != null) pendingValidation = null
        val cleanup = if (candidate != null) withContext(NonCancellable) {
            credentialTransactions.abandon(candidate)
        } else CredentialTransactionResult.Success(Unit)
        if (generation != setupGeneration) return SettingsOperation.Failed(SettingsError.Cancelled)
        val mapped = when (error) {
            is ModelError.Authentication -> SettingsError.Authentication
            is ModelError.Authorization -> SettingsError.Authorization
            is ModelError.RateLimited -> SettingsError.RateLimited
            is ModelError.Network, is ModelError.Server -> SettingsError.Network
            ModelError.Timeout -> SettingsError.Timeout
            ModelError.Cancelled -> SettingsError.Cancelled
            else -> SettingsError.InvalidResponse
        }
        mutableState.value = mutableState.value.copy(discoveryError = error)
        if (cleanup !is CredentialTransactionResult.Success) return transactionResult(cleanup)
        return fail(mapped)
    }

    fun updateEditor(editor: ProviderEditorUiState) {
        invalidatePendingSetup()
        mutableState.value = mutableState.value.copy(editor = editor)
    }

    fun reviewEndpoint(): SettingsOperation {
        val host = try { URI(normalizeBaseUrl(mutableState.value.editor.baseUrl)).host } catch (_: Exception) {
            return fail(SettingsError.InvalidEndpoint, SettingsOperation.InvalidEndpoint())
        }
        if (mutableState.value.editor.displayName.isBlank()) {
            return fail(SettingsError.InvalidEndpoint, SettingsOperation.InvalidEndpoint())
        }
        mutableState.value = mutableState.value.copy(
            setupStage = ProviderSetupStage.CREDENTIAL_VALIDATION,
            confirmedHost = host,
            error = null,
        )
        return SettingsOperation.Success
    }

    fun applyPreset(kind: PresetKind, localizedName: String) {
        invalidatePendingSetup()
        val preset = mutableState.value.presets.first { it.kind == kind }
        mutableState.value = mutableState.value.copy(editor = ProviderEditorUiState(
            type = preset.type,
            displayName = localizedName,
            baseUrl = preset.baseUrl.orEmpty(),
            modelId = preset.defaultModelId.orEmpty(),
            capabilities = preset.capabilities,
        ))
    }

    fun editProfile(id: String) {
        val profile = profile(id) ?: return
        invalidatePendingSetup()
        mutableState.value = mutableState.value.copy(editor = ProviderEditorUiState(
            id = profile.id,
            type = profile.type,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            modelId = profile.defaultModelId.orEmpty(),
            credentialAlias = profile.credentialAlias,
            capabilities = profile.capabilities,
        ))
    }

    suspend fun refresh(): SettingsOperation = try {
        val statuses = mutableState.value.profiles.associate { it.configuration.id to it.validationStatus }
        mutableState.value = mutableState.value.copy(
            profiles = profiles.list().map { ProviderProfileUiModel(it, statuses[it.id] ?: ValidationStatus.NotTested) },
            selectedProfileId = profiles.selectedId(),
            error = null,
        )
        SettingsOperation.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(SettingsError.StorageUnavailable)
    }

    suspend fun save(draft: ProviderDraft, secret: String): SettingsOperation {
        val profile = try {
            ProviderConfiguration(
                id = draft.id,
                type = draft.type,
                displayName = draft.displayName.trim(),
                baseUrl = normalizeBaseUrl(draft.baseUrl),
                credentialAlias = draft.credentialAlias,
                defaultModelId = draft.defaultModelId?.trim()?.takeIf(String::isNotEmpty),
                enabled = draft.enabled,
                capabilities = draft.capabilities,
            )
        } catch (_: IllegalArgumentException) {
            return fail(SettingsError.InvalidEndpoint, SettingsOperation.InvalidEndpoint())
        }
        if (profile.displayName.isBlank() || profile.credentialAlias.isBlank() || profile.defaultModelId == null) {
            return fail(SettingsError.InvalidEndpoint, SettingsOperation.InvalidEndpoint())
        }

        return when (val transaction = credentialTransactions.saveProfile(
            profile,
            secret,
            selectionRequested = false,
        )) {
            is CredentialTransactionResult.Success -> {
                replaceProfile(transaction.value)
                SettingsOperation.Success
            }
            CredentialTransactionResult.CredentialUnavailable -> fail(SettingsError.CredentialUnavailable)
            CredentialTransactionResult.StorageUnavailable -> fail(SettingsError.StorageUnavailable)
            CredentialTransactionResult.InconsistentStorage -> fail(SettingsError.InconsistentStorage)
        }
    }

    suspend fun validate(id: String, confirmedHost: String): SettingsOperation {
        val current = profile(id) ?: return fail(SettingsError.StorageUnavailable)
        val host = URI(current.baseUrl).host
        if (!host.equals(confirmedHost.trim(), ignoreCase = true)) {
            return SettingsOperation.HostConfirmationRequired(host)
        }
        setValidation(id, ValidationStatus.Testing)
        return when (try {
            providerFactory.create(current).validate(current)
        } catch (cancelled: CancellationException) {
            setValidation(id, ValidationStatus.NotTested)
            throw cancelled
        } catch (_: Exception) {
            setValidation(id, ValidationStatus.Invalid)
            return fail(SettingsError.ValidationFailed)
        }) {
            ValidationResult.Valid -> {
                setValidation(id, ValidationStatus.Valid)
                mutableState.value = mutableState.value.copy(error = null)
                SettingsOperation.Success
            }
            is ValidationResult.Invalid -> {
                setValidation(id, ValidationStatus.Invalid)
                fail(SettingsError.ValidationFailed)
            }
        }
    }

    suspend fun delete(id: String): SettingsOperation {
        val profile = profile(id) ?: return fail(SettingsError.StorageUnavailable)
        val oldCredential = credentials.read(profile.credentialAlias)
        val wasSelected = mutableState.value.selectedProfileId == id
        if (credentials.delete(profile.credentialAlias) !is CredentialResult.Success) {
            return fail(SettingsError.CredentialUnavailable)
        }
        return try {
            if (wasSelected) profiles.select(null)
            profiles.delete(id)
            val selected = mutableState.value.selectedProfileId.takeUnless { it == id }
            mutableState.value = mutableState.value.copy(
                profiles = mutableState.value.profiles.filterNot { it.configuration.id == id },
                selectedProfileId = selected,
                error = null,
            )
            SettingsOperation.Success
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val credentialRestored = restoreCredential(profile.credentialAlias, oldCredential)
                val profileRestored = runCatching {
                    profiles.upsert(profile)
                    if (wasSelected) profiles.select(id)
                }.isSuccess
                if (!credentialRestored || !profileRestored) mutableState.value = mutableState.value.copy(error = SettingsError.InconsistentStorage)
            }
            throw cancelled
        } catch (_: Exception) {
            val credentialRestored = oldCredential !is CredentialResult.Success ||
                credentials.put(profile.credentialAlias, oldCredential.value) is CredentialResult.Success
            val selectionRestored = try {
                profiles.upsert(profile)
                if (wasSelected) profiles.select(id)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            fail(if (credentialRestored && selectionRestored) SettingsError.StorageUnavailable else SettingsError.InconsistentStorage)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): SettingsOperation {
        val original = profile(id) ?: return fail(SettingsError.StorageUnavailable)
        val updated = original.copy(enabled = enabled)
        val wasSelected = mutableState.value.selectedProfileId == id
        return try {
            if (!enabled && wasSelected) profiles.select(null)
            profiles.upsert(updated)
            replaceProfile(updated)
            if (!enabled && wasSelected) mutableState.value = mutableState.value.copy(selectedProfileId = null)
            SettingsOperation.Success
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val restored = runCatching {
                    profiles.upsert(original)
                    if (wasSelected) profiles.select(id)
                }.isSuccess
                if (!restored) mutableState.value = mutableState.value.copy(error = SettingsError.InconsistentStorage)
            }
            throw cancelled
        } catch (_: Exception) {
            val restored = runCatching {
                profiles.upsert(original)
                if (wasSelected) profiles.select(id)
            }.isSuccess
            fail(if (restored) SettingsError.StorageUnavailable else SettingsError.InconsistentStorage)
        }
    }

    suspend fun select(id: String?): SettingsOperation {
        if (id != null && profile(id)?.enabled != true) return fail(SettingsError.StorageUnavailable)
        val previousId = try {
            profiles.selectedId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(SettingsError.StorageUnavailable)
        }
        return try {
            profiles.select(id)
            mutableState.value = mutableState.value.copy(selectedProfileId = id, error = null)
            SettingsOperation.Success
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!restoreSelection(previousId)) mutableState.value = mutableState.value.copy(error = SettingsError.InconsistentStorage)
            }
            throw cancelled
        } catch (_: Exception) {
            val restored = withContext(NonCancellable) { restoreSelection(previousId) }
            fail(if (restored) SettingsError.StorageUnavailable else SettingsError.InconsistentStorage)
        }
    }

    private fun profile(id: String) = mutableState.value.profiles.firstOrNull { it.configuration.id == id }?.configuration

    private fun replaceProfile(profile: ProviderConfiguration) {
        val existing = mutableState.value.profiles.firstOrNull { it.configuration.id == profile.id }
        mutableState.value = mutableState.value.copy(
            profiles = mutableState.value.profiles.filterNot { it.configuration.id == profile.id } +
                ProviderProfileUiModel(profile, existing?.validationStatus ?: ValidationStatus.NotTested),
            error = null,
        )
    }

    private fun setValidation(id: String, status: ValidationStatus) {
        mutableState.value = mutableState.value.copy(profiles = mutableState.value.profiles.map {
            if (it.configuration.id == id) it.copy(validationStatus = status) else it
        })
    }

    private fun normalizeBaseUrl(value: String): String {
        val endpoint = URI(value.trim())
        require(endpoint.scheme.equals("https", ignoreCase = true))
        require(!endpoint.host.isNullOrBlank() && endpoint.userInfo == null)
        return endpoint.toString().trimEnd('/')
    }

    private fun fail(error: SettingsError, operation: SettingsOperation = SettingsOperation.Failed(error)): SettingsOperation {
        mutableState.value = mutableState.value.copy(error = error)
        return operation
    }

    private fun transactionResult(result: CredentialTransactionResult<*>): SettingsOperation = when (result) {
        is CredentialTransactionResult.Success -> SettingsOperation.Success
        CredentialTransactionResult.CredentialUnavailable -> fail(SettingsError.CredentialUnavailable)
        CredentialTransactionResult.StorageUnavailable -> fail(SettingsError.StorageUnavailable)
        CredentialTransactionResult.InconsistentStorage -> fail(SettingsError.InconsistentStorage)
    }

    private fun invalidatePendingSetup() {
        val cleanupGeneration = ++setupGeneration
        val job = activeSetupJob
        activeSetupJob = null
        job?.cancel()
        val candidate = pendingValidation?.candidate
        pendingValidation = null
        resetSetupState()
        mutableState.value = mutableState.value.copy(setupInProgress = false)
        if (candidate != null) viewModelScope.launch {
            val result = withContext(NonCancellable) { credentialTransactions.abandon(candidate) }
            if (setupGeneration == cleanupGeneration) transactionResult(result)
        }
    }

    private fun launchSetupOperation(
        generation: Long = ++setupGeneration,
        block: suspend (Long) -> Unit,
    ): Job? {
        if (mutableState.value.operationInProgress || mutableState.value.setupInProgress) return null
        mutableState.value = mutableState.value.copy(setupInProgress = true)
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(generation)
            } finally {
                if (activeSetupJob === job && generation == setupGeneration) {
                    activeSetupJob = null
                    mutableState.value = mutableState.value.copy(setupInProgress = false)
                }
            }
        }
        activeSetupJob = job
        job.start()
        return job
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (!mutableState.value.controlsEnabled) return
        mutableState.value = mutableState.value.copy(operationInProgress = true)
        viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.value = mutableState.value.copy(operationInProgress = false)
            }
        }
    }

    private suspend fun restoreCredential(alias: String, previous: CredentialResult<String>): Boolean = when (previous) {
        is CredentialResult.Success -> credentials.put(alias, previous.value) is CredentialResult.Success
        is CredentialResult.Failure -> credentials.delete(alias) is CredentialResult.Success
    }

    private suspend fun restoreSelection(previousId: String?): Boolean = try {
        profiles.select(previousId)
        true
    } catch (cancelled: CancellationException) {
        false
    } catch (_: Exception) {
        false
    }
}

private val DEFAULT_PRESETS = listOf(
    ProviderPreset(PresetKind.OPENAI, ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4.1-mini", ModelCapabilities(streaming = true, vision = true, toolCalling = true, structuredOutput = true, systemMessages = true)),
    ProviderPreset(PresetKind.DEEPSEEK, ProviderType.DEEPSEEK_COMPATIBLE, "https://api.deepseek.com/v1", "deepseek-chat", ModelCapabilities(streaming = true, toolCalling = true, reasoning = true, systemMessages = true)),
    ProviderPreset(PresetKind.CUSTOM_HTTPS, ProviderType.OPENAI_COMPATIBLE, null, null, ModelCapabilities(streaming = true, systemMessages = true)),
)
