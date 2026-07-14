package dev.riddle.magicpaper.provider

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class OkHttpModelTransportTest {
    @Test fun `cancellation after callback resume but before collector dispatch closes discarded response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete body"))
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "paused-collector") }.asCoroutineDispatcher().use { delegate ->
                val dispatcher = PausingDispatcher(delegate)
                val sourceClosed = AtomicBoolean()
                val events = HeadersPauseEvents(dispatcher)
                val client = OkHttpModelTransport.defaultClient().newBuilder()
                    .eventListener(events)
                    .addNetworkInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        response.newBuilder().body(TrackingResponseBody(response.body, sourceClosed)).build()
                    }
                    .build()
                var call: Call? = null
                val transport = OkHttpModelTransport(CredentialSource { "secret" }, client, callCreated = { call = it })
                val job = CoroutineScope(dispatcher).launch {
                    transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "", "alias", TransportMethod.GET)).collect {
                        if (it is TransportResponse.Success) it.chunks.collect()
                    }
                }
                withTimeout(2_000) {
                    while (!events.headersSeen.get() || dispatcher.queuedCount == 0) yield()
                }
                job.cancel()
                dispatcher.release()
                withTimeout(1_000) { job.join() }
                withTimeout(1_000) {
                    while (!sourceClosed.get() || !events.connectionReleased.get()) yield()
                }
                assertEquals(true, call?.isCanceled())
                assertTrue(sourceClosed.get())
                assertTrue(events.connectionReleased.get())
            }
        }
    }

    @Test fun `synchronous response reads leave the caller main dispatcher`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("error"))
            val readThread = AtomicReference<String>()
            val client = OkHttpModelTransport.defaultClient().newBuilder()
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder().body(TrackingResponseBody(response.body, AtomicBoolean(), readThread)).build()
                }
                .build()
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "transport-caller-main") }.asCoroutineDispatcher().use { caller ->
                Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "transport-io") }.asCoroutineDispatcher().use { io ->
                    val transport = OkHttpModelTransport(CredentialSource { "secret" }, client, blockingDispatcher = io)
                    withContext(caller) {
                        transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "", "alias", TransportMethod.GET)).toList()
                    }
                }
            }
            assertTrue(readThread.get().orEmpty().startsWith("transport-io"), readThread.get())
        }
    }

    @Test fun `cancelling after success headers and a partial body promptly closes call body and connection`() = runBlocking {
        repeat(3) { assertBlockingBodyCancellation(responseCode = 200, collectSuccessBody = true) }
    }

    @Test fun `cancelling after error headers and a partial body promptly closes call body and connection`() = runBlocking {
        repeat(3) { assertBlockingBodyCancellation(responseCode = 400, collectSuccessBody = false) }
    }

    private suspend fun assertBlockingBodyCancellation(responseCode: Int, collectSuccessBody: Boolean) {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(responseCode)
                    .setBody(if (collectSuccessBody) "{\"data\":[" else "{\"error\":")
                    .setHeader("Content-Length", "1048576")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN),
            )
            val events = BlockingBodyEvents()
            val sourceClosed = AtomicBoolean()
            val client = OkHttpModelTransport.defaultClient().newBuilder()
                .eventListener(events)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder().body(TrackingResponseBody(response.body, sourceClosed)).build()
                }
                .build()
            var call: Call? = null
            val transport = OkHttpModelTransport(CredentialSource { "secret" }, client, callCreated = { call = it })
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "", "alias", TransportMethod.GET)).collect {
                    if (it is TransportResponse.Success) it.chunks.collect()
                }
            }
            withTimeout(2_000) { while (!events.bodyStarted.get()) yield() }
            job.cancel()
            withTimeout(1_000) { job.join() }
            withTimeout(1_000) { while (!events.connectionReleased.get()) yield() }
            assertEquals(true, call?.isCanceled())
            assertTrue(sourceClosed.get())
            assertTrue(events.connectionReleased.get())
        }
    }

    @Test fun `general request rejects credential bearing caller headers`() {
        listOf("Authorization", "authorization", "Proxy-Authorization", "Cookie").forEach { reserved ->
            assertFailsWith<IllegalArgumentException> {
                TransportRequest("https://example.test", mapOf(reserved to "attacker"), "{}", "alias")
            }
        }
    }
    @Test fun `credential is resolved only at concrete request boundary`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream"))
            val request = TransportRequest(server.url("/chat").toString(), emptyMap(), "{}", "key-alias")
            val transport = OkHttpModelTransport(CredentialSource { alias -> assertEquals("key-alias", alias); "raw-secret" })
            transport.stream(request).collect { if (it is TransportResponse.Success) it.chunks.collect() }
            assertEquals("Bearer raw-secret", server.takeRequest().getHeader("Authorization"))
            assertFalse(request.toString().contains("raw-secret"))
        }
    }

    @Test fun `collector cancellation cancels the concrete OkHttp call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            var call: Call? = null
            val transport = OkHttpModelTransport(CredentialSource { "secret" }, callCreated = { call = it })
            val job = launch { transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "{}", "a")).collect() }
            yield()
            server.takeRequest()
            while (call == null) yield()
            job.cancel(); job.join()
            assertEquals(true, call?.isCanceled())
        }
    }

    @Test fun `HTTP error body accepts 64 KiB and marks one byte over without silent truncation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(65_536)))
            server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(65_537)))
            val transport = OkHttpModelTransport(CredentialSource { "secret" })
            val boundary = transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "{}", "a")).toList().single() as TransportResponse.HttpFailure
            assertEquals(65_536, boundary.errorBody?.encodeToByteArray()?.size)
            assertFalse(boundary.errorBodyLimitExceeded)
            val over = transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "{}", "a")).toList().single() as TransportResponse.HttpFailure
            assertEquals(65_536, over.errorBody?.encodeToByteArray()?.size)
            assertEquals(true, over.errorBodyLimitExceeded)
        }
    }

    @Test fun `HTTP error body redacts the credential before leaving transport`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"rejected raw-secret\"}}"))
            val transport = OkHttpModelTransport(CredentialSource { "raw-secret" })
            val response = transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "{}", "a")).toList().single() as TransportResponse.HttpFailure
            assertFalse(response.errorBody.orEmpty().contains("raw-secret"))
        }
    }
}

private class TrackingResponseBody(
    private val delegate: ResponseBody,
    private val closed: AtomicBoolean,
    private val readThread: AtomicReference<String>? = null,
) : ResponseBody() {
    private val trackingSource = object : ForwardingSource(delegate.source()) {
        override fun read(sink: okio.Buffer, byteCount: Long): Long {
            readThread?.compareAndSet(null, Thread.currentThread().name)
            return super.read(sink, byteCount)
        }

        override fun close() {
            closed.set(true)
            super.close()
        }
    }.buffer()

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = trackingSource
}

private class PausingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val queued = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var paused = false
    val queuedCount: Int get() = queued.size

    fun pause() { paused = true }
    fun release() {
        paused = false
        while (true) delegate.dispatch(kotlin.coroutines.EmptyCoroutineContext, queued.poll() ?: break)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (paused) queued += block else delegate.dispatch(context, block)
    }
}

private class HeadersPauseEvents(
    private val dispatcher: PausingDispatcher,
) : EventListener() {
    val headersSeen = AtomicBoolean()
    val connectionReleased = AtomicBoolean()

    override fun responseHeadersEnd(call: Call, response: Response) {
        dispatcher.pause()
        headersSeen.set(true)
    }

    override fun connectionReleased(call: Call, connection: Connection) { connectionReleased.set(true) }
}

private class BlockingBodyEvents : EventListener() {
    val bodyStarted = AtomicBoolean()
    val bodyEnded = AtomicBoolean()
    val connectionReleased = AtomicBoolean()

    override fun responseBodyStart(call: Call) { bodyStarted.set(true) }
    override fun responseBodyEnd(call: Call, byteCount: Long) { bodyEnded.set(true) }
    override fun connectionReleased(call: Call, connection: Connection) { connectionReleased.set(true) }
}
