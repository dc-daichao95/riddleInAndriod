package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderContractTest {
    private val config = ProviderConfiguration("p", ProviderType.OPENAI_COMPATIBLE, "Provider", "https://example.test/v1", "alias", "chat", true, ModelCapabilities(streaming = true, toolCalling = true))
    private val request = ModelRequest("chat", listOf(Message(MessageRole.USER, "secret prompt")))

    private fun providers(transport: ModelTransport, credentials: CredentialSource = CredentialSource { "api-secret" }) = listOf<ModelProvider>(
        OpenAiCompatibleProvider(config, transport, credentials),
        DeepSeekProvider(config.copy(type = ProviderType.DEEPSEEK_COMPATIBLE), transport, credentials),
    )

    @Test fun `adapters map equivalent stream fixtures and never execute tool calls`() = runBlocking {
        val body = """data: {"choices":[{"delta":{"content":"Hi","tool_calls":[{"index":0,"id":"call","function":{"name":"danger","arguments":"{}"}}]},"finish_reason":null}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}

data: [DONE]

""".encodeToByteArray()
        providers(FakeTransport(TransportResponse.Success(flow { body.asList().chunked(2).forEach { emit(it.toByteArray()) } }))).forEach { provider ->
            assertEquals(listOf(ModelEvent.TextDelta("Hi"), ModelEvent.UsageUpdated(TokenUsage(3, 2)), ModelEvent.Completed(FinishReason.STOP)), provider.stream(request).toList())
        }
    }

    @Test fun `adapters map HTTP failures and retry after`() = runBlocking {
        val cases = listOf(
            401 to ModelError.Authentication(), 429 to ModelError.RateLimited(2000), 503 to ModelError.Server(503),
        )
        cases.forEach { (status, expected) ->
            providers(FakeTransport(TransportResponse.HttpFailure(status, mapOf("Retry-After" to "2")))).forEach {
                assertEquals(listOf(ModelEvent.Failed(expected)), it.stream(request).toList())
            }
        }
    }

    @Test fun `transport timeout is a typed network failure`() = runBlocking {
        providers(FakeTransport(TransportResponse.NetworkFailure("timeout"))).forEach {
            assertEquals(listOf(ModelEvent.Failed(ModelError.Network("timeout"))), it.stream(request).toList())
        }
    }

    @Test fun `malformed JSON and truncated stream are typed parse failures`() = runBlocking {
        listOf("data: nope\n\n", "data: {\"choices\":[]}").forEach { fixture ->
            providers(FakeTransport(TransportResponse.Success(flow { emit(fixture.encodeToByteArray()) }))).forEach {
                val event = it.stream(request).toList().single()
                assertTrue(event is ModelEvent.Failed && event.error is ModelError.Parsing)
            }
        }
    }

    @Test fun `collection cancellation cancels transport and secret stays out of requests`() = runBlocking {
        val transport = CancellingTransport()
        val job = launch { providers(transport).first().stream(request).collect {} }
        while (!transport.started) kotlinx.coroutines.yield()
        job.cancel(); job.join()
        assertTrue(transport.cancelled)
        assertFalse(transport.lastRequest.toString().contains("api-secret"))
        assertEquals("Bearer api-secret", transport.lastRequest.headers["Authorization"])
    }
}

private class FakeTransport(private val response: TransportResponse) : ModelTransport {
    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow { emit(response) }
}

private class CancellingTransport : ModelTransport {
    @Volatile var started = false
    @Volatile var cancelled = false
    lateinit var lastRequest: TransportRequest
    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow {
        lastRequest = request; started = true
        try { awaitCancellation() } finally { cancelled = true }
    }
}
