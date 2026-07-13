package dev.riddle.magicpaper.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.crypto.KeyGenerator
import kotlin.test.*

class EncryptedCredentialStoreTest {
    @Test fun `round trip uses unique IV and stores no plaintext then deletes`() = runBlocking {
        val records = MemoryRecords()
        val store = store(records)

        assertIs<CredentialResult.Success<Unit>>(store.put("first", "top-secret"))
        assertIs<CredentialResult.Success<Unit>>(store.put("second", "top-secret"))
        assertEquals("top-secret", assertIs<CredentialResult.Success<String>>(store.read("first")).value)
        assertFalse(records.values["first"]!!.iv.contentEquals(records.values["second"]!!.iv))
        assertFalse(records.values.values.any { it.ciphertext.decodeToString().contains("top-secret") })

        assertIs<CredentialResult.Success<Unit>>(store.delete("first"))
        assertEquals(CredentialError.Unavailable, assertIs<CredentialResult.Failure>(store.read("first")).error)
    }

    @Test fun `tampering is authenticated and returns corrupt`() = runBlocking {
        val records = MemoryRecords()
        val store = store(records)
        store.put("alias", "secret")
        records.values["alias"]!!.ciphertext[0] = (records.values["alias"]!!.ciphertext[0].toInt() xor 1).toByte()

        assertEquals(CredentialError.Corrupt, assertIs<CredentialResult.Failure>(store.read("alias")).error)
    }

    @Test fun `malformed IV returns corrupt`() = runBlocking {
        val records = MemoryRecords()
        val store = store(records)
        store.put("alias", "secret")
        records.values["alias"] = records.values["alias"]!!.copy(iv = byteArrayOf())
        assertEquals(CredentialError.Corrupt, assertIs<CredentialResult.Failure>(store.read("alias")).error)
    }

    @Test fun `unavailable key maps to typed unavailable`() = runBlocking {
        val records = MemoryRecords()
        val crypto = AesGcmCredentialCrypto(SecretKeyProvider { CredentialResult.Failure(CredentialError.Unavailable) })
        val store = EncryptedCredentialStore(records, crypto, Dispatchers.Unconfined)

        assertEquals(CredentialError.Unavailable, assertIs<CredentialResult.Failure>(store.put("alias", "secret")).error)
    }

    private fun store(records: MemoryRecords): EncryptedCredentialStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return EncryptedCredentialStore(records, AesGcmCredentialCrypto(SecretKeyProvider { CredentialResult.Success(key) }), Dispatchers.Unconfined)
    }
}

private class MemoryRecords : CredentialRecordStorage {
    val values = mutableMapOf<String, EncryptedCredential>()
    override fun read(alias: String) = values[alias]?.let { CredentialResult.Success(it) } ?: CredentialResult.Failure(CredentialError.Unavailable)
    override fun write(alias: String, record: EncryptedCredential): CredentialResult<Unit> { values[alias] = record; return CredentialResult.Success(Unit) }
    override fun delete(alias: String): CredentialResult<Unit> { values.remove(alias); return CredentialResult.Success(Unit) }
}
