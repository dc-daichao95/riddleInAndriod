package dev.riddle.magicpaper.provider

import java.io.ByteArrayOutputStream

sealed interface SseEvent {
    data class Data(val value: String) : SseEvent
    data object Done : SseEvent
    data object Truncated : SseEvent
}

class SseParser {
    private val line = ByteArrayOutputStream()
    private val data = mutableListOf<String>()

    fun feed(bytes: ByteArray): List<SseEvent> {
        val result = mutableListOf<SseEvent>()
        bytes.forEach { byte ->
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
                result += if (payload == "[DONE]") SseEvent.Done else SseEvent.Data(payload)
                data.clear()
            }
            return
        }
        if (value.startsWith(":")) return
        if (value == "data") data += ""
        else if (value.startsWith("data:")) data += value.substring(5).removePrefix(" ")
    }
}
