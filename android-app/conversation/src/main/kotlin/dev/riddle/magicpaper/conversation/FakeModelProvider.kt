package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelDescriptor
import dev.riddle.magicpaper.model.ModelDiscoveryResult
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderDescriptor
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow

class FakeModelProvider(
    private val eventFactory: (ModelRequest) -> Flow<ModelEvent>,
) : ModelProvider {
    constructor(events: List<ModelEvent>) : this({ events.asFlow() })
    constructor(events: Flow<ModelEvent>) : this({ events })

    private val requestTrace = mutableListOf<ModelRequest>()
    val recordedRequests: List<ModelRequest>
        get() = synchronized(requestTrace) { requestTrace.toList() }

    override val descriptor = ProviderDescriptor(
        ProviderType.OPENAI_COMPATIBLE,
        "Deterministic fake",
        ModelCapabilities(streaming = true),
    )

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        synchronized(requestTrace) { requestTrace += request }
        eventFactory(request).collect(::emit)
    }

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Success(
        listOf(ModelDescriptor("fake", "Fake", descriptor.capabilities)),
    )

    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult {
        val incompatible = when {
            !configuration.enabled -> "profile is disabled"
            configuration.type != descriptor.type -> "provider type does not match fake contract"
            !configuration.capabilities.streaming -> "streaming capability is required"
            configuration.defaultModelId.isNullOrBlank() -> "default model is required"
            else -> null
        }
        return if (incompatible == null) ValidationResult.Valid else ValidationResult.Invalid(
            dev.riddle.magicpaper.model.ModelError.InvalidRequest(incompatible),
        )
    }
}
