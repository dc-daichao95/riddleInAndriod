package dev.riddle.magicpaper.settings

import dev.riddle.magicpaper.model.*
import dev.riddle.magicpaper.security.CredentialError
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.CredentialStore
import dev.riddle.magicpaper.security.CredentialTransactionJournal
import dev.riddle.magicpaper.security.CredentialTransactionJournalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsViewModelTest {
    @Test fun `new profile throw after durable commit preserves candidate for restart recovery`() = runTest {
        val durable = FakeProfiles()
        val profiles = AmbiguousProfiles(durable, upsertFailure = FailureMode.Throw)
        val credentials = FakeCredentials()
        val vm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid))

        val result = vm.save(draft(), "new-secret")

        assertEquals(SettingsOperation.Failed(SettingsError.StorageUnavailable), result)
        val committed = durable.items.single()
        assertTrue(committed.credentialAlias.startsWith("candidate-profile-1-"))
        assertEquals("new-secret", credentials.values[committed.credentialAlias])
    }

    @Test fun `edited profile throw after durable commit immediately finishes recovery`() = runTest {
        val durable = FakeProfiles()
        val credentials = FakeCredentials()
        val vm = testViewModel(durable, credentials, RecordingFactory(ValidationResult.Valid))
        vm.save(draft(), "old-secret")
        val priorAlias = durable.items.single().credentialAlias
        val profiles = AmbiguousProfiles(durable, upsertFailure = FailureMode.Throw)
        val failingVm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid)).also { it.refresh() }

        val result = failingVm.save(draft().copy(baseUrl = "https://other.example/v1"), "new-secret")

        assertEquals(SettingsOperation.Failed(SettingsError.StorageUnavailable), result)
        val committed = durable.items.single()
        assertEquals("https://other.example/v1", committed.baseUrl)
        assertEquals("new-secret", credentials.values[committed.credentialAlias])
        assertFalse(credentials.values.containsKey(priorAlias))
    }

    @Test fun `save cancellation after durable commit finishes recovery before propagating`() = runTest {
        val durable = FakeProfiles()
        val credentials = FakeCredentials()
        val vm = testViewModel(durable, credentials, RecordingFactory(ValidationResult.Valid))
        vm.save(draft(), "old-secret")
        val priorAlias = durable.items.single().credentialAlias
        val failingVm = testViewModel(
            AmbiguousProfiles(durable, upsertFailure = FailureMode.Cancel), credentials, RecordingFactory(ValidationResult.Valid),
        ).also { it.refresh() }

        assertFailsWith<CancellationException> {
            failingVm.save(draft().copy(baseUrl = "https://other.example/v1"), "new-secret")
        }
        val committed = durable.items.single()
        assertEquals("https://other.example/v1", committed.baseUrl)
        assertEquals("new-secret", credentials.values[committed.credentialAlias])
        assertFalse(credentials.values.containsKey(priorAlias))
    }

    @Test fun `ambiguous profile commit reports storage failure without leaking secret`() = runTest {
        val durable = FakeProfiles()
        val credentials = FakeCredentials()
        val profiles = AmbiguousProfiles(durable, upsertFailure = FailureMode.Throw, compensationFails = true)
        val vm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid))

        val result = vm.save(draft(), "never-observable")

        assertEquals(SettingsOperation.Failed(SettingsError.StorageUnavailable), result)
        assertEquals(SettingsError.StorageUnavailable, vm.state.value.error)
        assertFalse(vm.state.value.toString().contains("never-observable"))
    }

    @Test fun `selection throw after commit restores previous durable selection`() = runTest {
        val durable = FakeProfiles().apply { selected = "previous" }
        val vm = viewModel(durable)
        vm.save(draft(), "secret")
        vm.refresh()
        val failing = testViewModel(
            AmbiguousProfiles(durable, selectFailure = FailureMode.Throw), FakeCredentials(), RecordingFactory(ValidationResult.Valid),
        ).also { it.refresh() }

        assertEquals(SettingsOperation.Failed(SettingsError.StorageUnavailable), failing.select("profile-1"))
        assertEquals("previous", durable.selected)
    }

    @Test fun `selection cancellation after commit restores previous durable selection`() = runTest {
        val durable = FakeProfiles().apply { selected = "previous" }
        durable.upsert(draft().toConfiguration())
        val failing = testViewModel(
            AmbiguousProfiles(durable, selectFailure = FailureMode.Cancel), FakeCredentials(), RecordingFactory(ValidationResult.Valid),
        ).also { it.refresh() }

        assertFailsWith<CancellationException> { failing.select("profile-1") }
        assertEquals("previous", durable.selected)
    }

    @Test fun `operation state exposes disabled controls while busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val profiles = object : ProviderProfileRepository by FakeProfiles() {
            override suspend fun list(): List<ProviderConfiguration> { gate.await(); return emptyList() }
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = testViewModel(profiles, FakeCredentials(), RecordingFactory(ValidationResult.Valid))
            vm.refreshAsync()
            assertFalse(vm.state.value.controlsEnabled)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.state.value.controlsEnabled)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `repository cancellation is preserved`() = runTest {
        val profiles = object : ProviderProfileRepository {
            override suspend fun list(): List<ProviderConfiguration> = throw CancellationException("cancelled")
            override suspend fun upsert(profile: ProviderConfiguration) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun select(id: String?) = Unit
            override suspend fun selectedId(): String? = null
        }
        val vm = testViewModel(profiles, FakeCredentials(), RecordingFactory(ValidationResult.Valid))

        assertFailsWith<CancellationException> { vm.refresh() }
    }

    @Test fun `save cancellation restores previous credential`() = runTest {
        val credentials = FakeCredentials().apply { values["credential-profile-1"] = "old" }
        val profiles = object : ProviderProfileRepository by FakeProfiles() {
            override suspend fun upsert(profile: ProviderConfiguration) = throw CancellationException("cancelled")
        }
        val vm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid))

        assertFailsWith<CancellationException> { vm.save(draft(), "new") }
        assertEquals(mapOf("credential-profile-1" to "old"), credentials.values)
    }

    @Test fun `delete cancellation restores profile credential and selection`() = runTest {
        val credentials = FakeCredentials()
        val delegate = FakeProfiles()
        val profiles = object : ProviderProfileRepository by delegate {
            override suspend fun delete(id: String) = throw CancellationException("cancelled")
        }
        val vm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid))
        vm.save(draft(), "secret")
        val credentialAlias = delegate.items.single().credentialAlias
        vm.select("profile-1")

        assertFailsWith<CancellationException> { vm.delete("profile-1") }
        assertEquals("secret", credentials.values[credentialAlias])
        assertEquals("profile-1", delegate.selected)
        assertEquals(1, delegate.items.size)
    }

    @Test fun `disable failure restores enabled selected profile`() = runTest {
        val credentials = FakeCredentials()
        val delegate = FakeProfiles()
        val profiles = object : ProviderProfileRepository by delegate {
            override suspend fun select(id: String?) {
                if (id == null) error("selection unavailable") else delegate.select(id)
            }
        }
        val vm = testViewModel(profiles, credentials, RecordingFactory(ValidationResult.Valid))
        vm.save(draft(), "secret")
        vm.select("profile-1")

        assertIs<SettingsOperation.Failed>(vm.setEnabled("profile-1", false))
        assertTrue(vm.state.value.profiles.single().configuration.enabled)
        assertEquals("profile-1", vm.state.value.selectedProfileId)
    }

    @Test fun `async operation rejects concurrent mutation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var selectCalls = 0
        val profiles = object : ProviderProfileRepository {
            override suspend fun list(): List<ProviderConfiguration> { gate.await(); return emptyList() }
            override suspend fun upsert(profile: ProviderConfiguration) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun select(id: String?) { selectCalls++ }
            override suspend fun selectedId(): String? = null
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = testViewModel(profiles, FakeCredentials(), RecordingFactory(ValidationResult.Valid))
            vm.refreshAsync()
            vm.selectAsync(null)
            runCurrent()
            assertTrue(vm.state.value.operationInProgress)
            assertEquals(0, selectCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.state.value.operationInProgress)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `saving keeps raw secret out of observable state and profile`() = runTest {
        val credentials = FakeCredentials()
        val profiles = FakeProfiles()
        val vm = viewModel(profiles, credentials)

        vm.save(draft(), "sk-test-secret")

        assertEquals("sk-test-secret", credentials.values[profiles.items.single().credentialAlias])
        assertFalse(vm.state.value.toString().contains("sk-test-secret"))
        assertFalse(profiles.items.single().toString().contains("sk-test-secret"))
    }

    @Test fun `editing metadata without replacement secret preserves credential`() = runTest {
        val credentials = FakeCredentials()
        val vm = viewModel(credentials = credentials)
        vm.save(draft(), "original-secret")
        val credentialAlias = vm.state.value.profiles.single().configuration.credentialAlias

        vm.save(draft().copy(displayName = "Renamed"), "")

        assertEquals("original-secret", credentials.values[credentialAlias])
        assertEquals("Renamed", vm.state.value.profiles.single().configuration.displayName)
    }

    @Test fun `validation uses minimal provider request without page image`() = runTest {
        val factory = RecordingFactory(ValidationResult.Valid)
        val vm = viewModel(factory = factory)
        vm.save(draft(), "secret")

        vm.validate("profile-1", confirmedHost = "api.openai.com")

        assertEquals("api.openai.com", java.net.URI(factory.created.single().baseUrl).host)
        assertEquals(factory.created.single(), factory.validationInputs.single())
        assertEquals(ValidationStatus.Valid, vm.state.value.profiles.single().validationStatus)
    }

    @Test fun `cleartext URL is rejected before credentials or transport`() = runTest {
        val credentials = FakeCredentials()
        val factory = RecordingFactory(ValidationResult.Valid)
        val vm = viewModel(credentials = credentials, factory = factory)

        val result = vm.save(draft(baseUrl = "http://api.example.com/v1"), "secret")

        assertIs<SettingsOperation.InvalidEndpoint>(result)
        assertTrue(credentials.values.isEmpty())
        assertTrue(factory.created.isEmpty())
    }

    @Test fun `deleting removes profile and its credential alias`() = runTest {
        val credentials = FakeCredentials()
        val profiles = FakeProfiles()
        val vm = viewModel(profiles, credentials)
        vm.save(draft(), "secret")
        vm.select("profile-1")

        assertEquals(SettingsOperation.Success, vm.delete("profile-1"))
        assertTrue(profiles.items.isEmpty())
        assertNull(profiles.selected)
        assertFalse(credentials.values.containsKey("credential-profile-1"))
    }

    @Test fun `validation requires confirmation of actual destination host`() = runTest {
        val factory = RecordingFactory(ValidationResult.Valid)
        val vm = viewModel(factory = factory)
        vm.save(draft(), "secret")

        val result = vm.validate("profile-1", confirmedHost = "lookalike.example")

        assertIs<SettingsOperation.HostConfirmationRequired>(result)
        assertEquals("api.openai.com", result.host)
        assertTrue(factory.created.isEmpty())
    }

    @Test fun `presets select enable and edit provider-neutral profiles`() = runTest {
        val vm = viewModel()
        assertEquals(PresetKind.entries.toSet(), vm.state.value.presets.map { it.kind }.toSet())
        vm.save(draft(enabled = false), "secret")

        vm.setEnabled("profile-1", true)
        vm.select("profile-1")

        assertTrue(vm.state.value.profiles.single().configuration.enabled)
        assertEquals("profile-1", vm.state.value.selectedProfileId)
    }

    private fun viewModel(
        profiles: FakeProfiles = FakeProfiles(), credentials: FakeCredentials = FakeCredentials(),
        factory: RecordingFactory = RecordingFactory(ValidationResult.Valid),
    ) = testViewModel(profiles, credentials, factory)

    private fun draft(baseUrl: String = "https://api.openai.com/v1", enabled: Boolean = true) = ProviderDraft(
        id = "profile-1", type = ProviderType.OPENAI_COMPATIBLE, displayName = "My OpenAI",
        baseUrl = baseUrl, credentialAlias = "credential-profile-1", defaultModelId = "gpt-test",
        enabled = enabled, capabilities = ModelCapabilities(streaming = true, vision = true),
    )

    private fun ProviderDraft.toConfiguration() = ProviderConfiguration(
        id, type, displayName, baseUrl, credentialAlias, defaultModelId, enabled, capabilities,
    )
}

