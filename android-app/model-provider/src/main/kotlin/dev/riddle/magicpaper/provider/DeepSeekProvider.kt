package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType

class DeepSeekProvider(configuration: ProviderConfiguration, transport: ModelTransport, credentials: CredentialSource) :
    OpenAiCompatibleProvider(configuration.copy(type = ProviderType.DEEPSEEK_COMPATIBLE), transport, credentials)
