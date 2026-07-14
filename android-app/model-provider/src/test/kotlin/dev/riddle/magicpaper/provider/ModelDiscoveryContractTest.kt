package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelDiscoveryResult
import dev.riddle.magicpaper.model.ModelError
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Shared MPX-FR-006 / MPX-AC-003-004 discovery contract for every initial adapter. */
class ModelDiscoveryContractTest {
    @Test fun `authenticated discovery uses the candidate alias and provider models endpoint`() = runBlocking {
        ProviderType.entries.forEach { type ->
            val transport = RequestRecordingDiscoveryTransport(catalog("{\"id\":\"model\"}"))
            assertIs<ModelDiscoveryResult.Success>(providerFor(type, transport).listModels())
            assertEquals("candidate-alias", transport.request.credentialAlias)
            assertEquals(TransportMethod.GET, transport.request.method)
            assertEquals("https://example.test/v1/models", transport.request.url)
            assertTrue(transport.request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
        }
    }

    @Test fun `authenticated catalogs are normalized case-sensitively with first metadata winning`() = runBlocking {
        val body = catalog(
            "{\"id\":\" z \"}",
            "{\"id\":\"Alpha\",\"name\":\"First\"}",
            "{\"id\":\"alpha\",\"name\":\"lower\"}",
            "{\"id\":\"Alpha\",\"name\":\"Later\"}",
            "{\"id\":\"ä\"}",
        )

        withLocale(Locale.forLanguageTag("tr-TR")) {
            providers(success(body)).forEach { provider ->
                val models = assertIs<ModelDiscoveryResult.Success>(provider.listModels()).models
                assertEquals(listOf("Alpha", "alpha", "z", "ä"), models.map { it.id })
                assertEquals("First", models.first().displayName)
            }
        }
    }

    @Test fun `empty catalog succeeds and blank IDs reject the whole catalog`() = runBlocking {
        providers(success(catalog())).forEach {
            assertEquals(ModelDiscoveryResult.Success(emptyList()), it.listModels())
        }
        providers(success(catalog("{\"id\":\"   \"}"))).forEach {
            assertIs<ModelError.InvalidResponse>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
        }
    }

    @Test fun `unsupported model endpoints are explicit`() = runBlocking {
        listOf(404, 405, 501).forEach { status ->
            providers(response(TransportResponse.HttpFailure(status))).forEach {
                assertEquals(ModelDiscoveryResult.Unsupported, it.listModels())
            }
        }
    }

    @Test fun `HTTP network timeout and malformed failures remain typed`() = runBlocking {
        val cases = listOf(
            TransportResponse.HttpFailure(401) to ModelError.Authentication(),
            TransportResponse.HttpFailure(403) to ModelError.Authorization(),
            TransportResponse.HttpFailure(429, mapOf("Retry-After" to "2")) to ModelError.RateLimited(2_000),
            TransportResponse.HttpFailure(503) to ModelError.Server(503),
            TransportResponse.NetworkFailure("offline") to ModelError.Network("offline"),
            TransportResponse.TimeoutFailure to ModelError.Timeout,
            TransportResponse.CancelledFailure to ModelError.Cancelled,
        )
        cases.forEach { (transportResponse, expected) ->
            providers(response(transportResponse)).forEach {
                assertEquals(ModelDiscoveryResult.Failed(expected), it.listModels())
            }
        }
        listOf("{}", "{\"data\":{}}", catalog("{\"name\":\"missing id\"}"), "not-json").forEach { body ->
            providers(success(body)).forEach {
                assertIs<ModelError.InvalidResponse>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
            }
        }
    }

    @Test fun `success body limit accepts two MiB and rejects one byte over without partial results`() = runBlocking {
        val prefix = "{\"data\":[{\"id\":\"ok\"}]}"
        val atLimit = prefix + " ".repeat(MAX_BODY_BYTES - prefix.encodeToByteArray().size)
        assertEquals(MAX_BODY_BYTES, atLimit.encodeToByteArray().size)
        providers(success(atLimit)).forEach {
            assertEquals(listOf("ok"), assertIs<ModelDiscoveryResult.Success>(it.listModels()).models.map { model -> model.id })
        }
        providers(success("$atLimit ")).forEach {
            assertIs<ModelError.ResponseTooLarge>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
        }
    }

    @Test fun `catalog entry limit accepts two thousand and rejects one over`() = runBlocking {
        val atLimit = (0 until MAX_CATALOG_ENTRIES).joinToString(",") { "{\"id\":\"m$it\"}" }
        providers(success("{\"data\":[$atLimit]}")).forEach {
            assertEquals(MAX_CATALOG_ENTRIES, assertIs<ModelDiscoveryResult.Success>(it.listModels()).models.size)
        }
        providers(success("{\"data\":[$atLimit,{\"id\":\"overflow\"}]}")).forEach {
            assertIs<ModelError.ResponseTooLarge>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
        }
    }

    @Test fun `UTF-8 model field limits accept boundaries and reject one byte over`() = runBlocking {
        val idAtLimit = "i".repeat(MAX_ID_BYTES)
        val nameAtLimit = "名".repeat(170) + "aa" // 512 UTF-8 bytes.
        assertEquals(MAX_NAME_BYTES, nameAtLimit.encodeToByteArray().size)
        providers(success(catalog("{\"id\":\"$idAtLimit\",\"name\":\"$nameAtLimit\"}"))).forEach {
            val model = assertIs<ModelDiscoveryResult.Success>(it.listModels()).models.single()
            assertEquals(idAtLimit, model.id)
            assertEquals(nameAtLimit, model.displayName)
        }
        listOf(
            catalog("{\"id\":\"${idAtLimit}x\"}"),
            catalog("{\"id\":\"ok\",\"name\":\"${nameAtLimit}x\"}"),
        ).forEach { body ->
            providers(success(body)).forEach {
                assertIs<ModelError.InvalidResponse>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
            }
        }
    }

    @Test fun `sanitized error body accepts 64 KiB and rejects one byte over`() = runBlocking {
        val atLimit = "x".repeat(MAX_ERROR_BYTES)
        providers(response(TransportResponse.HttpFailure(400, errorBody = atLimit))).forEach {
            assertIs<ModelError.InvalidRequest>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
        }
        providers(response(TransportResponse.HttpFailure(400, errorBody = "$atLimit" + "x"))).forEach {
            assertIs<ModelError.ResponseTooLarge>(assertIs<ModelDiscoveryResult.Failed>(it.listModels()).error)
        }
    }

    @Test fun `collector cancellation propagates and releases discovery response`() = runBlocking {
        ProviderType.entries.forEach { type ->
            val transport = CleanupTransport()
            val cancellableProvider = providerFor(type, transport)
            val job = launch { cancellableProvider.listModels() }
            while (!transport.started) yield()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(transport.cleaned)
        }
    }

    @Test fun `only the first transport response is authoritative and outer resources are released`() = runBlocking {
        ProviderType.entries.forEach { type ->
            var secondResponseEmitted = false
            var outerCleaned = false
            val failureThenSuccess = ModelTransport { flow {
                try {
                    emit(TransportResponse.HttpFailure(401))
                    secondResponseEmitted = true
                    emit(TransportResponse.Success(flow { emit(catalog("{\"id\":\"late\"}").encodeToByteArray()) }))
                } finally { outerCleaned = true }
            } }
            assertIs<ModelError.Authentication>(
                assertIs<ModelDiscoveryResult.Failed>(providerFor(type, failureThenSuccess).listModels()).error,
            )
            assertEquals(false, secondResponseEmitted)
            assertTrue(outerCleaned)

            var secondBodyStarted = false
            outerCleaned = false
            val successThenSuccess = ModelTransport { flow {
                try {
                    emit(TransportResponse.Success(flow { emit(catalog("{\"id\":\"first\"}").encodeToByteArray()) }))
                    emit(TransportResponse.Success(flow {
                        secondBodyStarted = true
                        emit(catalog("{\"id\":\"second\"}").encodeToByteArray())
                    }))
                } finally { outerCleaned = true }
            } }
            val models = assertIs<ModelDiscoveryResult.Success>(providerFor(type, successThenSuccess).listModels()).models
            assertEquals(listOf("first"), models.map { it.id })
            assertEquals(false, secondBodyStarted)
            assertTrue(outerCleaned)
        }
    }

    @Test fun `discovery assembly and parsing leave the caller main dispatcher`() = runBlocking {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "caller-main-sentinel") }.asCoroutineDispatcher().use { caller ->
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "model-parser-sentinel") }.asCoroutineDispatcher().use { parser ->
                ProviderType.entries.forEach { type ->
                    var collectionThread = ""
                    val transport = ModelTransport { flow {
                        emit(TransportResponse.Success(flow {
                            collectionThread = Thread.currentThread().name
                            emit(catalog("{\"id\":\"model\"}").encodeToByteArray())
                        }))
                    } }
                    withContext(caller) { providerFor(type, transport, parser).listModels() }
                    assertTrue(collectionThread.startsWith("model-parser-sentinel"), collectionThread)
                }
            }
        }
    }

    @Test fun `discovery and streaming error mapping leave the caller main dispatcher`() = runBlocking {
        val prefix = "{\"error\":{\"message\":\""
        val suffix = "\"}}"
        val errorBody = prefix + "x".repeat(MAX_ERROR_BYTES - prefix.length - suffix.length) + suffix
        assertEquals(MAX_ERROR_BYTES, errorBody.encodeToByteArray().size)
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "error-caller-main") }.asCoroutineDispatcher().use { caller ->
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "error-parser") }.asCoroutineDispatcher().use { parserDelegate ->
                val parser = ThreadRecordingDispatcher(parserDelegate)
                ProviderType.entries.forEach { type ->
                    val provider = providerFor(type, response(TransportResponse.HttpFailure(401, errorBody = errorBody)), parser)
                    withContext(caller) { provider.listModels() }
                    assertTrue(parser.lastThread.startsWith("error-parser"), parser.lastThread)
                    parser.lastThread = ""
                    withContext(caller) {
                        provider.stream(ModelRequest("model", listOf(Message(MessageRole.USER, "ping")))).toList()
                    }.also { events -> assertIs<ModelEvent.Failed>(events.single()) }
                    assertTrue(parser.lastThread.startsWith("error-parser"), parser.lastThread)
                }
            }
        }
    }

    @Test fun `invalid UTF-8 never becomes a replacement-character model ID`() = runBlocking {
        val prefix = "{\"data\":[{\"id\":\"".encodeToByteArray()
        val suffix = "\"}]}".encodeToByteArray()
        val invalidBodies = listOf(
            listOf(prefix + byteArrayOf(0x80.toByte()) + suffix),
            listOf(prefix + byteArrayOf(0xC3.toByte()), byteArrayOf(0x28) + suffix),
            listOf(prefix + byteArrayOf(0xE2.toByte()), byteArrayOf(0x82.toByte()) + suffix),
        )
        invalidBodies.forEach { chunks ->
            val transport = ModelTransport { flow {
                emit(TransportResponse.Success(flow { chunks.forEach { emit(it) } }))
            } }
            providers(transport).forEach { provider ->
                assertIs<ModelError.InvalidResponse>(
                    assertIs<ModelDiscoveryResult.Failed>(provider.listModels()).error,
                )
            }
        }
    }

    private fun providers(transport: ModelTransport): List<ModelProvider> = listOf(
        providerFor(ProviderType.OPENAI_COMPATIBLE, transport),
        providerFor(ProviderType.DEEPSEEK_COMPATIBLE, transport),
    )

    private fun providerFor(
        type: ProviderType,
        transport: ModelTransport,
        parsingDispatcher: CoroutineDispatcher? = null,
    ): ModelProvider {
        val configuration = ProviderConfiguration(
            id = "provider",
            type = type,
            displayName = type.name,
            baseUrl = "https://example.test/v1",
            credentialAlias = "candidate-alias",
            defaultModelId = null,
            enabled = true,
            capabilities = ModelCapabilities(streaming = true),
        )
        return when (type) {
            ProviderType.OPENAI_COMPATIBLE -> if (parsingDispatcher == null) OpenAiCompatibleProvider(configuration, transport)
                else OpenAiCompatibleProvider(configuration, transport, parsingDispatcher = parsingDispatcher)
            ProviderType.DEEPSEEK_COMPATIBLE -> if (parsingDispatcher == null) DeepSeekProvider(configuration, transport)
                else DeepSeekProvider(configuration, transport, parsingDispatcher = parsingDispatcher)
        }
    }

    private fun catalog(vararg entries: String): String = "{\"data\":[${entries.joinToString(",")}] }"
    private fun success(body: String): ModelTransport = response(
        TransportResponse.Success(flow { body.encodeToByteArray().asList().chunked(8191).forEach { emit(it.toByteArray()) } }),
    )
    private fun response(value: TransportResponse): ModelTransport = ModelTransport { flow { emit(value) } }

    private inline fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        return try { Locale.setDefault(locale); block() } finally { Locale.setDefault(previous) }
    }

    private companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_CATALOG_ENTRIES = 2_000
        const val MAX_ID_BYTES = 256
        const val MAX_NAME_BYTES = 512
        const val MAX_ERROR_BYTES = 64 * 1024
    }
}

private class ThreadRecordingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    @Volatile var lastThread: String = ""

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            lastThread = Thread.currentThread().name
            block.run()
        }
    }
}

private class CleanupTransport : ModelTransport {
    @Volatile var started = false
    @Volatile var cleaned = false

    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow {
        emit(TransportResponse.Success(flow {
            started = true
            try { awaitCancellation() } finally { cleaned = true }
        }))
    }
}

private class RequestRecordingDiscoveryTransport(private val body: String) : ModelTransport {
    lateinit var request: TransportRequest

    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow {
        this@RequestRecordingDiscoveryTransport.request = request
        emit(TransportResponse.Success(flow { emit(body.encodeToByteArray()) }))
    }
}
