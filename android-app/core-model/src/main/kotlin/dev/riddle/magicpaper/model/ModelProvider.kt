package dev.riddle.magicpaper.model

import kotlinx.coroutines.flow.Flow

interface ModelProvider {
    val descriptor: ProviderDescriptor

    fun stream(request: ModelRequest): Flow<ModelEvent>

    suspend fun listModels(): Result<List<ModelDescriptor>>

    suspend fun validate(configuration: ProviderConfiguration): ValidationResult
}
