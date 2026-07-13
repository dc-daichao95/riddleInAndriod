package dev.riddle.magicpaper.provider

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportPolicyTest {
    @Test fun `retry after parses seconds and HTTP date from injected clock`() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        assertEquals(2000, RetryPolicy(now = { now }).retryAfterMillis("2"))
        assertEquals(5000, RetryPolicy(now = { now }).retryAfterMillis("Mon, 13 Jul 2026 00:00:05 GMT"))
    }

    @Test fun `only transient failures are retry eligible`() {
        assertTrue(RetryPolicy().isEligible(TransportFailure.Timeout))
        assertTrue(RetryPolicy().isEligible(TransportFailure.Http(429)))
        assertTrue(RetryPolicy().isEligible(TransportFailure.Http(503)))
        assertFalse(RetryPolicy().isEligible(TransportFailure.Http(401)))
        assertFalse(RetryPolicy().isEligible(TransportFailure.Cancelled))
    }

    @Test fun `default OkHttp transport has finite complete timeout set`() {
        val client = OkHttpModelTransport.defaultClient()
        assertTrue(client.connectTimeoutMillis > 0)
        assertTrue(client.readTimeoutMillis > 0)
        assertTrue(client.writeTimeoutMillis > 0)
        assertTrue(client.callTimeoutMillis > 0)
    }
}
