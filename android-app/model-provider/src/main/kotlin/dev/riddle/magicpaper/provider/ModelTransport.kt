package dev.riddle.magicpaper.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException

data class TransportRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
) {
    override fun toString(): String = "TransportRequest(url=$url, headerNames=${headers.keys}, body=<redacted>)"
}

sealed interface TransportResponse {
    data class Success(val chunks: Flow<ByteArray>) : TransportResponse
    data class HttpFailure(val statusCode: Int, val headers: Map<String, String> = emptyMap()) : TransportResponse
    data class NetworkFailure(val message: String?) : TransportResponse
}

fun interface ModelTransport { fun stream(request: TransportRequest): Flow<TransportResponse> }
fun interface CredentialSource { suspend fun get(alias: String): String? }

class OkHttpModelTransport(private val client: OkHttpClient = OkHttpClient()) : ModelTransport {
    override fun stream(request: TransportRequest): Flow<TransportResponse> = flow {
        val httpRequest = Request.Builder().url(request.url)
            .post(request.body.toRequestBody("application/json".toMediaType()))
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = client.newCall(httpRequest)
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            if (!response.isSuccessful) {
                val headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                response.close()
                emit(TransportResponse.HttpFailure(response.code, headers))
            } else {
                emit(TransportResponse.Success(flow {
                    response.use {
                        val source = it.body.source()
                        val buffer = Buffer()
                        while (currentCoroutineContext().isActive && !source.exhausted()) {
                            val count = source.read(buffer, 8192)
                            if (count > 0) emit(buffer.readByteArray(count))
                        }
                    }
                }))
            }
        } catch (error: IOException) {
            if (currentCoroutineContext().isActive) emit(TransportResponse.NetworkFailure(error.message)) else throw error
        } finally {
            call.cancel()
        }
    }
}
