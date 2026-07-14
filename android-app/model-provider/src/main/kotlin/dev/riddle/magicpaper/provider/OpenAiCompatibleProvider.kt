package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

open class OpenAiCompatibleProvider(
    protected val configuration: ProviderConfiguration,
    private val transport: ModelTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ModelProvider {
    override val descriptor = ProviderDescriptor(configuration.type, configuration.displayName, configuration.capabilities)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = streamWith(configuration, request)

    private fun streamWith(profile: ProviderConfiguration, request: ModelRequest): Flow<ModelEvent> = flow {
        val transportRequest = TransportRequest(
            url = profile.baseUrl.trimEnd('/') + "/chat/completions",
            headers = mapOf("Accept" to "text/event-stream"),
            body = ProviderJson.request(request),
            credentialAlias = profile.credentialAlias,
        )
        try {
            transport.stream(transportRequest).collect { response ->
                when (response) {
                    is TransportResponse.HttpFailure -> {
                        val error = mapHttp(response)
                        emit(ModelEvent.Failed(error))
                    }
                    is TransportResponse.NetworkFailure -> emit(ModelEvent.Failed(ModelError.Network(response.message)))
                    TransportResponse.TimeoutFailure -> emit(ModelEvent.Failed(ModelError.Timeout))
                    TransportResponse.CancelledFailure -> emit(ModelEvent.Failed(ModelError.Cancelled))
                    is TransportResponse.Success -> collectSuccess(response.chunks) { emit(it) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { emit(ModelEvent.Failed(ModelError.Parsing(error.message))) }
    }

    private suspend fun collectSuccess(chunks: Flow<ByteArray>, emitEvent: suspend (ModelEvent) -> Unit) {
        val parser = SseParser()
        var completed = false
        var done = false
        val toolState = ProviderJson.ToolState()
        try {
            chunks.collect { chunk ->
                parser.feed(chunk).forEach { event -> when (event) {
                    is SseEvent.Data -> mapEvents(event.value, toolState).forEach { modelEvent ->
                        if (modelEvent is ModelEvent.Completed) {
                            if (!completed) emitEvent(modelEvent)
                            completed = true
                        } else emitEvent(modelEvent)
                    }
                    SseEvent.Done -> {
                        done = true
                        if (!completed) emitEvent(ModelEvent.Completed(FinishReason.STOP))
                        completed = true
                        throw DoneSignal
                    }
                    SseEvent.Truncated -> emitEvent(ModelEvent.Failed(ModelError.Parsing("Truncated SSE stream")))
                } }
            }
        } catch (_: DoneSignalException) { /* The protocol terminal marker cancels upstream collection. */ }
        if (!done && parser.finish().isNotEmpty()) emitEvent(ModelEvent.Failed(ModelError.Parsing("Truncated SSE stream")))
    }

    protected open fun mapEvents(payload: String, toolState: ProviderJson.ToolState): List<ModelEvent> = ProviderJson.events(payload, toolState = toolState)

    private suspend fun mapHttp(response: TransportResponse.HttpFailure): ModelError = withContext(parsingDispatcher) {
        when (response.statusCode) {
            401, 403 -> ModelError.Authentication(providerError(response.errorBody))
            429 -> ModelError.RateLimited(retryAfterMillis(response.headers))
            in 500..599 -> ModelError.Server(response.statusCode)
            else -> ModelError.InvalidRequest(providerError(response.errorBody))
        }
    }

    private fun retryAfterMillis(headers: Map<String, String>): Long? = retryPolicy.retryAfterMillis(headers.entries
        .firstOrNull { it.key.equals("Retry-After", true) }?.value)

    private fun providerError(body: String?): String? = runCatching { org.json.JSONObject(body.orEmpty()).optJSONObject("error")?.optString("message") }
        .getOrNull()?.takeIf { !it.isNullOrBlank() }

    override suspend fun listModels(): ModelDiscoveryResult {
        val request = TransportRequest(configuration.baseUrl.trimEnd('/') + "/models", mapOf("Accept" to "application/json"), "", configuration.credentialAlias, TransportMethod.GET)
        return try {
            var result: ModelDiscoveryResult? = null
            try {
                transport.stream(request).collect { response ->
                    result = when (response) {
                        is TransportResponse.Success -> discoverModels(response.chunks)
                        is TransportResponse.HttpFailure -> mapDiscoveryHttp(response)
                        is TransportResponse.NetworkFailure -> ModelDiscoveryResult.Failed(ModelError.Network(response.message))
                        TransportResponse.TimeoutFailure -> ModelDiscoveryResult.Failed(ModelError.Timeout)
                        TransportResponse.CancelledFailure -> ModelDiscoveryResult.Failed(ModelError.Cancelled)
                    }
                    throw DiscoveryResponseComplete
                }
            } catch (_: DiscoveryResponseCompleteException) {
                // A transport request has exactly one authoritative response.
            }
            result ?: ModelDiscoveryResult.Failed(ModelError.InvalidResponse("Missing model response"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DiscoveryBodyTooLargeException) {
            ModelDiscoveryResult.Failed(ModelError.ResponseTooLarge(MAX_DISCOVERY_BODY_BYTES))
        } catch (error: Exception) {
            ModelDiscoveryResult.Failed(ModelError.InvalidResponse(error.message))
        }
    }

    private suspend fun discoverModels(chunks: Flow<ByteArray>): ModelDiscoveryResult = withContext(parsingDispatcher) {
        val body = java.io.ByteArrayOutputStream()
        chunks.collect { chunk ->
            if (body.size().toLong() + chunk.size > MAX_DISCOVERY_BODY_BYTES) throw DiscoveryBodyTooLarge
            body.write(chunk)
        }
        val data = org.json.JSONObject(body.toByteArray().decodeToString(throwOnInvalidSequence = true)).getJSONArray("data")
        if (data.length() > MAX_DISCOVERY_ENTRIES) {
            return@withContext ModelDiscoveryResult.Failed(ModelError.ResponseTooLarge(MAX_DISCOVERY_ENTRIES))
        }
        val firstById = linkedMapOf<String, ModelDescriptor>()
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val id = item.getString("id").trim()
            if (id.isBlank()) return@withContext invalidDiscovery("Model ID is blank")
            if (id.encodeToByteArray().size > MAX_DISCOVERY_ID_BYTES) return@withContext invalidDiscovery("Model ID exceeds limit")
            val name = if (item.has("name")) item.getString("name") else id
            if (name.encodeToByteArray().size > MAX_DISCOVERY_NAME_BYTES) return@withContext invalidDiscovery("Model display name exceeds limit")
            firstById.putIfAbsent(id, ModelDescriptor(id, name, descriptor.capabilities))
        }
        ModelDiscoveryResult.Success(firstById.values.sortedBy(ModelDescriptor::id))
    }

    private fun invalidDiscovery(message: String) = ModelDiscoveryResult.Failed(ModelError.InvalidResponse(message))

    private suspend fun mapDiscoveryHttp(response: TransportResponse.HttpFailure): ModelDiscoveryResult = withContext(parsingDispatcher) {
        if (response.errorBodyLimitExceeded || response.errorBody.orEmpty().encodeToByteArray().size > MAX_DISCOVERY_ERROR_BYTES) {
            return@withContext ModelDiscoveryResult.Failed(ModelError.ResponseTooLarge(MAX_DISCOVERY_ERROR_BYTES))
        }
        when (response.statusCode) {
            404, 405, 501 -> ModelDiscoveryResult.Unsupported
            401 -> ModelDiscoveryResult.Failed(ModelError.Authentication(providerError(response.errorBody)))
            403 -> ModelDiscoveryResult.Failed(ModelError.Authorization(providerError(response.errorBody)))
            429 -> ModelDiscoveryResult.Failed(ModelError.RateLimited(retryAfterMillis(response.headers)))
            in 500..599 -> ModelDiscoveryResult.Failed(ModelError.Server(response.statusCode))
            else -> ModelDiscoveryResult.Failed(ModelError.InvalidRequest(providerError(response.errorBody)))
        }
    }

    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult {
        if (!configuration.enabled || configuration.type != descriptor.type) return ValidationResult.Invalid(ModelError.InvalidRequest())
        val minimal = ModelRequest(configuration.defaultModelId ?: return ValidationResult.Invalid(ModelError.InvalidRequest()), listOf(Message(MessageRole.USER, "ping")), maxOutputTokens = 1)
        return streamWith(configuration, minimal).let { events ->
            var failure: ModelError? = null
            var completed = false
            events.collect {
                if (it is ModelEvent.Failed) failure = it.error
                if (it is ModelEvent.Completed) completed = true
            }
            failure?.let { ValidationResult.Invalid(it) }
                ?: if (completed) ValidationResult.Valid
                else ValidationResult.Invalid(ModelError.Parsing("Validation response did not complete"))
        }
    }
}

private object DoneSignal : DoneSignalException()
private open class DoneSignalException : RuntimeException(null, null, false, false)
private object DiscoveryBodyTooLarge : DiscoveryBodyTooLargeException()
private open class DiscoveryBodyTooLargeException : RuntimeException(null, null, false, false)
private object DiscoveryResponseComplete : DiscoveryResponseCompleteException()
private open class DiscoveryResponseCompleteException : RuntimeException(null, null, false, false)

private const val MAX_DISCOVERY_BODY_BYTES = 2 * 1024 * 1024
private const val MAX_DISCOVERY_ENTRIES = 2_000
private const val MAX_DISCOVERY_ID_BYTES = 256
private const val MAX_DISCOVERY_NAME_BYTES = 512
private const val MAX_DISCOVERY_ERROR_BYTES = 64 * 1024