private class FakeCredentials : CredentialStore {
    val values = mutableMapOf<String, String>()
    override suspend fun put(alias: String, secret: String): CredentialResult<Unit> { values[alias] = secret; return CredentialResult.Success(Unit) }
    override suspend fun read(alias: String): CredentialResult<String> = values[alias]?.let { CredentialResult.Success(it) } ?: CredentialResult.Failure(CredentialError.Unavailable)
    override suspend fun delete(alias: String): CredentialResult<Unit> { values.remove(alias); return CredentialResult.Success(Unit) }
}

private fun testViewModel(
    profiles: ProviderProfileRepository,
    credentials: CredentialStore,
    factory: ProviderFactory,
) = ProviderSettingsViewModel(
    profiles,
    credentials,
    factory,
    CredentialTransactionCoordinator(profiles, credentials, TestCredentialJournal()),
)

private class TestCredentialJournal : CredentialTransactionJournalStore {
    private var value: CredentialTransactionJournal? = null
    override fun read(): CredentialResult<CredentialTransactionJournal?> = CredentialResult.Success(value)
    override fun write(journal: CredentialTransactionJournal): CredentialResult<Unit> {
        value = journal
        return CredentialResult.Success(Unit)
    }
    override fun clear(): CredentialResult<Unit> {
        value = null
        return CredentialResult.Success(Unit)
    }
}

