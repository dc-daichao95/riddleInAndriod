package dev.riddle.magicpaper.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTest {
    @Test fun `arbitrary chunks preserve utf8 and SSE framing`() {
        val input = ": comment\r\ndata: {\"text\":\"魔法\"}\r\ndata: second\r\n\r\ndata: [DONE]\n\n".encodeToByteArray()
        for (size in 1..3) {
            val parser = SseParser()
            val events = input.asList().chunked(size).flatMap { parser.feed(it.toByteArray()) }
            assertEquals(listOf(SseEvent.Data("{\"text\":\"魔法\"}\nsecond"), SseEvent.Done), events)
            assertEquals(emptyList(), parser.finish())
        }
    }

    @Test fun `finish reports a truncated event`() {
        val parser = SseParser()
        parser.feed("data: incomplete".encodeToByteArray())
        assertEquals(listOf(SseEvent.Truncated), parser.finish())
    }

    @Test fun `id and retry fields apply to the dispatched event`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent.Data("hello", id = "42", retryMillis = 1500)),
            parser.feed("id: 42\nretry: 1500\ndata: hello\n\n".encodeToByteArray()),
        )
    }

    @Test fun `retry reconnection value persists across dispatched events`() {
        val parser = SseParser()
        assertEquals(
            listOf(SseEvent.Data("one", retryMillis = 1500), SseEvent.Data("two", retryMillis = 1500)),
            parser.feed("retry: 1500\ndata: one\n\ndata: two\n\n".encodeToByteArray()),
        )
    }

    @Test fun `done terminates parser and ignores later bytes`() {
        val parser = SseParser()
        val events = parser.feed("data: [DONE]\n\ndata: later\n\n".encodeToByteArray())
        assertEquals(listOf(SseEvent.Done), events)
        assertEquals(emptyList(), parser.finish())
    }
}
