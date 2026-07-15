package dev.riddle.magicpaper.paper

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Unicode15GraphemeSegmenterTest {
    @Test fun `passes every official Unicode 15 grapheme break conformance case`() {
        val repository = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { it.resolve("AGENTS.md").isFile }
        val fixture = repository.resolve("android-app/paper-engine/src/test/resources/unicode/15.0.0/GraphemeBreakTest.txt")
        assertTrue(fixture.isFile, "official pinned GraphemeBreakTest.txt is missing")
        var cases = 0
        fixture.forEachLine { raw ->
            val body = raw.substringBefore('#').trim()
            if (body.isEmpty()) return@forEachLine
            val tokens = body.split(Regex("\\s+"))
            val codePoints = tokens.filter { it != "÷" && it != "×" }.map { it.toInt(16) }
            val text = buildString { codePoints.forEach(::appendCodePoint) }
            val expected = mutableListOf<String>()
            var current = StringBuilder()
            tokens.forEach { token ->
                when (token) {
                    "÷" -> if (current.isNotEmpty()) { expected += current.toString(); current = StringBuilder() }
                    "×" -> Unit
                    else -> current.appendCodePoint(token.toInt(16))
                }
            }
            if (current.isNotEmpty()) expected += current.toString()
            assertEquals(expected, Unicode15GraphemeSegmenter.segment(text), "case: $body")
            cases++
        }
        assertTrue(cases > 500)
    }

    @Test fun `streaming holds uncertain final cluster until next boundary or finish`() {
        val stream = Unicode15GraphemeSegmenter.Stream()
        assertEquals(emptyList(), stream.append("e"))
        assertEquals(emptyList(), stream.append("\u0301"))
        assertEquals(listOf("e\u0301"), stream.append("x"))
        assertEquals(listOf("x"), stream.finish())
    }
}
