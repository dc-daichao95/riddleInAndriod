package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReplyPlaybackTest {
    @Test
    fun `first page writes incrementally then lingers and dissolves before queued page`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)

        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        assertEquals(0, playback.state.writtenGlyphCount)

        playback.writeNextGlyph()
        assertEquals(1, playback.state.writtenGlyphCount)
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)

        playback.enqueue(page("B", index = 1, start = 1))
        playback.finalizeStream()
        assertEquals(ReplyPlaybackPhase.Lingering, playback.state.phase)
        playback.beginDissolve()
        (0..9).forEach { stage ->
            assertEquals(stage, playback.state.dissolveStage)
            playback.advanceDissolve()
        }

        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        assertEquals("B", requireNotNull(playback.state.page).glyphs.single().source)
        assertEquals(0, playback.state.writtenGlyphCount)
    }

    @Test
    fun `final page clears pixels and returns to complete after dissolve`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)
        playback.writeNextGlyph()
        playback.finalizeStream()
        playback.beginDissolve()
        repeat(10) { playback.advanceDissolve() }

        assertEquals(ReplyPlaybackPhase.Completed, playback.state.phase)
        assertEquals(null, playback.state.page)
        assertEquals(null, playback.state.dissolveStage)
    }

    @Test
    fun `reduced motion immediately exposes every queued page glyph`() {
        val playback = ReplyPlayback(page("A"), normalMotion = false)
        assertEquals(1, playback.state.writtenGlyphCount)
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)

        playback.enqueue(page("B", index = 1, start = 1))
        playback.finalizeStream()
        assertEquals(ReplyPlaybackPhase.Lingering, playback.state.phase)
        playback.beginDissolve()
        repeat(10) { playback.advanceDissolve() }

        assertEquals(1, playback.state.writtenGlyphCount)
        assertEquals(ReplyPlaybackPhase.Lingering, playback.state.phase)
    }

    @Test
    fun `streamed final page extension preserves already written prefix until stream finalizes`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)
        playback.writeNextGlyph()

        playback.replaceOpenPage(page("AB"))

        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        assertEquals(1, playback.state.writtenGlyphCount)
        assertEquals("A", requireNotNull(playback.state.page).glyphs.first().source)
        playback.beginDissolve()
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)

        playback.writeNextGlyph()
        assertEquals(2, playback.state.writtenGlyphCount)
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        playback.finalizeStream()
        assertEquals(ReplyPlaybackPhase.Lingering, playback.state.phase)
    }

    @Test
    fun `enqueue rejects invalid page order without changing a dissolving state`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)
        playback.writeNextGlyph()

        assertFailsWith<IllegalArgumentException> { playback.enqueue(page("", index = 1, start = 1)) }
        assertFailsWith<IllegalArgumentException> { playback.enqueue(page("B", index = 2, start = 1)) }
        assertFailsWith<IllegalArgumentException> { playback.enqueue(page("B", index = 1, start = 2)) }

        playback.enqueue(page("B", index = 1, start = 1))
        playback.finalizeStream()
        playback.beginDissolve()
        repeat(9) { playback.advanceDissolve() }
        assertEquals(9, playback.state.dissolveStage)
        playback.advanceDissolve()
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        assertEquals("B", requireNotNull(playback.state.page).glyphs.single().source)
    }

    @Test
    fun `late cancel after completion is a no op`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)
        playback.writeNextGlyph()
        playback.finalizeStream()
        playback.beginDissolve()
        repeat(10) { playback.advanceDissolve() }

        playback.cancel()

        assertEquals(ReplyPlaybackPhase.Completed, playback.state.phase)
    }

    @Test
    fun `reduced motion exposes every glyph of a multi glyph page only after finalization`() {
        val playback = ReplyPlayback(page("AB"), normalMotion = false)

        assertEquals(2, playback.state.writtenGlyphCount)
        assertEquals(ReplyPlaybackPhase.Writing, playback.state.phase)
        playback.finalizeStream()
        assertEquals(ReplyPlaybackPhase.Lingering, playback.state.phase)
    }

    @Test
    fun `enqueue rejects every malformed intermediate glyph range before changing state`() {
        val playback = ReplyPlayback(page("A"), normalMotion = true)
        val before = playback.state

        invalidIntermediateRangePages(text = "BCD", index = 1, start = 1).forEach { invalidPage ->
            assertFailsWith<IllegalArgumentException> { playback.enqueue(invalidPage) }
            assertEquals(before, playback.state)
        }
    }

    @Test
    fun `replace rejects every malformed intermediate glyph range before changing state`() {
        val playback = ReplyPlayback(page("AB"), normalMotion = true)
        val before = playback.state

        invalidIntermediateRangePages(text = "ABCD").forEach { invalidPage ->
            assertFailsWith<IllegalArgumentException> { playback.replaceOpenPage(invalidPage) }
            assertEquals(before, playback.state)
        }
    }

    @Test
    fun `reduced motion page replacement immediately exposes appended glyphs`() {
        val playback = ReplyPlayback(page("A"), normalMotion = false)

        playback.replaceOpenPage(page("AB"))

        assertEquals(2, playback.state.writtenGlyphCount)
        assertEquals("AB", requireNotNull(playback.state.page).glyphs.joinToString(separator = "") { it.source })
    }

    private fun page(text: String, index: Int = 0, start: Int = 0): ReplyPage {
        val glyphs = text.mapIndexed { offset, character ->
            PlannedReplyGlyph(
                id = "${start + offset}-${start + offset + 1}",
                source = character.toString(),
                sourceStart = start + offset,
                sourceEnd = start + offset + 1,
                font = ReplyFont.LATIN,
                label = null,
                originX = offset.toFloat(),
                originY = 0f,
                width = .1f,
                height = .1f,
                strokes = listOf(listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 1f))),
                direction = ReplyDirection.LEFT_TO_RIGHT,
            )
        }
        return ReplyPage(index, start, start + text.length, ReplyDirection.LEFT_TO_RIGHT, glyphs)
    }

    private fun invalidIntermediateRangePages(text: String, index: Int = 0, start: Int = 0): List<ReplyPage> {
        val valid = page(text, index, start)
        val prefix = valid.glyphs.dropLast(2)
        val intermediate = valid.glyphs[valid.glyphs.lastIndex - 1]
        val last = valid.glyphs.last()
        return listOf(
            valid.copy(glyphs = prefix + intermediate.copy(sourceEnd = intermediate.sourceStart) + last.copy(sourceStart = intermediate.sourceStart)),
            valid.copy(glyphs = prefix + intermediate.copy(sourceEnd = intermediate.sourceStart - 1) + last.copy(sourceStart = intermediate.sourceStart - 1)),
            valid.copy(glyphs = prefix + intermediate.copy(sourceEnd = valid.sourceEnd + 1) + last.copy(sourceStart = valid.sourceEnd + 1)),
        )
    }
}
