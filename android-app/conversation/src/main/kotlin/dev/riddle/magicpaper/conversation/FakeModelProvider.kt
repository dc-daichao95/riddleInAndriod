package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelDescriptor
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderDescriptor
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeModelProvider(private val events: Flow<ModelEvent>) : ModelProvider {
    constructor(events: List<ModelEvent>) : this(events.asFlow())

    override val descriptor = ProviderDescriptor(
        ProviderType.OPENAI_COMPATIBLE,
        "Deterministic fake",
        ModelCapabilities(streaming = true),
    )

    override fun stream(request: ModelRequest): Flow<ModelEvent> = events

    override suspend fun listModels(): Result<List<ModelDescriptor>> = Result.success(
        listOf(ModelDescriptor("fake", "Fake", descriptor.capabilities)),
    )

    override suspend fun validate(configuration: ProviderConfiguration): ValidationResult = ValidationResult.Valid
}
