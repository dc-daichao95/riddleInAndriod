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

    private fun providers(transport: ModelTransport) = listOf<ModelProvider>(
        OpenAiCompatibleProvider(config, transport),
        DeepSeekProvider(config.copy(type = ProviderType.DEEPSEEK_COMPATIBLE), transport),
    )

    @Test fun `adapters map equivalent stream fixtures and never execute tool calls`() = runBlocking {
        val body = fixture("text-tool-usage.txt")
        providers(FakeTransport(TransportResponse.Success(flow { body.asList().chunked(2).forEach { emit(it.toByteArray()) } }))).forEach { provider ->
            assertEquals(listOf(ModelEvent.TextDelta("Hi"), ModelEvent.ToolCallStarted("call", "danger"), ModelEvent.ToolCallArgumentsDelta("call", "{}"), ModelEvent.ToolCallCompleted(ToolCall("call", "danger", "{}")), ModelEvent.UsageUpdated(TokenUsage(3, 2)), ModelEvent.Completed(FinishReason.STOP)), provider.stream(request).toList())
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

    @Test fun `provider error message is mapped and malformed body is ignored`() = runBlocking {
        val json = "{\"error\":{\"message\":\"bad credential\",\"type\":\"authentication_error\"}}"
        providers(FakeTransport(TransportResponse.HttpFailure(401, errorBody = json))).forEach {
            assertEquals(listOf(ModelEvent.Failed(ModelError.Authentication("bad credential"))), it.stream(request).toList())
        }
        providers(FakeTransport(TransportResponse.HttpFailure(400, errorBody = "not-json"))).forEach {
            assertEquals(listOf(ModelEvent.Failed(ModelError.InvalidRequest())), it.stream(request).toList())
        }
    }

    @Test fun `malformed JSON and truncated stream are typed parse failures`() = runBlocking {
        listOf(fixture("malformed.txt"), fixture("truncated.txt")).forEach { fixture ->
            providers(FakeTransport(TransportResponse.Success(flow { emit(fixture) }))).forEach {
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
        assertEquals("alias", transport.lastRequest.credentialAlias)
        assertFalse(transport.lastRequest.headers.containsKey("Authorization"))
    }

    @Test fun `done stops later data and synthesizes one completion`() = runBlocking {
        val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"first\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"later\"}}]}\n\n".encodeToByteArray()
        providers(FakeTransport(TransportResponse.Success(flow { emit(bytes) }))).forEach {
            assertEquals(listOf(ModelEvent.TextDelta("first"), ModelEvent.Completed(FinishReason.STOP)), it.stream(request).toList())
        }
    }

    @Test fun `done cancels upstream chunk collection immediately`() = runBlocking {
        var continuedAfterDone = false
        val chunks = flow {
            emit("data: [DONE]\n\n".encodeToByteArray())
            continuedAfterDone = true
            emit("data: later\n\n".encodeToByteArray())
        }
        providers(FakeTransport(TransportResponse.Success(chunks))).forEach {
            assertEquals(listOf(ModelEvent.Completed(FinishReason.STOP)), it.stream(request).toList())
        }
        assertFalse(continuedAfterDone)
    }

    @Test fun `list models and validation use provider host with content free minimal requests`() = runBlocking {
        val transport = RecordingTransport(listOf(
            TransportResponse.Success(flow { emit("{\"data\":[{\"id\":\"chat\"}]}".encodeToByteArray()) }),
            TransportResponse.Success(flow { emit("data: [DONE]\n\n".encodeToByteArray()) }),
        ))
        val provider = OpenAiCompatibleProvider(config, transport)
        assertEquals(listOf("chat"), provider.listModels().getOrThrow().map { it.id })
        val candidate = config.copy(baseUrl = "https://actual-host.test/api")
        assertEquals(ValidationResult.Valid, provider.validate(candidate))
        assertTrue(transport.requests.first().url.startsWith("https://example.test/v1/"))
        assertTrue(transport.requests.last().url.startsWith("https://actual-host.test/api/"))
        assertFalse(transport.requests.last().body.contains("secret prompt"))
        assertFalse(transport.requests.last().body.contains("image"))
        assertEquals(TransportMethod.GET, transport.requests.first().method)
        assertEquals(TransportMethod.POST, transport.requests.last().method)
    }

    @Test fun `tool argument fragments retain call identity and complete without execution`() = runBlocking {
        val bytes = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}\n\ndata: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n".encodeToByteArray()
        providers(FakeTransport(TransportResponse.Success(flow { emit(bytes) }))).forEach {
            assertEquals(listOf(ModelEvent.ToolCallStarted("c1", "lookup"), ModelEvent.ToolCallArgumentsDelta("c1", "{\"q\":"), ModelEvent.ToolCallArgumentsDelta("c1", "1}"), ModelEvent.ToolCallCompleted(ToolCall("c1", "lookup", "{\"q\":1}")), ModelEvent.Completed(FinishReason.STOP)), it.stream(request).toList())
        }
    }

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/sse/$name")).readBytes()
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

private class RecordingTransport(responses: List<TransportResponse>) : ModelTransport {
    private val responses = ArrayDeque(responses)
    val requests = mutableListOf<TransportRequest>()
    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow { requests += request; emit(responses.removeFirst()) }
}
