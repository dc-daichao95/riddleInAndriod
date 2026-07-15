package dev.riddle.magicpaper.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RecognitionModelKey(
    val languageTag: String,
    val modelVersion: String,
)

internal interface RecognitionModelProvisioningBackend {
    suspend fun isModelDownloaded(key: RecognitionModelKey): Boolean
    suspend fun downloadModel(key: RecognitionModelKey)
    fun requestDownloadCancellation(key: RecognitionModelKey) = Unit
}

internal sealed class RecognitionProvisioningError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class DownloadFailed(val key: RecognitionModelKey, cause: Throwable) :
        RecognitionProvisioningError("Could not prepare handwriting model for ${key.languageTag}", cause)
}

/** Coordinates only concurrent work. Every later call rechecks durable ML Kit state. */
internal class RecognitionProvisioningCoordinator(
    private val backend: RecognitionModelProvisioningBackend,
    private val applicationScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private data class Entry(
        val work: Deferred<Unit>,
        val terminal: CompletableDeferred<Unit> = CompletableDeferred(),
        var waiters: Int,
        var abandoned: Boolean = false,
    )

    private sealed interface Acquisition {
        data class Work(val entry: Entry) : Acquisition
        data class Tombstone(val terminal: Deferred<Unit>) : Acquisition
    }

    private val mutex = Mutex()
    private val active = mutableMapOf<RecognitionModelKey, Entry>()

    suspend fun ensureAvailable(key: RecognitionModelKey) {
        while (true) {
            when (val acquisition = acquire(key)) {
                is Acquisition.Tombstone -> {
                    acquisition.terminal.await()
                    continue
                }
                is Acquisition.Work -> {
                    try {
                        acquisition.entry.work.await()
                        return
                    } finally {
                        release(key, acquisition.entry)
                    }
                }
            }
        }
    }

    private suspend fun acquire(key: RecognitionModelKey): Acquisition = mutex.withLock {
        val existing = active[key]
        if (existing != null) {
            if (existing.abandoned) return@withLock Acquisition.Tombstone(existing.terminal)
            existing.waiters += 1
            return@withLock Acquisition.Work(existing)
        }

        val work = applicationScope.async(dispatcher, start = CoroutineStart.LAZY) {
            try {
                if (!backend.isModelDownloaded(key)) backend.downloadModel(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw RecognitionProvisioningError.DownloadFailed(key, failure)
            }
        }
        val entry = Entry(work = work, waiters = 1)
        active[key] = entry
        work.invokeOnCompletion {
            applicationScope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                mutex.withLock {
                    if (active[key] === entry) active.remove(key)
                    entry.terminal.complete(Unit)
                }
            }
        }
        work.start()
        Acquisition.Work(entry)
    }

    private suspend fun release(key: RecognitionModelKey, entry: Entry) {
        mutex.withLock {
            if (active[key] !== entry) return@withLock
            entry.waiters -= 1
            if (entry.waiters == 0 && !entry.work.isCompleted) {
                entry.abandoned = true
                // Ownership remains with applicationScope until the backend is truly terminal.
                // A backend may best-effort cancel its real operation, but cancelling this
                // Deferred would only cancel an awaiter and could release the key too early.
                backend.requestDownloadCancellation(key)
            }
        }
    }
}
