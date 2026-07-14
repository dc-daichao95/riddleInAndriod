package dev.riddle.magicpaper.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CredentialTransactionJournalTest {
    @Test fun `journal round trip contains aliases and intent but never secret`() {
        val records = MemoryJournalRecords()
        val store = DurableCredentialTransactionJournalStore(records)
        val journal = CredentialTransactionJournal(
            profileId = "profile-1",
            candidateAlias = "candidate-profile-1-nonce",
            priorAlias = "credential-profile-1",
            selectionRequested = true,
            phase = CredentialTransactionPhase.INTENT,
        )

        assertIs<CredentialResult.Success<Unit>>(store.write(journal))
        assertEquals(journal, assertIs<CredentialResult.Success<CredentialTransactionJournal?>>(store.read()).value)
        assertFalse(records.value.toString().contains("sentinel-api-key"))

        assertIs<CredentialResult.Success<Unit>>(store.clear())
        assertEquals(null, assertIs<CredentialResult.Success<CredentialTransactionJournal?>>(store.read()).value)
    }
}

private class MemoryJournalRecords : CredentialTransactionJournalRecordStorage {
    var value: CredentialTransactionJournal? = null
    override fun read() = CredentialResult.Success(value)
    override fun write(journal: CredentialTransactionJournal): CredentialResult<Unit> {
        value = journal
        return CredentialResult.Success(Unit)
    }
    override fun clear(): CredentialResult<Unit> {
        value = null
        return CredentialResult.Success(Unit)
    }
}
