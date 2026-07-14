package dev.riddle.magicpaper.settings

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.security.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialTransactionCoordinatorTest {
    @Test fun `startup recovery and begin are serialized so new journal survives`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var listCalls = 0
        val profiles = object : ProviderProfileRepository {
            override suspend fun list(): List<ProviderConfiguration> {
                if (++listCalls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return emptyList()
            }
            override suspend fun upsert(profile: ProviderConfiguration) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun select(id: String?) = Unit
            override suspend fun selectedId(): String? = null
        }
        val events = mutableListOf<String>()
        val credentials = TransactionCredentials(events)
        val journal = TransactionJournal(events).apply {
            value = CredentialTransactionJournal(
                "old", "candidate-old", null, false, CredentialTransactionPhase.INTENT,
            )
        }
        val coordinator = CredentialTransactionCoordinator(
            profiles, credentials, journal,
            dispatcher = StandardTestDispatcher(testScheduler), nonce = { "fixed" },
        )

        val startup = async { coordinator.recover() }
        entered.await()
        val begin = async { coordinator.begin("new", null, false, SECRET) }
        runCurrent()
        release.complete(Unit)
        startup.await()
        assertIs<CredentialTransactionResult.Success<CandidateCredential>>(begin.await())

        assertEquals("new", journal.value?.profileId)
        assertEquals(CredentialTransactionPhase.CANDIDATE_WRITTEN, journal.value?.phase)
    }

    @Test fun `ambiguous candidate write failure is recovered without orphaning secret`() = runTest {
        val events = mutableListOf<String>()
        val profiles = TransactionProfiles(events)
        val credentials = object : CredentialStore {
            val values = mutableMapOf<String, String>()
            override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
                values[alias] = secret
                return CredentialResult.Failure(CredentialError.Unavailable)
            }
            override suspend fun read(alias: String) = values[alias]?.let { CredentialResult.Success(it) }
                ?: CredentialResult.Failure(CredentialError.Unavailable)
            override suspend fun delete(alias: String): CredentialResult<Unit> {
                values.remove(alias)
                return CredentialResult.Success(Unit)
            }
        }
        val journal = TransactionJournal(events)
        val coordinator = CredentialTransactionCoordinator(
            profiles, credentials, journal,
            dispatcher = StandardTestDispatcher(testScheduler), nonce = { "fixed" },
        )

        assertEquals(
            CredentialTransactionResult.CredentialUnavailable,
            coordinator.begin("profile-1", null, false, SECRET),
        )
        assertTrue(credentials.values.isEmpty())
        assertNull(journal.value)
    }

    @Test fun `concurrent begin calls cannot overwrite an in-flight journal`() = runTest {
        val firstPutEntered = CompletableDeferred<Unit>()
        val releaseFirstPut = CompletableDeferred<Unit>()
        var puts = 0
        val events = mutableListOf<String>()
        val profiles = TransactionProfiles(events)
        val credentials = object : CredentialStore {
            val values = mutableMapOf<String, String>()
            override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
                puts++
                if (puts == 1) {
                    firstPutEntered.complete(Unit)
                    releaseFirstPut.await()
                }
                values[alias] = secret
                return CredentialResult.Success(Unit)
            }
            override suspend fun read(alias: String) = values[alias]?.let { CredentialResult.Success(it) }
                ?: CredentialResult.Failure(CredentialError.Unavailable)
            override suspend fun delete(alias: String): CredentialResult<Unit> {
                values.remove(alias)
                return CredentialResult.Success(Unit)
            }
        }
        val journal = TransactionJournal(events)
        var nonce = 0
        val coordinator = CredentialTransactionCoordinator(
            profiles, credentials, journal,
            dispatcher = StandardTestDispatcher(testScheduler), nonce = { (++nonce).toString() },
        )

        val first = async { coordinator.begin("first", null, false, "first-secret") }
        firstPutEntered.await()
        val second = async { coordinator.begin("second", null, false, "second-secret") }
        runCurrent()
        assertEquals(1, puts)
        releaseFirstPut.complete(Unit)
        assertIs<CredentialTransactionResult.Success<CandidateCredential>>(first.await())
        assertIs<CredentialTransactionResult.Success<CandidateCredential>>(second.await())

        assertEquals("second", journal.value?.profileId)
        assertEquals(setOf("candidate-second-2"), credentials.values.keys)
    }

    @Test fun `cleanup clear failure keeps recoverable intent until next recovery`() = runTest {
        val events = mutableListOf<String>()
        val profiles = TransactionProfiles(events)
        val credentials = object : CredentialStore {
            val values = mutableMapOf<String, String>()
            override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
                values[alias] = secret
                return CredentialResult.Failure(CredentialError.Unavailable)
            }
            override suspend fun read(alias: String) = values[alias]?.let { CredentialResult.Success(it) }
                ?: CredentialResult.Failure(CredentialError.Unavailable)
            override suspend fun delete(alias: String): CredentialResult<Unit> {
                values.remove(alias)
                return CredentialResult.Success(Unit)
            }
        }
        val journal = TransactionJournal(events).apply { clearFailures = 1 }
        val coordinator = CredentialTransactionCoordinator(
            profiles, credentials, journal,
            dispatcher = StandardTestDispatcher(testScheduler), nonce = { "fixed" },
        )

        assertEquals(
            CredentialTransactionResult.StorageUnavailable,
            coordinator.begin("profile-1", null, false, SECRET),
        )
        assertNotNull(journal.value)
        assertTrue(credentials.values.isEmpty())

        assertIs<CredentialTransactionResult.Success<Unit>>(coordinator.recover())
        assertNull(journal.value)
    }

    @Test fun `candidate write cancellation converges transaction before propagating`() = runTest {
        val events = mutableListOf<String>()
        val profiles = TransactionProfiles(events)
        val credentials = object : CredentialStore {
            val values = mutableMapOf<String, String>()
            override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
                values[alias] = secret
                throw CancellationException("after write")
            }
            override suspend fun read(alias: String) = values[alias]?.let { CredentialResult.Success(it) }
                ?: CredentialResult.Failure(CredentialError.Unavailable)
            override suspend fun delete(alias: String): CredentialResult<Unit> {
                values.remove(alias)
                return CredentialResult.Success(Unit)
            }
        }
        val journal = TransactionJournal(events)
        val coordinator = CredentialTransactionCoordinator(
            profiles, credentials, journal,
            dispatcher = StandardTestDispatcher(testScheduler), nonce = { "fixed" },
        )

        assertFailsWith<CancellationException> {
            coordinator.begin("profile-1", null, false, SECRET)
        }
        assertTrue(credentials.values.isEmpty())
        assertNull(journal.value)
    }

    @Test fun `persistence transaction dispatches away from caller context`() = runTest {
        val events = mutableListOf<String>()
        val worker = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val coordinator = CredentialTransactionCoordinator(
            TransactionProfiles(events),
            TransactionCredentials(events),
            TransactionJournal(events),
            dispatcher = worker,
            nonce = { "fixed" },
        )

        assertIs<CredentialTransactionResult.Success<CandidateCredential>>(
            coordinator.begin("profile-1", null, false, SECRET),
        )

        assertTrue(worker.dispatchCount > 0)
    }

    @Test fun `settings save performs profile lookup and commit on worker dispatcher`() = runTest {
        val events = mutableListOf<String>()
        val worker = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val delegate = TransactionProfiles(events)
        val profiles = object : ProviderProfileRepository by delegate {
            override suspend fun list(): List<ProviderConfiguration> {
                assertTrue(worker.isWorkerThread())
                return delegate.list()
            }
        }
        val credentials = TransactionCredentials(events)
        val coordinator = CredentialTransactionCoordinator(
            profiles,
            credentials,
            TransactionJournal(events),
            dispatcher = worker,
            nonce = { "fixed" },
        )
        val vm = ProviderSettingsViewModel(profiles, credentials, RecordingProviderFactory(), coordinator)

        assertEquals(SettingsOperation.Success, vm.save(draftForViewModel(), SECRET))
    }
    @Test fun `begin persists secret-free intent before candidate and advances journal`() = runTest {
        val fixture = Fixture()

        val result = fixture.coordinator.begin("profile-1", "prior", true, SECRET)

        val candidate = assertIs<CredentialTransactionResult.Success<CandidateCredential>>(result).value
        assertEquals("candidate-profile-1-fixed", candidate.candidateAlias)
        assertEquals(listOf("journal:INTENT", "credential:put", "journal:CANDIDATE_WRITTEN"), fixture.events)
        assertFalse(fixture.journal.value.toString().contains(SECRET))
    }

    @Test fun `new profile commit makes candidate authoritative selects and clears journal`() = runTest {
        val fixture = Fixture()
        val candidate = fixture.begin()

        assertIs<CredentialTransactionResult.Success<Unit>>(fixture.coordinator.commit(candidate, profile(candidate.candidateAlias)))

        assertEquals(candidate.candidateAlias, fixture.profiles.items.single().credentialAlias)
        assertEquals("profile-1", fixture.profiles.selected)
        assertEquals(setOf(candidate.candidateAlias), fixture.credentials.values.keys)
        assertNull(fixture.journal.value)
    }

    @Test fun `edited secret deletes prior alias only after profile references candidate`() = runTest {
        val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")
        val candidate = fixture.begin(priorAlias = "prior")

        fixture.coordinator.commit(candidate, profile(candidate.candidateAlias))

        assertEquals(listOf(
            "journal:INTENT", "credential:put", "journal:CANDIDATE_WRITTEN", "profile:upsert",
            "journal:PROFILE_COMMITTED", "profile:select", "credential:delete:prior", "journal:clear",
        ), fixture.events)
        assertFalse(fixture.credentials.values.containsKey("prior"))
    }

    @Test fun `metadata-only edit creates no candidate and retains referenced alias`() = runTest {
        val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")

        assertIs<CredentialTransactionResult.Success<Unit>>(
            fixture.coordinator.commitMetadata(profile("prior").copy(displayName = "Renamed"), selectionRequested = false),
        )

        assertEquals("old", fixture.credentials.values["prior"])
        assertNull(fixture.journal.value)
        assertEquals("Renamed", fixture.profiles.items.single().displayName)
    }

    @Test fun `abandon and cancellation delete unreferenced candidate and preserve prior`() = runTest {
        val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")
        val candidate = fixture.begin(priorAlias = "prior")

        assertIs<CredentialTransactionResult.Success<Unit>>(fixture.coordinator.abandon(candidate))
        assertEquals(mapOf("prior" to "old"), fixture.credentials.values)
        assertNull(fixture.journal.value)
        assertIs<CredentialTransactionResult.Success<Unit>>(fixture.coordinator.abandon(candidate))
    }

    @Test fun `recovery is idempotent at every durable boundary`() = runTest {
        val cases = listOf(
            RecoveryCase("before candidate", CredentialTransactionPhase.INTENT, candidateExists = false, committed = false),
            RecoveryCase("after candidate", CredentialTransactionPhase.INTENT, candidateExists = true, committed = false),
            RecoveryCase("after journal advance", CredentialTransactionPhase.CANDIDATE_WRITTEN, candidateExists = true, committed = false),
            RecoveryCase("after discovery", CredentialTransactionPhase.CANDIDATE_WRITTEN, candidateExists = true, committed = false),
            RecoveryCase("after profile commit", CredentialTransactionPhase.CANDIDATE_WRITTEN, candidateExists = true, committed = true),
            RecoveryCase("after phase commit", CredentialTransactionPhase.PROFILE_COMMITTED, candidateExists = true, committed = true),
            RecoveryCase("after selection", CredentialTransactionPhase.PROFILE_COMMITTED, candidateExists = true, committed = true),
            RecoveryCase("after prior delete", CredentialTransactionPhase.PROFILE_COMMITTED, candidateExists = true, committed = true, priorExists = false),
        )
        for (case in cases) {
            val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")
            val candidateAlias = "candidate-profile-1-fixed"
            if (case.candidateExists) fixture.withCredential(candidateAlias, SECRET)
            if (case.committed) fixture.withProfile(profile(candidateAlias))
            if (!case.priorExists) fixture.credentials.values.remove("prior")
            fixture.journal.value = CredentialTransactionJournal("profile-1", candidateAlias, "prior", true, case.phase)

            val first = fixture.coordinator.recover()
            val second = fixture.coordinator.recover()

            assertIs<CredentialTransactionResult.Success<Unit>>(first, case.name)
            assertIs<CredentialTransactionResult.Success<Unit>>(second, case.name)
            assertNull(fixture.journal.value, case.name)
            if (case.committed) {
                assertEquals("profile-1", fixture.profiles.selected, case.name)
                assertTrue(fixture.credentials.values.containsKey(candidateAlias), case.name)
                assertFalse(fixture.credentials.values.containsKey("prior"), case.name)
            } else {
                assertTrue(fixture.credentials.values.containsKey("prior"), case.name)
                assertFalse(fixture.credentials.values.containsKey(candidateAlias), case.name)
            }
        }
    }

    @Test fun `recovery never deletes an alias referenced by another profile`() = runTest {
        val fixture = Fixture()
            .withProfile(profile("candidate-profile-1-fixed"))
            .withProfile(profile("prior").copy(id = "profile-2"))
            .withCredential("candidate-profile-1-fixed", SECRET)
            .withCredential("prior", "old")
        fixture.journal.value = CredentialTransactionJournal(
            "profile-1", "candidate-profile-1-fixed", "prior", true, CredentialTransactionPhase.PROFILE_COMMITTED,
        )

        assertIs<CredentialTransactionResult.Success<Unit>>(fixture.coordinator.recover())

        assertTrue(fixture.credentials.values.containsKey("prior"))
        assertTrue(fixture.credentials.values.containsKey("candidate-profile-1-fixed"))
    }

    @Test fun `cleanup rechecks references immediately before deleting prior alias`() = runTest {
        val events = mutableListOf<String>()
        val durable = TransactionProfiles(events).apply {
            upsertDirect(profile("candidate-profile-1-fixed"))
        }
        var listCalls = 0
        val racingProfiles = object : ProviderProfileRepository by durable {
            override suspend fun list(): List<ProviderConfiguration> {
                val snapshot = durable.list()
                if (++listCalls == 1) {
                    durable.upsertDirect(profile("prior").copy(id = "profile-2"))
                }
                return snapshot
            }
        }
        val credentials = TransactionCredentials(events).apply {
            values["candidate-profile-1-fixed"] = SECRET
            values["prior"] = "old"
        }
        val journal = TransactionJournal(events).apply {
            value = CredentialTransactionJournal(
                "profile-1", "candidate-profile-1-fixed", "prior", true,
                CredentialTransactionPhase.PROFILE_COMMITTED,
            )
        }
        val coordinator = CredentialTransactionCoordinator(racingProfiles, credentials, journal) { "fixed" }

        assertIs<CredentialTransactionResult.Success<Unit>>(coordinator.recover())

        assertTrue(credentials.values.containsKey("prior"))
        assertNull(journal.value)
    }

    @Test fun `candidate read failure after durable candidate phase is inconsistent and preserves prior`() = runTest {
        val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")
        fixture.journal.value = CredentialTransactionJournal(
            "profile-1", "candidate-profile-1-fixed", "prior", true, CredentialTransactionPhase.CANDIDATE_WRITTEN,
        )
        fixture.credentials.readFailure = CredentialError.Corrupt

        assertEquals(CredentialTransactionResult.InconsistentStorage, fixture.coordinator.recover())
        assertTrue(fixture.credentials.values.containsKey("prior"))
        assertNotNull(fixture.journal.value)
    }

    @Test fun `sentinel never enters profile journal result or observable settings state`() = runTest {
        val fixture = Fixture()
        val candidate = fixture.begin(secret = SECRET)
        fixture.coordinator.commit(candidate, profile(candidate.candidateAlias))
        val vm = ProviderSettingsViewModel(
            fixture.profiles,
            fixture.credentials,
            RecordingProviderFactory(),
            fixture.coordinator,
        )
        vm.refresh()

        val exposed = listOf(fixture.profiles.items, fixture.journal.value, candidate, vm.state.value).joinToString()
        assertFalse(exposed.contains(SECRET))
    }

    @Test fun `settings save commits replacement through candidate alias`() = runTest {
        val fixture = Fixture()
        val vm = ProviderSettingsViewModel(
            fixture.profiles,
            fixture.credentials,
            RecordingProviderFactory(),
            fixture.coordinator,
        )

        assertEquals(SettingsOperation.Success, vm.save(draftForViewModel(), SECRET))

        val saved = fixture.profiles.items.single()
        assertEquals("candidate-profile-1-fixed", saved.credentialAlias)
        assertFalse(saved.toString().contains(SECRET))
        assertFalse(vm.state.value.toString().contains(SECRET))
        assertNull(fixture.journal.value)
    }

    @Test fun `settings metadata save retains durable prior alias without journal`() = runTest {
        val fixture = Fixture().withProfile(profile("prior")).withCredential("prior", "old")
        val vm = ProviderSettingsViewModel(
            fixture.profiles,
            fixture.credentials,
            RecordingProviderFactory(),
            fixture.coordinator,
        ).also { it.refresh() }

        assertEquals(SettingsOperation.Success, vm.save(draftForViewModel().copy(displayName = "Renamed"), ""))

        assertEquals("prior", fixture.profiles.items.single().credentialAlias)
        assertEquals("old", fixture.credentials.values["prior"])
        assertNull(fixture.journal.value)
    }

    private data class RecoveryCase(
        val name: String,
        val phase: CredentialTransactionPhase,
        val candidateExists: Boolean,
        val committed: Boolean,
        val priorExists: Boolean = true,
    )

    private class Fixture {
        val events = mutableListOf<String>()
        val profiles = TransactionProfiles(events)
        val credentials = TransactionCredentials(events)
        val journal = TransactionJournal(events)
        val coordinator = CredentialTransactionCoordinator(profiles, credentials, journal) { "fixed" }
        fun withProfile(profile: ProviderConfiguration) = apply { profiles.upsertDirect(profile) }
        fun withCredential(alias: String, secret: String) = apply { credentials.values[alias] = secret }
        suspend fun begin(priorAlias: String? = null, secret: String = SECRET) =
            assertIs<CredentialTransactionResult.Success<CandidateCredential>>(
                coordinator.begin("profile-1", priorAlias, true, secret),
            ).value
    }

    private companion object {
        const val SECRET = "sentinel-api-key"
        fun profile(alias: String) = ProviderConfiguration(
            id = "profile-1", type = ProviderType.OPENAI_COMPATIBLE, displayName = "Provider",
            baseUrl = "https://example.test/v1", credentialAlias = alias, defaultModelId = "model",
            enabled = true, capabilities = ModelCapabilities(streaming = true),
        )
        fun draftForViewModel() = ProviderDraft(
            id = "profile-1", type = ProviderType.OPENAI_COMPATIBLE, displayName = "Provider",
            baseUrl = "https://example.test/v1", credentialAlias = "legacy-draft-alias", defaultModelId = "model",
            enabled = true, capabilities = ModelCapabilities(streaming = true),
        )
    }
}