private class FakeProfiles : ProviderProfileRepository {
    val items = mutableListOf<ProviderConfiguration>()
    var selected: String? = null
    override suspend fun list() = items.toList()
    override suspend fun upsert(profile: ProviderConfiguration) { items.removeAll { it.id == profile.id }; items += profile }
    override suspend fun delete(id: String) { items.removeAll { it.id == id }; if (selected == id) selected = null }
    override suspend fun select(id: String?) { selected = id }
    override suspend fun selectedId() = selected
}

private enum class FailureMode { None, Throw, Cancel }

private class AmbiguousProfiles(
    private val delegate: FakeProfiles,
    private var upsertFailure: FailureMode = FailureMode.None,
    private var selectFailure: FailureMode = FailureMode.None,
    private val compensationFails: Boolean = false,
) : ProviderProfileRepository by delegate {
    private var mutationFailed = false

    override suspend fun upsert(profile: ProviderConfiguration) {
        delegate.upsert(profile)
        val mode = upsertFailure
        upsertFailure = FailureMode.None
        if (mode != FailureMode.None) {
            mutationFailed = true
            fail(mode)
        }
    }

    override suspend fun delete(id: String) {
        if (compensationFails && mutationFailed) error("compensation failed")
        delegate.delete(id)
    }

    override suspend fun select(id: String?) {
        delegate.select(id)
        val mode = selectFailure
        selectFailure = FailureMode.None
        fail(mode)
    }

    private fun fail(mode: FailureMode) = when (mode) {
        FailureMode.None -> Unit
        FailureMode.Throw -> error("committed then failed")
        FailureMode.Cancel -> throw CancellationException("committed then cancelled")
    }
}

private class RecordingFactory(private val validation: ValidationResult) : ProviderFactory {
    val created = mutableListOf<ProviderConfiguration>()
    val validationInputs = mutableListOf<ProviderConfiguration>()
    override fun create(configuration: ProviderConfiguration): ModelProvider {
        created += configuration
        return object : ModelProvider {
            override val descriptor = ProviderDescriptor(configuration.type, configuration.displayName, configuration.capabilities)
            override fun stream(request: ModelRequest): Flow<ModelEvent> = emptyFlow()
            override suspend fun listModels() = ModelDiscoveryResult.Success(emptyList<ModelDescriptor>())
            override suspend fun validate(configuration: ProviderConfiguration): ValidationResult {
                validationInputs += configuration
                return validation
            }
        }
    }
}
