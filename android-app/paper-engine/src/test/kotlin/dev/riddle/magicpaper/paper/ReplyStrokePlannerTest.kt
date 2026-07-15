package dev.riddle.magicpaper.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CancellationException

class ReplyStrokePlannerTest {
    private val geometry = PageGeometry(
        pageWidthPx = 400,
        pageHeightPx = 300,
        safeBounds = SafePageBounds(20, 20, 380, 280),
    )

    @Test fun `plans latin cjk combining and punctuation without losing source`() {
        val source = "Magic e\u0301，魔法。"
        val plan = ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry)

        assertEquals(source, plan.accessibilityText)
        assertEquals(source, plan.pages.joinToString("") { source.substring(it.sourceStart, it.sourceEnd) })
        assertEquals(listOf("e\u0301"), plan.pages.flatMap { it.glyphs }.map { it.source }.filter { it.length > 1 })
        assertTrue(plan.pages.flatMap { it.glyphs }.any { it.font == ReplyFont.CJK })
    }

    @Test fun `unsupported zwj cluster creates one labeled box and keeps exact source`() {
        val source = "A👩‍🚀B"
        val plan = ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry)
        val unsupported = plan.pages.flatMap { it.glyphs }.single { it.font == ReplyFont.PLACEHOLDER }

        assertEquals("👩‍🚀", unsupported.source)
        assertEquals("U+1F469+200D+1F680", unsupported.label)
        assertEquals(source, plan.accessibilityText)
        assertTrue(unsupported.strokes.isNotEmpty())
    }

    @Test fun `pagination ranges are contiguous ordered and exactly reconstruct source`() {
        val source = "魔法 paper العربية — ".repeat(30)
        val pages = ReplyStrokePlanner(TestGlyphSource(), lineHeight = .12f, glyphAdvance = .08f)
            .plan(source, geometry).pages

        assertTrue(pages.size > 1)
        assertEquals(0, pages.first().sourceStart)
        assertEquals(source.length, pages.last().sourceEnd)
        pages.zipWithNext().forEach { (left, right) -> assertEquals(left.sourceEnd, right.sourceStart) }
        assertEquals(source, pages.joinToString("") { source.substring(it.sourceStart, it.sourceEnd) })
    }

    @Test fun `stream deltas split inside cluster append first wins without replay`() {
        val glyphSource = TestGlyphSource()
        val session = ReplyStrokePlanner(glyphSource).stream(geometry)
        assertTrue(session.append("e").pages.isEmpty())
        assertTrue(session.append("\u0301 and 👩").pages.flatMap { it.glyphs }.none { it.source == "👩" })
        val before = session.snapshot().pages.flatMap { it.glyphs }.map { it.id }
        session.append("‍")
        session.append("🚀!")
        val complete = session.finish()
        val after = complete.pages.flatMap { it.glyphs }

        assertEquals("e\u0301 and 👩‍🚀!", complete.accessibilityText)
        assertEquals(before, after.take(before.size).map { it.id })
        assertEquals(1, after.count { it.source == "e\u0301" })
        assertEquals(1, after.count { it.source == "👩‍🚀" })
        assertEquals(1, glyphSource.strokeCalls.getValue("e\u0301"))
    }

    @Test fun `variation selector and surrogate split remain one unsupported cluster`() {
        val stream = ReplyStrokePlanner(TestGlyphSource()).stream(geometry)
        val emoji = "😀️"
        stream.append(emoji.substring(0, 1))
        stream.append(emoji.substring(1, 2))
        stream.append(emoji.substring(2))
        val glyph = stream.finish().pages.flatMap { it.glyphs }.single()

        assertEquals(emoji, glyph.source)
        assertEquals(ReplyFont.PLACEHOLDER, glyph.font)
        assertEquals("U+1F600+FE0F", glyph.label)
    }

    @Test fun `boxed fallback visibly encodes its code point label`() {
        val planner = ReplyStrokePlanner(TestGlyphSource())
        val first = planner.plan("😀", geometry).pages.single().glyphs.single()
        val second = planner.plan("\uDBFF\uDFFF", geometry).pages.single().glyphs.single()

        assertTrue(first.strokes.size > 2)
        assertTrue(second.strokes.size > 2)
        assertTrue(first.strokes != second.strokes)
    }

    @Test fun `rtl paragraph keeps logical source order and deterministic normalized metadata`() {
        val source = "مرحبا بالعالم"
        val first = ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry).normalizedMetadata()
        val second = ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry).normalizedMetadata()

        assertEquals(first, second)
        assertEquals(source, ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry).accessibilityText)
    }

    @Test fun `newlines retain source ranges without drawing a fallback rune`() {
        val glyphs = ReplyStrokePlanner(TestGlyphSource()).plan("A\nB", geometry).pages.flatMap { it.glyphs }
        val newline = glyphs.single { it.source == "\n" }

        assertEquals(ReplyFont.LATIN, newline.font)
        assertTrue(newline.strokes.isEmpty())
    }

    @Test fun `cells clamp inside extremely narrow safe bounds`() {
        val narrow = PageGeometry(1_000, 1_000, SafePageBounds(100, 100, 110, 110))
        val glyph = ReplyStrokePlanner(TestGlyphSource()).plan("A", narrow).pages.single().glyphs.single()
        val bounds = narrow.normalizedSafeBounds

        assertTrue(glyph.originX >= bounds.left && glyph.originX + glyph.width <= bounds.right)
        assertTrue(glyph.originY >= bounds.top && glyph.originY + glyph.height <= bounds.bottom)
    }

    @Test fun `stream prefix metadata stays first wins when rtl strong text arrives`() {
        val session = ReplyStrokePlanner(TestGlyphSource()).stream(geometry)
        val before = session.append("A\n").normalizedMetadata()

        // The newline commits before the first glyph in its new paragraph. Its empty paragraph
        // must not acquire a default LTR direction before the Hebrew glyph is committed.
        session.append("א")
        val after = session.append("x").normalizedMetadata()
        val glyphs = after.flatMap { it.glyphs }
        val prefixGlyphCount = before.sumOf { it.glyphs.size }

        assertEquals(before.flatMap { it.glyphs }, glyphs.take(prefixGlyphCount))
        assertEquals(
            ReplyDirection.RIGHT_TO_LEFT,
            glyphs.single { it.sourceStart == 2 && it.sourceEnd == 3 }.direction,
        )
    }

    @Test fun `each paragraph owns direction while ranges remain contiguous`() {
        val plan = ReplyStrokePlanner(TestGlyphSource()).plan("A\nمرحبا", geometry)
        val glyphs = plan.pages.flatMap { it.glyphs }
        assertEquals(ReplyDirection.LEFT_TO_RIGHT, glyphs.first().direction)
        assertEquals(ReplyDirection.RIGHT_TO_LEFT, glyphs.first { it.source == "م" }.direction)
        glyphs.zipWithNext().forEach { (a, b) -> assertEquals(a.sourceEnd, b.sourceStart) }
    }

    @Test fun `neutral only stream paragraph deterministically defaults left to right`() {
        val session = ReplyStrokePlanner(TestGlyphSource()).stream(geometry)
        session.append("A\n")
        session.append("123")
        val plan = session.finish()

        assertEquals(
            ReplyDirection.LEFT_TO_RIGHT,
            plan.pages.flatMap { it.glyphs }.first { it.source == "1" }.direction,
        )
    }

    @Test fun `common punctuation and supported cross font fallback use cjk but shaping scripts do not`() {
        val source = FallbackGlyphSource()
        val glyphs = ReplyStrokePlanner(source).plan("，éم", geometry).pages.flatMap { it.glyphs }
        assertEquals(ReplyFont.CJK, glyphs[0].font)
        assertEquals(ReplyFont.CJK, glyphs[1].font)
        assertEquals(ReplyFont.PLACEHOLDER, glyphs[2].font)
    }

    @Test fun `arbitrarily long unsupported cluster keeps exact source with bounded visible label`() {
        val source = buildString { append("😀"); repeat(500) { append('\uFE0F') } }
        val plan = ReplyStrokePlanner(TestGlyphSource()).plan(source, geometry)
        val glyph = plan.pages.single().glyphs.single()
        assertEquals(source, plan.accessibilityText)
        assertTrue(checkNotNull(glyph.label).length <= 80)
        assertTrue(glyph.strokes.size <= 512)
    }
    @Test fun `long combining cluster is one source-preserving bounded placeholder without font work`() {
        val source = buildString { append('e'); repeat(20_000) { append('\u0301') } }
        val glyphSource = CountingGlyphSource()

        val glyph = ReplyStrokePlanner(glyphSource).plan(source, geometry).pages.single().glyphs.single()

        assertEquals(source, glyph.source)
        assertEquals(ReplyFont.PLACEHOLDER, glyph.font)
        assertTrue(checkNotNull(glyph.label).length <= 32)
        assertTrue(glyph.strokes.size <= 512)
        assertEquals(0, glyphSource.supportCalls)
        assertEquals(0, glyphSource.strokeCalls)
    }

    @Test fun `streamed long zwj cluster is retained exactly without repeated glyph work`() {
        val glyphSource = CountingGlyphSource()
        val stream = ReplyStrokePlanner(glyphSource).stream(geometry)
        stream.append("\uD83D\uDC69")
        repeat(10_000) { stream.append("\u200D") }
        val plan = stream.finish()

        assertEquals("\uD83D\uDC69" + "\u200D".repeat(10_000), plan.accessibilityText)
        assertEquals(1, plan.pages.single().glyphs.size)
        assertEquals(ReplyFont.PLACEHOLDER, plan.pages.single().glyphs.single().font)
        assertEquals(0, glyphSource.supportCalls)
        assertEquals(0, glyphSource.strokeCalls)
    }

    @Test fun `planner cancellation interrupts long cluster before glyph work`() {
        val glyphSource = CountingGlyphSource()
        var checks = 0
        val planner = ReplyStrokePlanner(
            glyphSource = glyphSource,
            cancellation = ReplyCancellationProbe { if (++checks > 3) throw CancellationException("stop") },
        )

        assertFailsWith<CancellationException> {
            planner.plan("e" + "\u0301".repeat(20_000), geometry)
        }
        assertEquals(0, glyphSource.strokeCalls)
    }

    @Test fun `cjk base with combining mark and fullwidth latin use pinned font candidates`() {
        val glyphs = ReplyStrokePlanner(CjkMarkGlyphSource()).plan("\u4E2D\u0301\uFF21", geometry)
            .pages.single().glyphs

        assertEquals(ReplyFont.CJK, glyphs[0].font)
        assertEquals(ReplyFont.LATIN, glyphs[1].font)
    }
}

