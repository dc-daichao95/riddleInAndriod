package dev.riddle.magicpaper.provider

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class OkHttpModelTransportTest {
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

    @Test fun `HTTP error body is bounded before leaving transport`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(20_000)))
            val transport = OkHttpModelTransport(CredentialSource { "secret" })
            val response = transport.stream(TransportRequest(server.url("/").toString(), emptyMap(), "{}", "a")).toList().single() as TransportResponse.HttpFailure
            assertEquals(16_384, response.errorBody?.length)
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
