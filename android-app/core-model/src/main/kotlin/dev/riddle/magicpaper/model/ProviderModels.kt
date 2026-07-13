package dev.riddle.magicpaper.model

import java.net.URI

enum class ProviderType {
    OPENAI_COMPATIBLE,
    DEEPSEEK_COMPATIBLE,
}

data class ModelCapabilities(
    val streaming: Boolean = false,
    val vision: Boolean = false,
    val toolCalling: Boolean = false,
    val structuredOutput: Boolean = false,
    val reasoning: Boolean = false,
    val systemMessages: Boolean = false,
)

data class ProviderConfiguration(
    val id: String,
    val type: ProviderType,
    val displayName: String,
    val baseUrl: String,
    val credentialAlias: String,
    val defaultModelId: String?,
    val enabled: Boolean,
    val capabilities: ModelCapabilities,
) {
    init {
        val endpoint = runCatching { URI(baseUrl) }
            .getOrElse { throw IllegalArgumentException("baseUrl must be a valid URI", it) }
        require(endpoint.scheme.equals("https", ignoreCase = true)) { "baseUrl must use HTTPS" }
        require(!endpoint.host.isNullOrBlank()) { "baseUrl must include a host" }
        require(endpoint.userInfo == null) { "baseUrl must not include user-info" }
    }
}

data class ProviderDescriptor(
    val type: ProviderType,
    val displayName: String,
    val capabilities: ModelCapabilities,
)

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: ModelCapabilities,
)

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

data class Message(
    val role: MessageRole,
    val text: String,
    val imageDataUrl: String? = null,
)

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data class JsonSchema(val schema: String) : ResponseFormat
}

data class ReasoningOptions(
    val effort: String? = null,
)

data class ModelRequest(
    val modelId: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val responseFormat: ResponseFormat? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningOptions? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

enum class FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    UNKNOWN,
}

sealed interface ModelError {
    data class Authentication(val message: String? = null) : ModelError
    data class InvalidRequest(val message: String? = null) : ModelError
    data class RateLimited(val retryAfterMillis: Long? = null) : ModelError
    data class Server(val statusCode: Int) : ModelError
    data class Network(val message: String? = null) : ModelError
    data class Parsing(val message: String? = null) : ModelError
    data object Cancelled : ModelError
}

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class ReasoningDelta(val text: String) : ModelEvent
    data class UsageUpdated(val usage: TokenUsage) : ModelEvent
    data class ToolCallStarted(val id: String, val name: String) : ModelEvent
    data class ToolCallArgumentsDelta(val id: String, val json: String) : ModelEvent
    data class ToolCallCompleted(val call: ToolCall) : ModelEvent
    data class Completed(val reason: FinishReason) : ModelEvent
    data class Failed(val error: ModelError) : ModelEvent
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val error: ModelError) : ValidationResult
}
