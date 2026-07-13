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
}