private class FallbackGlyphSource : ReplyGlyphSource {
    override fun supports(font: ReplyFont, cluster: String) = when {
        cluster == "，" -> font == ReplyFont.CJK
        cluster == "é" -> font == ReplyFont.CJK
        cluster == "م" -> true
        else -> font == ReplyFont.LATIN
    }
    override fun strokes(font: ReplyFont, cluster: String) = listOf(listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 1f)))
}

private class TestGlyphSource : ReplyGlyphSource {
    val strokeCalls = mutableMapOf<String, Int>()

    override fun supports(font: ReplyFont, cluster: String): Boolean = when (font) {
        ReplyFont.LATIN -> cluster.codePoints().allMatch { it < 0x300 || it in 0x300..0x36f }
        ReplyFont.CJK -> cluster.codePoints().allMatch { it in 0x3000..0x9fff }
        ReplyFont.PLACEHOLDER -> false
    }

    override fun strokes(font: ReplyFont, cluster: String): List<List<ReplyPoint>> {
        strokeCalls[cluster] = strokeCalls.getOrDefault(cluster, 0) + 1
        return listOf(listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 1f)))
    }
}

private class CountingGlyphSource : ReplyGlyphSource {
    var supportCalls = 0
    var strokeCalls = 0
    override fun supports(font: ReplyFont, cluster: String): Boolean {
        supportCalls++
        return true
    }
    override fun strokes(font: ReplyFont, cluster: String): List<List<ReplyPoint>> {
        strokeCalls++
        return listOf(listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 1f)))
    }
}

private class CjkMarkGlyphSource : ReplyGlyphSource {
    override fun supports(font: ReplyFont, cluster: String): Boolean = when (cluster) {
        "\u4E2D\u0301" -> font == ReplyFont.CJK
        "\uFF21" -> font == ReplyFont.LATIN
        else -> false
    }
    override fun strokes(font: ReplyFont, cluster: String): List<List<ReplyPoint>> =
        listOf(listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 1f)))
}
