package dev.riddle.magicpaper.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.InvalidAlgorithmParameterException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedCredential(val iv: ByteArray, val ciphertext: ByteArray)

internal interface CredentialRecordStorage {
    fun read(alias: String): CredentialResult<EncryptedCredential>
    fun write(alias: String, record: EncryptedCredential): CredentialResult<Unit>
    fun delete(alias: String): CredentialResult<Unit>
}

internal fun interface SecretKeyProvider {
    fun getOrCreate(): CredentialResult<SecretKey>
}

internal class AesGcmCredentialCrypto(private val keys: SecretKeyProvider) {
    fun encrypt(secret: String): CredentialResult<EncryptedCredential> = withKey { key ->
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        EncryptedCredential(cipher.iv, cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(record: EncryptedCredential): CredentialResult<String> = withKey(
        corrupt = { error -> error is AEADBadTagException || error is IllegalArgumentException || error is InvalidAlgorithmParameterException },
    ) { key ->
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, record.iv))
        cipher.doFinal(record.ciphertext).toString(Charsets.UTF_8)
    }

    private inline fun <T> withKey(
        corrupt: (Exception) -> Boolean = { false },
        operation: (SecretKey) -> T,
    ): CredentialResult<T> = when (val key = keys.getOrCreate()) {
        is CredentialResult.Failure -> key
        is CredentialResult.Success -> try {
            CredentialResult.Success(operation(key.value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CredentialResult.Failure(if (corrupt(error)) CredentialError.Corrupt else CredentialError.Unavailable)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

internal class EncryptedCredentialStore(
    private val records: CredentialRecordStorage,
    private val crypto: AesGcmCredentialCrypto,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    override suspend fun put(alias: String, secret: String): CredentialResult<Unit> = withContext(dispatcher) {
        if (alias.isBlank() || secret.isBlank()) return@withContext CredentialResult.Failure(CredentialError.Unavailable)
        when (val encrypted = crypto.encrypt(secret)) {
            is CredentialResult.Failure -> encrypted
            is CredentialResult.Success -> records.write(alias, encrypted.value)
        }
    }

    override suspend fun read(alias: String): CredentialResult<String> = withContext(dispatcher) {
        when (val record = records.read(alias)) {
            is CredentialResult.Failure -> record
            is CredentialResult.Success -> crypto.decrypt(record.value)
        }
    }

    override suspend fun delete(alias: String): CredentialResult<Unit> = withContext(dispatcher) { records.delete(alias) }
}

class AndroidKeystoreCredentialStore(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore by EncryptedCredentialStore(
    PreferenceCredentialRecordStorage(context.applicationContext),
    AesGcmCredentialCrypto(AndroidSecretKeyProvider()),
    ioDispatcher,
) {
    companion object { const val KEY_ALIAS = "riddle-provider-secrets-v1" }
}

private class PreferenceCredentialRecordStorage(context: Context) : CredentialRecordStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(alias: String): CredentialResult<EncryptedCredential> {
        val iv = preferences.getString("$alias.iv", null)
        val ciphertext = preferences.getString("$alias.ciphertext", null)
        if (iv == null && ciphertext == null) return CredentialResult.Failure(CredentialError.Unavailable)
        if (iv == null || ciphertext == null) return CredentialResult.Failure(CredentialError.Corrupt)
        return try {
            CredentialResult.Success(EncryptedCredential(Base64.decode(iv, Base64.NO_WRAP), Base64.decode(ciphertext, Base64.NO_WRAP)))
        } catch (_: IllegalArgumentException) {
            CredentialResult.Failure(CredentialError.Corrupt)
        }
    }

    override fun write(alias: String, record: EncryptedCredential): CredentialResult<Unit> =
        if (preferences.edit()
                .putString("$alias.iv", Base64.encodeToString(record.iv, Base64.NO_WRAP))
                .putString("$alias.ciphertext", Base64.encodeToString(record.ciphertext, Base64.NO_WRAP))
                .commit()
        ) CredentialResult.Success(Unit) else CredentialResult.Failure(CredentialError.Unavailable)

    override fun delete(alias: String): CredentialResult<Unit> =
        if (preferences.edit().remove("$alias.iv").remove("$alias.ciphertext").commit()) CredentialResult.Success(Unit)
        else CredentialResult.Failure(CredentialError.Unavailable)

    companion object { private const val PREFERENCES_NAME = "provider-credentials-v1" }
}

private class AndroidSecretKeyProvider : SecretKeyProvider {
    override fun getOrCreate(): CredentialResult<SecretKey> = try {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = store.getKey(AndroidKeystoreCredentialStore.KEY_ALIAS, null) as? SecretKey
        CredentialResult.Success(existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    AndroidKeystoreCredentialStore.KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialResult.Failure(CredentialError.Unavailable)
    }

    companion object { private const val ANDROID_KEY_STORE = "AndroidKeyStore" }
}
