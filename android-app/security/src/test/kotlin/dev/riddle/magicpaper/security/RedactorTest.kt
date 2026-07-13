package dev.riddle.magicpaper.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactorTest {
    @Test fun `redacts raw key and authorization header values`() {
        val rawKey = "sk-live-do-not-leak"
        val input = "request failed Authorization: Bearer $rawKey api_key=$rawKey"

        val redacted = Redactor.redact(input, secrets = setOf(rawKey))

        assertFalse(redacted.contains(rawKey))
        assertFalse(redacted.contains("Bearer"))
        assertEquals("request failed Authorization: [REDACTED] api_key=[REDACTED]", redacted)
    }

    @Test fun `redacts authorization values regardless of casing`() {
        val redacted = Redactor.redact("AUTHORIZATION=Token abc123\nOther: safe")

        assertEquals("AUTHORIZATION=[REDACTED]\nOther: safe", redacted)
    }
}
