package dev.riddle.magicpaper.conversation

import app.cash.turbine.test
import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelDiscoveryResult
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ProviderConfiguration
import dev.riddle.magicpaper.model.ProviderType
import dev.riddle.magicpaper.model.ValidationResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeModelProviderTest {
    @Test fun `descriptor models and compatible configuration form a meaningful contract`() = runTest {
        val provider = FakeModelProvider(emptyList())
        assertEquals("Deterministic fake", provider.descriptor.displayName)
        assertTrue(provider.descriptor.capabilities.streaming)
        assertEquals(listOf("fake"), assertIs<ModelDiscoveryResult.Success>(provider.listModels()).models.map { it.id })
        assertIs<ValidationResult.Valid>(provider.validate(configuration()))
    }

    @Test fun `validation rejects disabled mismatched or nonstreaming configuration`() = runTest {
        val provider = FakeModelProvider(emptyList())
        assertIs<ValidationResult.Invalid>(provider.validate(configuration(enabled = false)))
        assertIs<ValidationResult.Invalid>(provider.validate(configuration(type = ProviderType.DEEPSEEK_COMPATIBLE)))
        assertIs<ValidationResult.Invalid>(provider.validate(configuration(capabilities = ModelCapabilities())))
    }

    @Test fun `stream factory is cold per request and records each collection`() = runTest {
        var factoryCalls = 0
        val provider = FakeModelProvider { request ->
            factoryCalls++
            flowOf(ModelEvent.TextDelta(request.modelId))
        }
        val first = request("one")
        val second = request("two")
        provider.stream(first).test { assertEquals(ModelEvent.TextDelta("one"), awaitItem()); awaitComplete() }
        provider.stream(second).test { assertEquals(ModelEvent.TextDelta("two"), awaitItem()); awaitComplete() }
        provider.stream(first).test { assertEquals(ModelEvent.TextDelta("one"), awaitItem()); awaitComplete() }
        assertEquals(3, factoryCalls)
        assertEquals(listOf(first, second, first), provider.recordedRequests)
    }

    private fun request(model: String) = ModelRequest(model, listOf(Message(MessageRole.USER, "q")))

    private fun configuration(
        enabled: Boolean = true,
        type: ProviderType = ProviderType.OPENAI_COMPATIBLE,
        capabilities: ModelCapabilities = ModelCapabilities(streaming = true),
    ) = ProviderConfiguration("fake", type, "Fake", "https://example.test", "alias", "fake", enabled, capabilities)
}
