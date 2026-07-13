package dev.riddle.magicpaper.security

interface CredentialStore {
    suspend fun put(alias: String, secret: String): CredentialResult<Unit>
    suspend fun read(alias: String): CredentialResult<String>
    suspend fun delete(alias: String): CredentialResult<Unit>
}

sealed interface CredentialResult<out T> {
    data class Success<T>(val value: T) : CredentialResult<T>
    data class Failure(val error: CredentialError) : CredentialResult<Nothing>
}

enum class CredentialError {
    Unavailable,
    Corrupt,
}
