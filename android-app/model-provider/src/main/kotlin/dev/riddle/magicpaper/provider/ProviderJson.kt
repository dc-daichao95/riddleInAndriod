package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import org.json.JSONArray
import org.json.JSONObject

object ProviderJson {
    class ToolState {
        internal data class Partial(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder(), var started: Boolean = false, var completed: Boolean = false)
        internal val calls = mutableMapOf<Int, Partial>()
    }
    fun request(request: ModelRequest): String = JSONObject().apply {
        put("model", request.modelId); put("stream", true); put("stream_options", JSONObject().put("include_usage", true))
        put("messages", JSONArray(request.messages.map { JSONObject().put("role", it.role.name.lowercase()).put("content", it.text) }))
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        if (request.tools.isNotEmpty()) put("tools", JSONArray(request.tools.map { tool ->
            JSONObject().put("type", "function").put("function", JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", JSONObject(tool.inputSchema)))
        }))
    }.toString()

    fun events(payload: String, includeReasoning: Boolean = false, toolState: ToolState = ToolState()): List<ModelEvent> {
        val root = try { JSONObject(payload) } catch (error: Exception) { throw ProviderParseException("Malformed provider JSON", error) }
        val result = mutableListOf<ModelEvent>()
        try {
            val choices = root.optJSONArray("choices") ?: JSONArray()
            var completion: ModelEvent.Completed? = null
            for (index in 0 until choices.length()) {
                val choice = choices.getJSONObject(index)
                val delta = choice.optJSONObject("delta") ?: JSONObject()
                delta.optString("content").takeIf { it.isNotEmpty() }?.let { result += ModelEvent.TextDelta(it) }
                if (includeReasoning) delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { result += ModelEvent.ReasoningDelta(it) }
                // Tool calls are deliberately parsed as untrusted protocol data, but this layer never executes them.
                delta.optJSONArray("tool_calls")?.let { calls -> repeat(calls.length()) { callIndex ->
                    val call = calls.getJSONObject(callIndex)
                    val index = call.optInt("index", callIndex)
                    val partial = toolState.calls.getOrPut(index) { ToolState.Partial() }
                    call.optString("id").takeIf { it.isNotEmpty() }?.let { partial.id = it }
                    val id = partial.id ?: "index-$index"
                    val function = call.optJSONObject("function") ?: JSONObject()
                    val name = function.optString("name")
                    val arguments = function.optString("arguments")
                    if (name.isNotEmpty()) partial.name += name
                    if (!partial.started && partial.name.isNotEmpty() && arguments.isNotEmpty()) {
                        partial.started = true
                        result += ModelEvent.ToolCallStarted(id, partial.name)
                    }
                    if (arguments.isNotEmpty()) { partial.arguments.append(arguments); result += ModelEvent.ToolCallArgumentsDelta(id, arguments) }
                } }
                if (!choice.isNull("finish_reason")) completion = ModelEvent.Completed(finishReason(choice.getString("finish_reason")))
            }
            if (completion != null) toolState.calls.values.filter { !it.completed && it.id != null && it.name.isNotEmpty() }.forEach { partial ->
                if (!partial.started) {
                    partial.started = true
                    result += ModelEvent.ToolCallStarted(requireNotNull(partial.id), partial.name)
                }
                partial.completed = true
                result += ModelEvent.ToolCallCompleted(ToolCall(requireNotNull(partial.id), partial.name, partial.arguments.toString()))
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
