package dev.riddle.magicpaper.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.coroutines.resumeWithException

enum class TransportMethod { GET, POST }

data class TransportRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val credentialAlias: String,
    val method: TransportMethod = TransportMethod.POST,
) {
    init {
        val reserved = setOf("authorization", "proxy-authorization", "cookie")
        require(headers.keys.none { it.lowercase() in reserved }) { "credential-bearing headers are transport-owned" }
    }
    override fun toString(): String = "TransportRequest(url=$url, headerNames=${headers.keys}, body=<redacted>)"
}

sealed interface TransportResponse {
    data class Success(val chunks: Flow<ByteArray>) : TransportResponse
    data class HttpFailure(
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap(),
        val errorBody: String? = null,
        val errorBodyLimitExceeded: Boolean = false,
    ) : TransportResponse
    data class NetworkFailure(val message: String?) : TransportResponse
    data object TimeoutFailure : TransportResponse
    data object CancelledFailure : TransportResponse
}

fun interface ModelTransport { fun stream(request: TransportRequest): Flow<TransportResponse> }
fun interface CredentialSource { suspend fun get(alias: String): String? }

class OkHttpModelTransport(
    private val credentials: CredentialSource,
    private val client: OkHttpClient = defaultClient(),
    internal val callCreated: (okhttp3.Call) -> Unit = {},
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cancellationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ModelTransport {
    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow {
        val credential = credentials.get(request.credentialAlias)
        if (credential.isNullOrBlank()) { emit(TransportResponse.HttpFailure(401)); return@flow }
        val builder = Request.Builder().url(request.url).header("Authorization", "Bearer $credential")
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
        val httpRequest = when (request.method) {
            TransportMethod.GET -> builder.get()
            TransportMethod.POST -> builder.post(request.body.toRequestBody("application/json".toMediaType()))
        }.build()
        val call = client.newCall(httpRequest)
        callCreated(call)
        try {
            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (continuation.isActive) {
                            continuation.resume(response) { _, discardedResponse, _ -> discardedResponse.close() }
                        } else response.close()
                    }
                })
            }
            withCancellationWatcher(call, response) {
                if (!response.isSuccessful) {
                    val headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                    val (errorBody, errorBodyLimitExceeded) = withContext(blockingDispatcher) {
                        response.body.source().use { source ->
                            val exceeded = source.request(ERROR_BODY_LIMIT_BYTES + 1L)
                            val text = source.readByteString(minOf(ERROR_BODY_LIMIT_BYTES.toLong(), source.buffer.size)).utf8()
                            text.replace(credential, "[REDACTED]") to exceeded
                        }
                    }
                    response.close()
                    emit(TransportResponse.HttpFailure(response.code, headers, errorBody, errorBodyLimitExceeded))
                } else {
                    emit(TransportResponse.Success(flow {
                        response.use {
                            val source = it.body.source()
                            val buffer = Buffer()
                            while (currentCoroutineContext().isActive) {
                                val bytes = withContext(blockingDispatcher) {
                                    if (source.exhausted()) null else {
                                        val count = source.read(buffer, 8192)
                                        if (count > 0) buffer.readByteArray(count) else byteArrayOf()
                                    }
                                } ?: break
                                if (bytes.isNotEmpty()) emit(bytes)
                            }
                        }
                    }))
                }
            }
        } catch (error: SocketTimeoutException) {
            if (currentCoroutineContext().isActive) emit(TransportResponse.TimeoutFailure) else throw error
        } catch (error: IOException) {
            if (currentCoroutineContext().isActive) emit(TransportResponse.NetworkFailure(error.message)) else throw error
        } finally {
            call.cancel()
        }
    }

    private suspend fun withCancellationWatcher(
        call: okhttp3.Call,
        response: okhttp3.Response,
        block: suspend () -> Unit,
    ) = coroutineScope {
        val watcher = launch(cancellationDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
                response.close()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    companion object {
        private const val ERROR_BODY_LIMIT_BYTES = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(90)).build()
    }
}
