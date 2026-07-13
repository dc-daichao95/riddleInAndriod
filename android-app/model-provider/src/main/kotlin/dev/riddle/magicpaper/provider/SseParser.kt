package dev.riddle.magicpaper.provider

import java.io.ByteArrayOutputStream

sealed interface SseEvent {
    data class Data(val value: String, val id: String? = null, val retryMillis: Long? = null) : SseEvent
    data object Done : SseEvent
    data object Truncated : SseEvent
}

class SseParser {
    private val line = ByteArrayOutputStream()
    private val data = mutableListOf<String>()
    private var id: String? = null
    private var retryMillis: Long? = null
    private var terminated = false

    fun feed(bytes: ByteArray): List<SseEvent> {
        val result = mutableListOf<SseEvent>()
        if (terminated) return result
        bytes.forEach { byte ->
            if (terminated) return@forEach
            if (byte == '\n'.code.toByte()) {
                processLine(line.toByteArray().decodeToString().removeSuffix("\r"), result)
                line.reset()
            } else {
                line.write(byte.toInt())
            }
        }
        return result
    }

    fun finish(): List<SseEvent> =
        if (line.size() > 0 || data.isNotEmpty()) listOf(SseEvent.Truncated) else emptyList()

    private fun processLine(value: String, result: MutableList<SseEvent>) {
        if (value.isEmpty()) {
            if (data.isNotEmpty()) {
                val payload = data.joinToString("\n")
                result += if (payload == "[DONE]") SseEvent.Done.also { terminated = true } else SseEvent.Data(payload, id, retryMillis)
                data.clear()
            }
            return
        }
        if (value.startsWith(":")) return
        if (value.startsWith("id:")) id = value.substring(3).removePrefix(" ")
        else if (value.startsWith("retry:")) value.substring(6).trim().toLongOrNull()?.takeIf { it >= 0 }?.let { retryMillis = it }
        else if (value == "data") data += ""
        else if (value.startsWith("data:")) data += value.substring(5).removePrefix(" ")
    }
}
