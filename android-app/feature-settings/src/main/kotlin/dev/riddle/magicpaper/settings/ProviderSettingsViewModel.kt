package dev.riddle.magicpaper.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riddle.magicpaper.model.*
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val error: SettingsError? = null,
) {
    val controlsEnabled: Boolean get() = !operationInProgress
}

enum class SettingsError { InvalidEndpoint, CredentialUnavailable, StorageUnavailable, InconsistentStorage, ValidationFailed }

sealed interface SettingsOperation {
    data object Success : SettingsOperation
    data class InvalidEndpoint(val reason: SettingsError = SettingsError.InvalidEndpoint) : SettingsOperation
    data class HostConfirmationRequired(val host: String) : SettingsOperation
    data class Failed(val reason: SettingsError) : SettingsOperation
}

class ProviderSettingsViewModel(
    private val profiles: ProviderProfileRepository,
    private val credentials: CredentialStore,
    private val providerFactory: ProviderFactory,
    private val credentialTransactions: CredentialTransactionCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSettingsUiState())
    val state: StateFlow<ProviderSettingsUiState> = mutableState.asStateFlow()

    fun refreshAsync() = launchOperation { refresh() }
    fun saveAsync(draft: ProviderDraft, secret: String) = launchOperation { save(draft, secret) }
    fun validateAsync(id: String, confirmedHost: String) = launchOperation { validate(id, confirmedHost) }
    fun deleteAsync(id: String) = launchOperation { delete(id) }
    fun setEnabledAsync(id: String, enabled: Boolean) = launchOperation { setEnabled(id, enabled) }
    fun selectAsync(id: String?) = launchOperation { select(id) }

    fun updateEditor(editor: ProviderEditorUiState) { mutableState.value = mutableState.value.copy(editor = editor) }

    fun applyPreset(kind: PresetKind, localizedName: String) {
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

    private fun launchOperation(block: suspend () -> Unit) {
        if (mutableState.value.operationInProgress) return
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
