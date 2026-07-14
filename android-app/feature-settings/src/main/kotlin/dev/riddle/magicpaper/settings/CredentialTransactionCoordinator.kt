package dev.riddle.magicpaper.settings

import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.security.CredentialError
import dev.riddle.magicpaper.security.CredentialResult
import dev.riddle.magicpaper.security.CredentialStore
import dev.riddle.magicpaper.security.CredentialTransactionJournal
import dev.riddle.magicpaper.security.CredentialTransactionJournalStore
import dev.riddle.magicpaper.security.CredentialTransactionPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID

class CandidateCredential internal constructor(
    val profileId: String,
    val candidateAlias: String,
    val priorAlias: String?,
    val selectionRequested: Boolean,
)

sealed interface CredentialTransactionResult<out T> {
    data class Success<T>(val value: T) : CredentialTransactionResult<T>
    data object CredentialUnavailable : CredentialTransactionResult<Nothing>
    data object StorageUnavailable : CredentialTransactionResult<Nothing>
    data object InconsistentStorage : CredentialTransactionResult<Nothing>
}

class CredentialTransactionCoordinator(
    private val profiles: ProviderProfileRepository,
    private val credentials: CredentialStore,
    private val journalStore: CredentialTransactionJournalStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    suspend fun saveProfile(
        profile: ProviderConfiguration,
        secret: String,
        selectionRequested: Boolean,
    ): CredentialTransactionResult<ProviderConfiguration> = serialized {
        val durableProfiles = listProfiles() ?: return@serialized CredentialTransactionResult.StorageUnavailable
        val previous = durableProfiles.firstOrNull { it.id == profile.id }
        if (secret.isBlank()) {
            val existing = previous ?: return@serialized CredentialTransactionResult.CredentialUnavailable
            val committed = profile.copy(credentialAlias = existing.credentialAlias)
            return@serialized when (commitMetadataLocked(committed, selectionRequested)) {
                is CredentialTransactionResult.Success -> CredentialTransactionResult.Success(committed)
                CredentialTransactionResult.CredentialUnavailable -> CredentialTransactionResult.CredentialUnavailable
                CredentialTransactionResult.StorageUnavailable -> CredentialTransactionResult.StorageUnavailable
                CredentialTransactionResult.InconsistentStorage -> CredentialTransactionResult.InconsistentStorage
            }
        }
        val candidate = when (val begin = beginLocked(
            profile.id,
            previous?.credentialAlias,
            selectionRequested,
            secret,
        )) {
            is CredentialTransactionResult.Success -> begin.value
            CredentialTransactionResult.CredentialUnavailable -> return@serialized CredentialTransactionResult.CredentialUnavailable
            CredentialTransactionResult.StorageUnavailable -> return@serialized CredentialTransactionResult.StorageUnavailable
            CredentialTransactionResult.InconsistentStorage -> return@serialized CredentialTransactionResult.InconsistentStorage
        }
        val committed = profile.copy(credentialAlias = candidate.candidateAlias)
        when (commitLocked(candidate, committed)) {
            is CredentialTransactionResult.Success -> CredentialTransactionResult.Success(committed)
            CredentialTransactionResult.CredentialUnavailable -> CredentialTransactionResult.CredentialUnavailable
            CredentialTransactionResult.StorageUnavailable -> CredentialTransactionResult.StorageUnavailable
            CredentialTransactionResult.InconsistentStorage -> CredentialTransactionResult.InconsistentStorage
        }
    }

    suspend fun begin(
        profileId: String,
        priorAlias: String?,
        selectionRequested: Boolean,
        secret: String,
    ): CredentialTransactionResult<CandidateCredential> = serialized {
        beginLocked(profileId, priorAlias, selectionRequested, secret)
    }

    private suspend fun beginLocked(
        profileId: String,
        priorAlias: String?,
        selectionRequested: Boolean,
        secret: String,
    ): CredentialTransactionResult<CandidateCredential> {
        if (profileId.isBlank() || secret.isBlank()) return CredentialTransactionResult.CredentialUnavailable
        when (val recovery = recoverLocked()) {
            is CredentialTransactionResult.Success -> Unit
            CredentialTransactionResult.CredentialUnavailable -> return CredentialTransactionResult.CredentialUnavailable
            CredentialTransactionResult.StorageUnavailable -> return CredentialTransactionResult.StorageUnavailable
            CredentialTransactionResult.InconsistentStorage -> return CredentialTransactionResult.InconsistentStorage
        }
        val candidate = CandidateCredential(
            profileId = profileId,
            candidateAlias = "candidate-$profileId-${nonce()}",
            priorAlias = priorAlias,
            selectionRequested = selectionRequested,
        )
        val intent = candidate.toJournal(CredentialTransactionPhase.INTENT)
        if (journalStore.write(intent) !is CredentialResult.Success) {
            return CredentialTransactionResult.StorageUnavailable
        }
        if (credentials.put(candidate.candidateAlias, secret) !is CredentialResult.Success) {
            return when (val recovery = recoverLocked()) {
                is CredentialTransactionResult.Success -> CredentialTransactionResult.CredentialUnavailable
                CredentialTransactionResult.CredentialUnavailable -> CredentialTransactionResult.CredentialUnavailable
                CredentialTransactionResult.StorageUnavailable -> CredentialTransactionResult.StorageUnavailable
                CredentialTransactionResult.InconsistentStorage -> CredentialTransactionResult.InconsistentStorage
            }
        }
        if (journalStore.write(candidate.toJournal(CredentialTransactionPhase.CANDIDATE_WRITTEN)) !is CredentialResult.Success) {
            return recoverAfterFailure(CredentialTransactionResult.StorageUnavailable)
        }
        return CredentialTransactionResult.Success(candidate)
    }

    suspend fun commit(
        candidate: CandidateCredential,
        profile: ProviderConfiguration,
    ): CredentialTransactionResult<Unit> = serialized { commitLocked(candidate, profile) }

    private suspend fun commitLocked(
        candidate: CandidateCredential,
        profile: ProviderConfiguration,
    ): CredentialTransactionResult<Unit> {
        if (profile.id != candidate.profileId || profile.credentialAlias != candidate.candidateAlias) {
            return CredentialTransactionResult.InconsistentStorage
        }
        val current = when (val result = journalStore.read()) {
            is CredentialResult.Success -> result.value
            is CredentialResult.Failure -> return CredentialTransactionResult.InconsistentStorage
        }
        if (current == null || !current.matches(candidate)) return CredentialTransactionResult.InconsistentStorage
        try {
            profiles.upsert(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return recoverAfterFailure(CredentialTransactionResult.StorageUnavailable)
        }
        if (journalStore.write(candidate.toJournal(CredentialTransactionPhase.PROFILE_COMMITTED)) !is CredentialResult.Success) {
            return recoverAfterFailure(CredentialTransactionResult.StorageUnavailable)
        }
        return finishCommitted(candidate)
    }

    suspend fun commitMetadata(
        profile: ProviderConfiguration,
        selectionRequested: Boolean,
    ): CredentialTransactionResult<Unit> = serialized { commitMetadataLocked(profile, selectionRequested) }

    private suspend fun commitMetadataLocked(
        profile: ProviderConfiguration,
        selectionRequested: Boolean,
    ): CredentialTransactionResult<Unit> = try {
        profiles.upsert(profile)
        if (selectionRequested) profiles.select(profile.id)
        CredentialTransactionResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialTransactionResult.StorageUnavailable
    }

    suspend fun abandon(candidate: CandidateCredential): CredentialTransactionResult<Unit> =
        serialized { abandonLocked(candidate) }

    private suspend fun abandonLocked(candidate: CandidateCredential): CredentialTransactionResult<Unit> {
        val journal = when (val result = journalStore.read()) {
            is CredentialResult.Success -> result.value ?: return CredentialTransactionResult.Success(Unit)
            is CredentialResult.Failure -> return CredentialTransactionResult.InconsistentStorage
        }
        if (!journal.matches(candidate)) return CredentialTransactionResult.InconsistentStorage
        val durableProfiles = listProfiles() ?: return CredentialTransactionResult.StorageUnavailable
        return if (durableProfiles.any { it.credentialAlias == candidate.candidateAlias }) {
            finishCommitted(candidate)
        } else {
            deleteIfUnreferenced(candidate.candidateAlias)?.let { return it }
            clearJournal()
        }
    }

    suspend fun recover(): CredentialTransactionResult<Unit> = serialized { recoverLocked() }

    private suspend fun recoverLocked(): CredentialTransactionResult<Unit> {
        val journal = when (val result = journalStore.read()) {
            is CredentialResult.Success -> result.value ?: return CredentialTransactionResult.Success(Unit)
            is CredentialResult.Failure -> return CredentialTransactionResult.InconsistentStorage
        }
        val durableProfiles = listProfiles() ?: return CredentialTransactionResult.StorageUnavailable
        val candidate = journal.toCandidate()
        if (durableProfiles.any { it.credentialAlias == journal.candidateAlias }) {
            return finishCommitted(candidate)
        }

        return when (val candidateRead = credentials.read(journal.candidateAlias)) {
            is CredentialResult.Success -> {
                deleteIfUnreferenced(journal.candidateAlias)?.let { return it }
                clearJournal()
            }
            is CredentialResult.Failure -> when {
                journal.phase == CredentialTransactionPhase.INTENT && candidateRead.error == CredentialError.Unavailable -> clearJournal()
                else -> CredentialTransactionResult.InconsistentStorage
            }
        }
    }

    private suspend fun finishCommitted(candidate: CandidateCredential): CredentialTransactionResult<Unit> {
        try {
            if (candidate.selectionRequested) profiles.select(candidate.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CredentialTransactionResult.StorageUnavailable
        }
        candidate.priorAlias?.takeUnless { it == candidate.candidateAlias }?.let { prior ->
            deleteIfUnreferenced(prior)?.let { return it }
        }
        return clearJournal()
    }

    private suspend fun deleteIfUnreferenced(alias: String): CredentialTransactionResult<Unit>? {
        val currentProfiles = listProfiles() ?: return CredentialTransactionResult.StorageUnavailable
        if (currentProfiles.any { it.credentialAlias == alias }) return null
        return if (credentials.delete(alias) is CredentialResult.Success) null
        else CredentialTransactionResult.CredentialUnavailable
    }

    private suspend fun listProfiles(): List<ProviderConfiguration>? = try {
        profiles.list()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun clearJournal(): CredentialTransactionResult<Unit> =
        if (journalStore.clear() is CredentialResult.Success) CredentialTransactionResult.Success(Unit)
        else CredentialTransactionResult.StorageUnavailable

    private fun CandidateCredential.toJournal(phase: CredentialTransactionPhase) = CredentialTransactionJournal(
        profileId, candidateAlias, priorAlias, selectionRequested, phase,
    )

    private fun CredentialTransactionJournal.toCandidate() = CandidateCredential(
        profileId, candidateAlias, priorAlias, selectionRequested,
    )

    private fun CredentialTransactionJournal.matches(candidate: CandidateCredential) =
        profileId == candidate.profileId && candidateAlias == candidate.candidateAlias &&
            priorAlias == candidate.priorAlias && selectionRequested == candidate.selectionRequested

    private suspend fun <T> recoverAfterFailure(
        original: CredentialTransactionResult<T>,
    ): CredentialTransactionResult<T> = when (recoverLocked()) {
        is CredentialTransactionResult.Success -> original
        CredentialTransactionResult.CredentialUnavailable -> CredentialTransactionResult.CredentialUnavailable
        CredentialTransactionResult.StorageUnavailable -> CredentialTransactionResult.StorageUnavailable
        CredentialTransactionResult.InconsistentStorage -> CredentialTransactionResult.InconsistentStorage
    }

    private suspend fun <T> serialized(
        operation: suspend () -> CredentialTransactionResult<T>,
    ): CredentialTransactionResult<T> = withContext(dispatcher) {
        mutex.lock()
        try {
            operation()
        } catch (cancelled: CancellationException) {
            try {
                withContext(NonCancellable) { recoverLocked() }
            } catch (_: Exception) {
                // Preserve the original cancellation; the durable journal remains for startup recovery.
            }
            throw cancelled
        } finally {
            mutex.unlock()
        }
    }
}
