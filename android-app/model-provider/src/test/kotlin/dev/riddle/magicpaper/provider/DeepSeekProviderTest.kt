package dev.riddle.magicpaper.provider

import dev.riddle.magicpaper.model.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekProviderTest {
    @Test fun `reasoning content is a DeepSeek mapping difference`() = runBlocking {
        val transport = ModelTransport { flow { emit(TransportResponse.Success(flow { emit("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\ndata: [DONE]\n\n".encodeToByteArray()) })) } }
        val base = ProviderConfiguration("p", ProviderType.OPENAI_COMPATIBLE, "p", "https://example.test/v1", "alias", "m", true, ModelCapabilities(streaming = true))
        val request = ModelRequest("m", listOf(Message(MessageRole.USER, "hi")))
        assertFalse(OpenAiCompatibleProvider(base, transport).stream(request).toList().any { it is ModelEvent.ReasoningDelta })
        val deepSeek = DeepSeekProvider(base.copy(type = ProviderType.DEEPSEEK_COMPATIBLE), transport)
        assertTrue(deepSeek.descriptor.capabilities.reasoning)
        assertEquals(listOf(ModelEvent.ReasoningDelta("think"), ModelEvent.Completed(FinishReason.STOP)), deepSeek.stream(request).toList())
    }
}
