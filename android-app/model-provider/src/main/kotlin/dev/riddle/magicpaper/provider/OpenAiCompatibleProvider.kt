package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

open class OpenAiCompatibleProvider(
    protected val configuration: ProviderConfiguration,
    private val transport: ModelTransport,
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
                    is TransportResponse.HttpFailure -> emit(ModelEvent.Failed(mapHttp(response)))
                    is TransportResponse.NetworkFailure -> emit(ModelEvent.Failed(ModelError.Network(response.message)))
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

    private fun mapHttp(response: TransportResponse.HttpFailure): ModelError = when (response.statusCode) {
        401, 403 -> ModelError.Authentication(providerError(response.errorBody))
        429 -> ModelError.RateLimited(retryAfterMillis(response.headers))
        in 500..599 -> ModelError.Server(response.statusCode)
        else -> ModelError.InvalidRequest(providerError(response.errorBody))
    }

    private fun retryAfterMillis(headers: Map<String, String>): Long? = RetryPolicy().retryAfterMillis(headers.entries
        .firstOrNull { it.key.equals("Retry-After", true) }?.value)

    private fun providerError(body: String?): String? = runCatching { org.json.JSONObject(body.orEmpty()).optJSONObject("error")?.optString("message") }
        .getOrNull()?.takeIf { !it.isNullOrBlank() }

    override suspend fun listModels(): Result<List<ModelDescriptor>> = runCatching {
        val request = TransportRequest(configuration.baseUrl.trimEnd('/') + "/models", mapOf("Accept" to "application/json"), "", configuration.credentialAlias, TransportMethod.GET)
        var result: List<ModelDescriptor>? = null
        transport.stream(request).collect { response -> when (response) {
            is TransportResponse.Success -> {
                val bytes = mutableListOf<Byte>()
                response.chunks.collect { bytes += it.toList() }
                val data = org.json.JSONObject(bytes.toByteArray().decodeToString()).getJSONArray("data")
                result = (0 until data.length()).map { ModelDescriptor(data.getJSONObject(it).getString("id"), data.getJSONObject(it).getString("id"), descriptor.capabilities) }
            }
            is TransportResponse.HttpFailure -> throw ProviderOperationException(mapHttp(response))
            is TransportResponse.NetworkFailure -> throw ProviderOperationException(ModelError.Network(response.message))
        } }
        result ?: throw ProviderOperationException(ModelError.Parsing("Missing model response"))
    }

    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult {
        if (!configuration.enabled || configuration.type != descriptor.type) return ValidationResult.Invalid(ModelError.InvalidRequest())
        val minimal = ModelRequest(configuration.defaultModelId ?: return ValidationResult.Invalid(ModelError.InvalidRequest()), listOf(Message(MessageRole.USER, "ping")), maxOutputTokens = 1)
        return streamWith(configuration, minimal).let { events ->
            var failure: ModelError? = null
            events.collect { if (it is ModelEvent.Failed) failure = it.error }
            failure?.let { ValidationResult.Invalid(it) } ?: ValidationResult.Valid
        }
    }
}

class ProviderOperationException(val error: ModelError) : Exception("Provider operation failed")

private object DoneSignal : DoneSignalException()
private open class DoneSignalException : RuntimeException(null, null, false, false)
