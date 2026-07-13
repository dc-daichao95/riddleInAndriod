package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ModelEvent

class DeepSeekProvider(configuration: ProviderConfiguration, transport: ModelTransport, retryPolicy: RetryPolicy = RetryPolicy()) :
    OpenAiCompatibleProvider(
        configuration.copy(
            type = ProviderType.DEEPSEEK_COMPATIBLE,
            capabilities = configuration.capabilities.copy(reasoning = true),
        ),
        transport,
        retryPolicy,
    )
{
    override fun mapEvents(payload: String, toolState: ProviderJson.ToolState): List<ModelEvent> =
        ProviderJson.events(payload, includeReasoning = true, toolState = toolState)
}