private class TransactionProfiles(private val events: MutableList<String>) : ProviderProfileRepository {
    val items = mutableListOf<ProviderConfiguration>()
    var selected: String? = null
    fun upsertDirect(profile: ProviderConfiguration) { items.removeAll { it.id == profile.id }; items += profile }
    override suspend fun list() = items.toList()
    override suspend fun upsert(profile: ProviderConfiguration) { events += "profile:upsert"; upsertDirect(profile) }
    override suspend fun delete(id: String) { events += "profile:delete"; items.removeAll { it.id == id } }
    override suspend fun select(id: String?) { events += "profile:select"; selected = id }
    override suspend fun selectedId() = selected
}

private class TransactionCredentials(private val events: MutableList<String>) : CredentialStore {
    val values = mutableMapOf<String, String>()
    var readFailure: CredentialError? = null
    override suspend fun put(alias: String, secret: String): CredentialResult<Unit> {
        events += "credential:put"; values[alias] = secret; return CredentialResult.Success(Unit)
    }
    override suspend fun read(alias: String): CredentialResult<String> = readFailure?.let { CredentialResult.Failure(it) }
        ?: values[alias]?.let { CredentialResult.Success(it) } ?: CredentialResult.Failure(CredentialError.Unavailable)
    override suspend fun delete(alias: String): CredentialResult<Unit> {
        events += "credential:delete:$alias"; values.remove(alias); return CredentialResult.Success(Unit)
    }
}

private class TransactionJournal(private val events: MutableList<String>) : CredentialTransactionJournalStore {
    var value: CredentialTransactionJournal? = null
    var clearFailures = 0
    override fun read(): CredentialResult<CredentialTransactionJournal?> = CredentialResult.Success(value)
    override fun write(journal: CredentialTransactionJournal): CredentialResult<Unit> {
        events += "journal:${journal.phase}"; value = journal; return CredentialResult.Success(Unit)
    }
    override fun clear(): CredentialResult<Unit> {
        events += "journal:clear"
        if (clearFailures > 0) {
            clearFailures--
            return CredentialResult.Failure(CredentialError.Unavailable)
        }
        value = null
        return CredentialResult.Success(Unit)
    }
}

private class RecordingProviderFactory : ProviderFactory {
    override fun create(configuration: ProviderConfiguration) = error("not used")
}

private class RecordingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val workerThread = ThreadLocal.withInitial { false }
    var dispatchCount = 0
    fun isWorkerThread(): Boolean = workerThread.get() == true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        delegate.dispatch(context) {
            workerThread.set(true)
            try {
                block.run()
            } finally {
                workerThread.set(false)
            }
        }
    }
}
