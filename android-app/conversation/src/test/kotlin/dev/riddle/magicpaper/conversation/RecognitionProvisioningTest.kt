package dev.riddle.magicpaper.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecognitionProvisioningTest {
    @Test
    fun `installed model skips download`() = runTest {
        val key = RecognitionModelKey("en-US", "v1")
        val backend = FakeProvisioningBackend(installed = mutableSetOf(key))
        val coordinator = coordinator(backend)

        coordinator.ensureAvailable(key)

        assertEquals(1, backend.installedChecks)
        assertEquals(0, backend.downloadCalls)
    }

    @Test
    fun `concurrent waiters for the same key share one backend download`() = runTest {
        val key = RecognitionModelKey("ja-JP", "v1")
        val gate = CompletableDeferred<Unit>()
        val backend = FakeProvisioningBackend(downloadGate = gate)
        val coordinator = coordinator(backend)

        val first = async { coordinator.ensureAvailable(key) }
        val second = async { coordinator.ensureAvailable(key) }
        runCurrent()

        assertEquals(1, backend.downloadCalls)
        gate.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `cancelling one waiter does not cancel shared work for another waiter`() = runTest {
        val key = RecognitionModelKey("ja-JP", "v1")
        val gate = CompletableDeferred<Unit>()
        val backend = FakeProvisioningBackend(downloadGate = gate)
        val coordinator = coordinator(backend)
        val first = async { coordinator.ensureAvailable(key) }
        val second = async { coordinator.ensureAvailable(key) }
        runCurrent()

        first.cancelAndJoin()
        assertEquals(0, backend.cancelRequests)
        gate.complete(Unit)

        second.await()
        assertEquals(1, backend.downloadCalls)
    }

    @Test
    fun `final waiter cancellation retains key ownership until backend is truly terminal`() = runTest {
        val key = RecognitionModelKey("ja-JP", "v1")
        val firstGate = CompletableDeferred<Unit>()
        val backend = FakeProvisioningBackend(downloadGate = firstGate)
        val coordinator = coordinator(backend)
        val first = async { coordinator.ensureAvailable(key) }
        runCurrent()

        first.cancelAndJoin()
        runCurrent()
        assertEquals(1, backend.cancelRequests)

        backend.downloadGate = CompletableDeferred<Unit>()
        val next = async { coordinator.ensureAvailable(key) }
        runCurrent()
        assertEquals(1, backend.downloadCalls)

        firstGate.complete(Unit)
        runCurrent()
        next.await()
        assertEquals(1, backend.downloadCalls)
    }

    @Test
    fun `new coordinator after process restart checks installed state again`() = runTest {
        val key = RecognitionModelKey("en-US", "v1")
        val backend = FakeProvisioningBackend()
        coordinator(backend).ensureAvailable(key)
        val checksAfterFirstSession = backend.installedChecks

        coordinator(backend).ensureAvailable(key)

        assertEquals(checksAfterFirstSession + 1, backend.installedChecks)
        assertEquals(1, backend.downloadCalls)
    }

    @Test
    fun `locale and model version use isolated shared work`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeProvisioningBackend(downloadGate = gate)
        val coordinator = coordinator(backend)
        val jobs = listOf(
            async { coordinator.ensureAvailable(RecognitionModelKey("en-US", "v1")) },
            async { coordinator.ensureAvailable(RecognitionModelKey("en-US", "v2")) },
            async { coordinator.ensureAvailable(RecognitionModelKey("ja-JP", "v1")) },
        )
        runCurrent()

        assertEquals(3, backend.downloadCalls)
        gate.complete(Unit)
        jobs.forEach { it.await() }
    }

    @Test
    fun `download failure is typed and can be retried by a later call`() = runTest {
        val key = RecognitionModelKey("ja-JP", "v1")
        val backend = FakeProvisioningBackend(failure = IllegalStateException("offline"))
        val coordinator = coordinator(backend)

        val failure = assertFailsWith<RecognitionProvisioningError.DownloadFailed> {
            coordinator.ensureAvailable(key)
        }
        assertEquals(key, failure.key)

        backend.failure = null
        coordinator.ensureAvailable(key)
        assertEquals(2, backend.downloadCalls)
    }

    @Test
    fun `installed check and download run on the supplied worker dispatcher`() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "recognition-worker")
        }.asCoroutineDispatcher()
        try {
            var backendThread = ""
            val backend = object : RecognitionModelProvisioningBackend {
                override suspend fun isModelDownloaded(key: RecognitionModelKey): Boolean {
                    backendThread = Thread.currentThread().name
                    return false
                }

                override suspend fun downloadModel(key: RecognitionModelKey) = Unit
            }
            val coordinator = RecognitionProvisioningCoordinator(
                backend,
                CoroutineScope(SupervisorJob() + dispatcher),
                dispatcher,
            )

            coordinator.ensureAvailable(RecognitionModelKey("en-US", "v1"))

            assertTrue(backendThread.startsWith("recognition-worker"))
        } finally {
            dispatcher.close()
        }
    }

    private fun TestScope.coordinator(backend: RecognitionModelProvisioningBackend): RecognitionProvisioningCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RecognitionProvisioningCoordinator(
            backend = backend,
            applicationScope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
        )
    }

    private class FakeProvisioningBackend(
        private val installed: MutableSet<RecognitionModelKey> = mutableSetOf(),
        var downloadGate: CompletableDeferred<Unit>? = null,
        var failure: Exception? = null,
    ) : RecognitionModelProvisioningBackend {
        var installedChecks = 0
        var downloadCalls = 0
        var cancelRequests = 0

        override suspend fun isModelDownloaded(key: RecognitionModelKey): Boolean {
            installedChecks += 1
            return key in installed
        }

        override suspend fun downloadModel(key: RecognitionModelKey) {
            downloadCalls += 1
            downloadGate?.await()
            failure?.let { throw it }
            installed += key
        }

        override fun requestDownloadCancellation(key: RecognitionModelKey) {
            cancelRequests += 1
        }
    }
}
