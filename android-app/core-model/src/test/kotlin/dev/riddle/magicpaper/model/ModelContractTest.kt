package dev.riddle.magicpaper.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelContractTest {
    @Test
    fun `normalized point rejects coordinates outside the page`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(-0.01f, 0.5f, 0.1f) }
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(0.5f, 1.01f, 0.1f) }
    }

    @Test
    fun `normalized point rejects invalid radius`() {
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(0.5f, 0.5f, -0.01f) }
        assertFailsWith<IllegalArgumentException> { NormalizedPoint(0.5f, 0.5f, Float.NaN) }
    }

    @Test
    fun `provider profile rejects cleartext endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderConfiguration(
                id = "local",
                type = ProviderType.OPENAI_COMPATIBLE,
                displayName = "Unsafe",
                baseUrl = "http://example.test/v1",
                credentialAlias = "provider-local",
                defaultModelId = "model",
                enabled = true,
                capabilities = ModelCapabilities(streaming = true),
            )
        }
    }

    @Test
    fun `provider profile requires host without user info`() {
        assertFailsWith<IllegalArgumentException> { configuration("https:///v1") }
        assertFailsWith<IllegalArgumentException> { configuration("https://user@example.test/v1") }
    }

    private fun configuration(baseUrl: String) = ProviderConfiguration(
        id = "custom",
        type = ProviderType.OPENAI_COMPATIBLE,
        displayName = "Custom",
        baseUrl = baseUrl,
        credentialAlias = "provider-custom",
        defaultModelId = null,
        enabled = true,
        capabilities = ModelCapabilities(streaming = true),
    )
}
