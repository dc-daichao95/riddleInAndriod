package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderJson {
    fun request(request: ModelRequest): String = JSONObject().apply {
        put("model", request.modelId); put("stream", true); put("stream_options", JSONObject().put("include_usage", true))
        put("messages", JSONArray(request.messages.map { JSONObject().put("role", it.role.name.lowercase()).put("content", it.text) }))
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        if (request.tools.isNotEmpty()) put("tools", JSONArray(request.tools.map { tool ->
            JSONObject().put("type", "function").put("function", JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", JSONObject(tool.inputSchema)))
        }))
    }.toString()

    fun events(payload: String): List<ModelEvent> {
        val root = try { JSONObject(payload) } catch (error: Exception) { throw ProviderParseException("Malformed provider JSON", error) }
        val result = mutableListOf<ModelEvent>()
        try {
            val choices = root.optJSONArray("choices") ?: JSONArray()
            var completion: ModelEvent.Completed? = null
            for (index in 0 until choices.length()) {
                val choice = choices.getJSONObject(index)
                val delta = choice.optJSONObject("delta") ?: JSONObject()
                delta.optString("content").takeIf { it.isNotEmpty() }?.let { result += ModelEvent.TextDelta(it) }
                delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { result += ModelEvent.ReasoningDelta(it) }
                // Tool calls are deliberately parsed as untrusted protocol data, but this layer never executes them.
                delta.optJSONArray("tool_calls")?.let { calls -> repeat(calls.length()) { calls.getJSONObject(it) } }
                if (!choice.isNull("finish_reason")) completion = ModelEvent.Completed(finishReason(choice.getString("finish_reason")))
            }
            root.optJSONObject("usage")?.let { usage -> result += ModelEvent.UsageUpdated(TokenUsage(usage.optLong("prompt_tokens"), usage.optLong("completion_tokens"))) }
            completion?.let { result += it }
        } catch (error: Exception) { throw ProviderParseException("Malformed provider event", error) }
        return result
    }

    private fun finishReason(value: String) = when (value) {
        "stop", "tool_calls" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }
}

internal class ProviderParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
