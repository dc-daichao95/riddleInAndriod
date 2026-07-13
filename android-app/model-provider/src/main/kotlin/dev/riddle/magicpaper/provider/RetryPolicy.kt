package dev.riddle.magicpaper.provider

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface TransportFailure {
    data object Timeout : TransportFailure
    data object Network : TransportFailure
    data object Cancelled : TransportFailure
    data class Http(val status: Int) : TransportFailure
}

class RetryPolicy(private val now: () -> Instant = Instant::now) {
    fun isEligible(failure: TransportFailure): Boolean = when (failure) {
        TransportFailure.Timeout, TransportFailure.Network -> true
        is TransportFailure.Http -> failure.status == 429 || failure.status in 500..599
        TransportFailure.Cancelled -> false
    }

    fun retryAfterMillis(value: String?): Long? {
        value ?: return null
        value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) * 1000 }
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()?.let { (it.toEpochMilli() - now().toEpochMilli()).coerceAtLeast(0) }
    }
}
