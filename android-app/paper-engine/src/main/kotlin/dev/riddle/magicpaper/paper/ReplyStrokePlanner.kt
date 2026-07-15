package dev.riddle.magicpaper.paper

import java.util.Locale

enum class ReplyFont { LATIN, CJK, PLACEHOLDER }

enum class ReplyDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

data class ReplyPoint(val x: Float, val y: Float)

interface ReplyGlyphSource {
    fun supports(font: ReplyFont, cluster: String): Boolean
    fun strokes(font: ReplyFont, cluster: String): List<List<ReplyPoint>>
}

data class PlannedReplyGlyph(
    val id: String,
    val source: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val font: ReplyFont,
    val label: String?,
    val originX: Float,
    val originY: Float,
    val width: Float,
    val height: Float,
    val strokes: List<List<ReplyPoint>>,
    val direction: ReplyDirection,
)

data class ReplyPage(
    val index: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val direction: ReplyDirection,
    val glyphs: List<PlannedReplyGlyph>,
)

data class ReplyPlan(
    val accessibilityText: String,
    val pages: List<ReplyPage>,
) {
    fun normalizedMetadata(): List<ReplyPageMetadata> = pages.map { page ->
        ReplyPageMetadata(
            index = page.index,
            sourceStart = page.sourceStart,
            sourceEnd = page.sourceEnd,
            direction = page.direction,
            glyphs = page.glyphs.map { glyph ->
                ReplyGlyphMetadata(
                    glyph.id,
                    glyph.sourceStart,
                    glyph.sourceEnd,
                    glyph.font,
                    glyph.label,
                    glyph.originX,
                    glyph.originY,
                    glyph.width,
                    glyph.height,
                    glyph.direction,
                )
            },
        )
    }
}

data class ReplyPageMetadata(
    val index: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val direction: ReplyDirection,
    val glyphs: List<ReplyGlyphMetadata>,
)

data class ReplyGlyphMetadata(
    val id: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val font: ReplyFont,
    val label: String?,
    val originX: Float,
    val originY: Float,
    val width: Float,
    val height: Float,
    val direction: ReplyDirection,
)

