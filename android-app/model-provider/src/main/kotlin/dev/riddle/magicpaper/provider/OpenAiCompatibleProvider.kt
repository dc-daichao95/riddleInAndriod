package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

open class OpenAiCompatibleProvider(
    protected val configuration: ProviderConfiguration,
    private val transport: ModelTransport,
    private val credentials: CredentialSource,
) : ModelProvider {
    override val descriptor = ProviderDescriptor(configuration.type, configuration.displayName, configuration.capabilities)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val credential = credentials.get(configuration.credentialAlias)
        if (credential == null) { emit(ModelEvent.Failed(ModelError.Authentication())); return@flow }
        val transportRequest = TransportRequest(
            url = configuration.baseUrl.trimEnd('/') + "/chat/completions",
            headers = mapOf("Authorization" to "Bearer $credential", "Accept" to "text/event-stream"),
            body = ProviderJson.request(request),
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
        chunks.collect { chunk ->
            parser.feed(chunk).forEach { event ->
                when (event) {
                    is SseEvent.Data -> ProviderJson.events(event.value).forEach { emitEvent(it) }
                    SseEvent.Done -> Unit
                    SseEvent.Truncated -> emitEvent(ModelEvent.Failed(ModelError.Parsing("Truncated SSE stream")))
                }
            }
        }
        if (parser.finish().isNotEmpty()) emitEvent(ModelEvent.Failed(ModelError.Parsing("Truncated SSE stream")))
    }

    private fun mapHttp(response: TransportResponse.HttpFailure): ModelError = when (response.statusCode) {
        401, 403 -> ModelError.Authentication()
        429 -> ModelError.RateLimited(retryAfterMillis(response.headers))
        in 500..599 -> ModelError.Server(response.statusCode)
        else -> ModelError.InvalidRequest()
    }

    private fun retryAfterMillis(headers: Map<String, String>): Long? = headers.entries
        .firstOrNull { it.key.equals("Retry-After", true) }?.value?.toLongOrNull()?.times(1000)

    override suspend fun listModels(): Result<List<ModelDescriptor>> = Result.success(emptyList())
    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult =
        if (configuration.enabled && configuration.type == descriptor.type) ValidationResult.Valid
        else ValidationResult.Invalid(ModelError.InvalidRequest())
}
