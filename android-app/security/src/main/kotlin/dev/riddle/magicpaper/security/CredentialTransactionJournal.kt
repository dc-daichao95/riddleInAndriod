package dev.riddle.magicpaper.security

import android.content.Context
import android.content.SharedPreferences

enum class CredentialTransactionPhase { INTENT, CANDIDATE_WRITTEN, PROFILE_COMMITTED }

data class CredentialTransactionJournal(
    val profileId: String,
    val candidateAlias: String,
    val priorAlias: String?,
    val selectionRequested: Boolean,
    val phase: CredentialTransactionPhase,
) {
    init {
        require(profileId.isNotBlank())
        require(candidateAlias.isNotBlank())
    }
}

interface CredentialTransactionJournalStore {
    fun read(): CredentialResult<CredentialTransactionJournal?>
    fun write(journal: CredentialTransactionJournal): CredentialResult<Unit>
    fun clear(): CredentialResult<Unit>
}

interface CredentialTransactionJournalRecordStorage : CredentialTransactionJournalStore

class DurableCredentialTransactionJournalStore(
    private val records: CredentialTransactionJournalRecordStorage,
) : CredentialTransactionJournalStore by records

class SharedPreferencesCredentialTransactionJournalStorage(
    context: Context,
) : CredentialTransactionJournalRecordStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): CredentialResult<CredentialTransactionJournal?> {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return CredentialResult.Success(null)
        return try {
            val profileId = requireNotNull(preferences.getString(KEY_PROFILE_ID, null))
            val candidateAlias = requireNotNull(preferences.getString(KEY_CANDIDATE_ALIAS, null))
            val phase = CredentialTransactionPhase.valueOf(requireNotNull(preferences.getString(KEY_PHASE, null)))
            CredentialResult.Success(
                CredentialTransactionJournal(
                    profileId = profileId,
                    candidateAlias = candidateAlias,
                    priorAlias = preferences.getString(KEY_PRIOR_ALIAS, null),
                    selectionRequested = preferences.getBoolean(KEY_SELECTION_REQUESTED, false),
                    phase = phase,
                ),
            )
        } catch (_: RuntimeException) {
            CredentialResult.Failure(CredentialError.Corrupt)
        }
    }

    override fun write(journal: CredentialTransactionJournal): CredentialResult<Unit> =
        resultOf(preferences.edit().apply {
            putString(KEY_PROFILE_ID, journal.profileId)
            putString(KEY_CANDIDATE_ALIAS, journal.candidateAlias)
            if (journal.priorAlias == null) remove(KEY_PRIOR_ALIAS) else putString(KEY_PRIOR_ALIAS, journal.priorAlias)
            putBoolean(KEY_SELECTION_REQUESTED, journal.selectionRequested)
            putString(KEY_PHASE, journal.phase.name)
            putBoolean(KEY_ACTIVE, true)
        })

    override fun clear(): CredentialResult<Unit> = resultOf(preferences.edit().clear())

    private fun resultOf(editor: SharedPreferences.Editor): CredentialResult<Unit> =
        if (editor.commit()) CredentialResult.Success(Unit) else CredentialResult.Failure(CredentialError.Unavailable)

    private companion object {
        const val PREFERENCES_NAME = "credential-transaction-journal-v1"
        const val KEY_ACTIVE = "active"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CANDIDATE_ALIAS = "candidate_alias"
        const val KEY_PRIOR_ALIAS = "prior_alias"
        const val KEY_SELECTION_REQUESTED = "selection_requested"
        const val KEY_PHASE = "phase"
    }
}
