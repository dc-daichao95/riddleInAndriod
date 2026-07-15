package dev.riddle.magicpaper

import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelError
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.provider.DeepSeekProvider
import dev.riddle.magicpaper.provider.ModelTransport
import dev.riddle.magicpaper.provider.OpenAiCompatibleProvider
import dev.riddle.magicpaper.provider.ProviderJson
import dev.riddle.magicpaper.provider.TransportResponse
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ProviderAndroidJsonTest {
    private val configuration = ProviderConfiguration(
        "profile",
        ProviderType.OPENAI_COMPATIBLE,
        "Provider",
        "https://example.test/v1",
        "credential",
        "model",
        true,
        ModelCapabilities(streaming = true, reasoning = true, toolCalling = true),
    )

    @Test fun `Android JSON null content reasoning and tool fields emit no fragments`() {
        val payload = """{"choices":[{"delta":{"content":null,"reasoning_content":null,"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":null}}]},"finish_reason":null}]}"""

        assertEquals(emptyList(), ProviderJson.events(payload, includeReasoning = true))
    }

    @Test fun `Android JSON null provider error message remains absent for both adapters`() = runBlocking {
        val transport = ModelTransport {
            flow { emit(TransportResponse.HttpFailure(401, errorBody = "{\"error\":{\"message\":null}}")) }
        }
        val request = ModelRequest("model", listOf(Message(MessageRole.USER, "test")))
        val providers = listOf(
            OpenAiCompatibleProvider(configuration, transport),
            DeepSeekProvider(configuration.copy(type = ProviderType.DEEPSEEK_COMPATIBLE), transport),
        )

        providers.forEach { provider ->
            assertEquals(
                listOf(ModelEvent.Failed(ModelError.Authentication())),
                provider.stream(request).toList(),
            )
        }
    }
}