/** Deterministic, Provider-neutral layout of reply graphemes into normalized paper paths. */
class ReplyStrokePlanner(
    private val glyphSource: ReplyGlyphSource,
    private val lineHeight: Float = .09f,
    private val glyphAdvance: Float = .055f,
    private val cancellation: ReplyCancellationProbe = ReplyCancellationProbe {},
) {
    private data class ResolvedGlyph(
        val font: ReplyFont,
        val label: String?,
        val strokes: List<List<ReplyPoint>>,
    )

    private val glyphCache = object : LinkedHashMap<String, ResolvedGlyph>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedGlyph>?): Boolean = size > 1_024
    }

    init {
        require(lineHeight > 0f)
        require(glyphAdvance > 0f)
    }

    fun plan(source: String, geometry: PageGeometry): ReplyPlan =
        layout(source, Unicode15GraphemeSegmenter.segment(source, cancellation), geometry)

    fun stream(geometry: PageGeometry): Stream = Stream(geometry)

    inner class Stream internal constructor(private val geometry: PageGeometry) {
        private val segmenter = Unicode15GraphemeSegmenter.Stream(cancellation)
        private val committed = ArrayList<String>()
        private val source = StringBuilder()
        private val paragraphDirections = mutableMapOf<Int, ReplyDirection>()

        fun append(delta: String): ReplyPlan {
            source.append(delta)
            committed += segmenter.append(delta)
            return snapshot()
        }

        fun snapshot(): ReplyPlan {
            val committedSource = committed.joinToString(separator = "")
            freezeParagraphDirections(committedSource)
            return layout(committedSource, committed, geometry, paragraphDirections).copy(accessibilityText = source.toString())
        }

        fun finish(): ReplyPlan {
            committed += segmenter.finish()
            return snapshot()
        }

        private fun freezeParagraphDirections(committedSource: String) {
            committedSource.split(Regex("\\r\\n|[\\r\\n]")).forEachIndexed { index, paragraph ->
                // A trailing newline opens an empty paragraph, whose eventual first strong
                // character must determine its direction. Once any non-newline content is
                // committed, paragraphDirection deterministically uses LTR for neutrals.
                if (paragraph.isNotEmpty()) {
                    paragraphDirections.putIfAbsent(index, paragraphDirection(paragraph))
                }
            }
        }
    }

    private fun layout(
        source: String,
        clusters: List<String>,
        geometry: PageGeometry,
        frozenParagraphDirections: Map<Int, ReplyDirection> = emptyMap(),
    ): ReplyPlan {
        if (clusters.isEmpty()) return ReplyPlan(source, emptyList())
        val bounds = geometry.normalizedSafeBounds
        val cellHeight = minOf(lineHeight, bounds.height)
        val cellWidth = minOf(glyphAdvance, bounds.width)
        var paragraph = 0
        var direction = frozenParagraphDirections[paragraph] ?: paragraphDirection(source.substringBefore('\n').substringBefore('\r'))
        val pageGlyphs = ArrayList<MutableList<PlannedReplyGlyph>>()
        pageGlyphs.add(ArrayList())
        var page = 0
        var x = if (direction == ReplyDirection.LEFT_TO_RIGHT) bounds.left else bounds.right - cellWidth
        var y = bounds.top
        var sourceOffset = 0

        fun nextLine() {
            x = if (direction == ReplyDirection.LEFT_TO_RIGHT) bounds.left else bounds.right - cellWidth
            y += cellHeight
        }

        fun nextPage() {
            page++
            pageGlyphs.add(ArrayList())
            x = if (direction == ReplyDirection.LEFT_TO_RIGHT) bounds.left else bounds.right - cellWidth
            y = bounds.top
        }

        clusters.forEach { cluster ->
            cancellation.check()
            val start = sourceOffset
            val end = start + cluster.length
            sourceOffset = end
            if (cluster == "\n" || cluster == "\r" || cluster == "\r\n") {
                pageGlyphs[page] += plannedGlyph(cluster, start, end, x, y, cellWidth, cellHeight, direction)
                nextLine()
                if (y + cellHeight > bounds.bottom) nextPage()
                paragraph++
                direction = frozenParagraphDirections[paragraph] ?: paragraphDirection(source.substring(end).substringBefore('\n').substringBefore('\r'))
                x = if (direction == ReplyDirection.LEFT_TO_RIGHT) bounds.left else bounds.right - cellWidth
                return@forEach
            }
            val exceedsLine = if (direction == ReplyDirection.LEFT_TO_RIGHT) {
                x + cellWidth > bounds.right
            } else {
                x < bounds.left
            }
            if (exceedsLine) nextLine()
            if (y + cellHeight > bounds.bottom) nextPage()
            pageGlyphs[page] += plannedGlyph(cluster, start, end, x, y, cellWidth, cellHeight, direction)
            x += if (direction == ReplyDirection.LEFT_TO_RIGHT) cellWidth else -cellWidth
        }

        val pages = pageGlyphs.filter { it.isNotEmpty() }.mapIndexed { index, glyphs ->
            ReplyPage(index, glyphs.first().sourceStart, glyphs.last().sourceEnd, glyphs.first().direction, glyphs)
        }
        return ReplyPlan(source, pages)
    }

    private fun plannedGlyph(
        cluster: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        direction: ReplyDirection,
    ): PlannedReplyGlyph {
        val resolved = if (exceedsRendererClusterLimit(cluster)) {
            boundedPlaceholder(cluster)
        } else synchronized(glyphCache) {
            glyphCache.getOrPut(cluster) { resolveGlyph(cluster) }
        }
        val localStrokes = resolved.strokes
        val strokes = localStrokes.map { stroke -> stroke.map { point -> ReplyPoint(x + point.x * width, y + point.y * height) } }
        return PlannedReplyGlyph(
            id = "g-$start-$end",
            source = cluster,
            sourceStart = start,
            sourceEnd = end,
            font = resolved.font,
            label = resolved.label,
            originX = x,
            originY = y,
            width = width,
            height = height,
            strokes = strokes,
            direction = direction,
        )
    }

    private fun resolveGlyph(cluster: String): ResolvedGlyph {
        if (cluster.isBlank()) return ResolvedGlyph(ReplyFont.LATIN, null, emptyList())
        val font = selectFont(cluster)
        return if (font == ReplyFont.PLACEHOLDER) boundedPlaceholder(cluster) else
            ResolvedGlyph(font, null, glyphSource.strokes(font, cluster))
    }

    private fun boundedPlaceholder(cluster: String): ResolvedGlyph {
        val label = codePointLabel(cluster)
        return ResolvedGlyph(ReplyFont.PLACEHOLDER, label, placeholderStrokes(label))
    }

    private fun selectFont(cluster: String): ReplyFont {
        val candidates = when {
            isUnsupportedShapingCluster(cluster) -> emptyList()
            isCjkCluster(cluster) -> listOf(ReplyFont.CJK, ReplyFont.LATIN)
            isLatinCluster(cluster) -> listOf(ReplyFont.LATIN, ReplyFont.CJK)
            isCommonCluster(cluster) -> listOf(ReplyFont.LATIN, ReplyFont.CJK)
            else -> emptyList()
        }
        return candidates.firstOrNull { glyphSource.supports(it, cluster) } ?: ReplyFont.PLACEHOLDER
    }

    private fun isLatinCluster(cluster: String): Boolean = clusterBase(cluster)?.let(::isLatinBase) == true

    private fun isCjkCluster(cluster: String): Boolean = clusterBase(cluster)?.let(::isCjkBase) == true

    private fun clusterBase(cluster: String): Int? {
        var offset = 0
        while (offset < cluster.length) {
            cancellation.check()
            val codePoint = cluster.codePointAt(offset)
            if (!isClusterMark(codePoint)) return codePoint
            offset += Character.charCount(codePoint)
        }
        return null
    }

    private fun isLatinBase(codePoint: Int): Boolean = codePoint <= 0x024f || codePoint in 0xff01..0xff5e

    private fun isCjkBase(codePoint: Int): Boolean =
        codePoint in 0x2e80..0x9fff || codePoint in 0xf900..0xfaff || codePoint in 0x20000..0x323af ||
            codePoint in 0x3000..0x303f

    private fun isClusterMark(codePoint: Int): Boolean =
        codePoint in 0x0300..0x036f || codePoint in 0x1ab0..0x1aff || codePoint in 0x1dc0..0x1dff ||
            codePoint in 0xfe00..0xfe0f || codePoint in 0xfe20..0xfe2f ||
            Character.getType(codePoint) in setOf(
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            )

    private fun isCommonCluster(cluster: String): Boolean = cluster.codePoints().allMatch { codePoint ->
        Character.isWhitespace(codePoint) || when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
            else -> codePoint in 0xfe00..0xfe0f
        }
    }

    private fun isUnsupportedShapingCluster(cluster: String): Boolean = cluster.codePoints().anyMatch { codePoint ->
        codePoint in 0x0590..0x08ff || codePoint in 0xfb1d..0xfdff || codePoint in 0xfe70..0xfeff ||
            codePoint in 0x1f000..0x1faff
    }

    private fun paragraphDirection(source: String): ReplyDirection {
        for (codePoint in source.codePoints().toArray()) {
            if (codePoint in 0x0590..0x08ff || codePoint in 0xfb1d..0xfdff || codePoint in 0xfe70..0xfeff) {
                return ReplyDirection.RIGHT_TO_LEFT
            }
            if (Character.isLetter(codePoint)) return ReplyDirection.LEFT_TO_RIGHT
        }
        return ReplyDirection.LEFT_TO_RIGHT
    }

    private fun codePointLabel(cluster: String): String {
        val output = StringBuilder("U+")
        var offset = 0
        var labels = 0
        while (offset < cluster.length && labels < MAX_VISIBLE_LABEL_CODE_POINTS) {
            cancellation.check()
            val codePoint = cluster.codePointAt(offset)
            val value = if (codePoint <= 0xffff) "%04X".format(Locale.ROOT, codePoint) else "%X".format(Locale.ROOT, codePoint)
            val addition = if (labels == 0) value else "+$value"
            if (output.length + addition.length > MAX_VISIBLE_LABEL_LENGTH) {
                if (output.length <= MAX_VISIBLE_LABEL_LENGTH - 3) output.append("+..")
                break
            }
            output.append(addition)
            offset += Character.charCount(codePoint)
            labels++
        }
        if (offset < cluster.length && output.length <= MAX_VISIBLE_LABEL_LENGTH - 3) output.append("+..")
        return output.toString()
    }

    private fun exceedsRendererClusterLimit(cluster: String): Boolean {
        var offset = 0
        var codePoints = 0
        while (offset < cluster.length && codePoints <= MAX_RENDER_CLUSTER_CODE_POINTS) {
            if ((codePoints and CANCELLATION_MASK) == 0) cancellation.check()
            offset += Character.charCount(cluster.codePointAt(offset))
            codePoints++
        }
        return codePoints > MAX_RENDER_CLUSTER_CODE_POINTS
    }

    private fun placeholderStrokes(label: String): List<List<ReplyPoint>> {
        val strokes = ArrayList<List<ReplyPoint>>()
        strokes += listOf(ReplyPoint(0f, 0f), ReplyPoint(1f, 0f), ReplyPoint(1f, 1f), ReplyPoint(0f, 1f), ReplyPoint(0f, 0f))
        val columns = label.length * 4 - 1
        val unit = .8f / columns.coerceAtLeast(1)
        label.forEachIndexed { characterIndex, character ->
            val bitmap = PLACEHOLDER_FONT.getValue(character)
            bitmap.forEachIndexed { row, bits ->
                repeat(3) { column ->
                    if (bits and (1 shl (2 - column)) != 0) {
                        val x = .1f + (characterIndex * 4 + column) * unit
                        val y = .23f + row * .135f
                        strokes += listOf(ReplyPoint(x, y), ReplyPoint(x + unit * .72f, y))
                    }
                }
            }
        }
        return strokes
    }

    private companion object {
        val PLACEHOLDER_FONT = mapOf(
            '0' to intArrayOf(0b111, 0b101, 0b101, 0b101, 0b111),
            '1' to intArrayOf(0b010, 0b110, 0b010, 0b010, 0b111),
            '2' to intArrayOf(0b111, 0b001, 0b111, 0b100, 0b111),
            '3' to intArrayOf(0b111, 0b001, 0b111, 0b001, 0b111),
            '4' to intArrayOf(0b101, 0b101, 0b111, 0b001, 0b001),
            '5' to intArrayOf(0b111, 0b100, 0b111, 0b001, 0b111),
            '6' to intArrayOf(0b111, 0b100, 0b111, 0b101, 0b111),
            '7' to intArrayOf(0b111, 0b001, 0b010, 0b010, 0b010),
            '8' to intArrayOf(0b111, 0b101, 0b111, 0b101, 0b111),
            '9' to intArrayOf(0b111, 0b101, 0b111, 0b001, 0b111),
            'A' to intArrayOf(0b010, 0b101, 0b111, 0b101, 0b101),
            'B' to intArrayOf(0b110, 0b101, 0b110, 0b101, 0b110),
            'C' to intArrayOf(0b111, 0b100, 0b100, 0b100, 0b111),
            'D' to intArrayOf(0b110, 0b101, 0b101, 0b101, 0b110),
            'E' to intArrayOf(0b111, 0b100, 0b110, 0b100, 0b111),
            'F' to intArrayOf(0b111, 0b100, 0b110, 0b100, 0b100),
            'U' to intArrayOf(0b101, 0b101, 0b101, 0b101, 0b111),
            '+' to intArrayOf(0b000, 0b010, 0b111, 0b010, 0b000),
            '.' to intArrayOf(0b000, 0b000, 0b000, 0b010, 0b010),
        )
        const val MAX_VISIBLE_LABEL_LENGTH = 32
        const val MAX_VISIBLE_LABEL_CODE_POINTS = 4
        /** Protects glyph shaping/rasterization from adversarial combining and ZWJ clusters. */
        const val MAX_RENDER_CLUSTER_CODE_POINTS = 256
        const val CANCELLATION_MASK = 0xFF
    }
}
