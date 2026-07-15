package dev.riddle.magicpaper.paper

fun interface ReplyCancellationProbe {
    fun check()
}

/** Platform-independent implementation of Unicode 15.0 extended grapheme clusters (UAX #29). */
object Unicode15GraphemeSegmenter {
    /**
     * Scans UTF-16 incrementally: it never allocates a code-point array proportional to the input.
     * The returned strings intentionally retain exact user text; renderer-specific work limits live in
     * [ReplyStrokePlanner], where an oversize cluster becomes one bounded placeholder.
     */
    fun segment(text: String, cancellation: ReplyCancellationProbe = ReplyCancellationProbe {}): List<String> {
        if (text.isEmpty()) return emptyList()
        val state = BoundaryState()
        val clusters = ArrayList<String>()
        var start = 0
        var offset = 0
        var inspected = 0
        while (offset < text.length) {
            if ((inspected++ and CANCELLATION_MASK) == 0) cancellation.check()
            val codePoint = text.codePointAt(offset)
            if (state.breakBefore(codePoint)) {
                clusters += text.substring(start, offset)
                start = offset
            }
            offset += Character.charCount(codePoint)
        }
        clusters += text.substring(start)
        return clusters
    }

    /**
     * Retains only the undecided final cluster. Each code point is classified once, avoiding the
     * previous re-segmentation of a growing Extend/ZWJ suffix on every network delta.
     */
    class Stream(private val cancellation: ReplyCancellationProbe = ReplyCancellationProbe {}) {
        private val pending = StringBuilder()
        private val state = BoundaryState()
        private var deferredHighSurrogate: Char? = null
        private var inspected = 0

        fun append(delta: String): List<String> {
            if (delta.isEmpty()) return emptyList()
            val completed = ArrayList<String>()
            var offset = 0
            deferredHighSurrogate?.let { high ->
                if (delta[0].isLowSurrogate()) {
                    accept(Character.toCodePoint(high, delta[0]), "$high${delta[0]}", completed)
                    offset = 1
                } else {
                    accept(high.code, high.toString(), completed)
                }
                deferredHighSurrogate = null
            }
            while (offset < delta.length) {
                if (offset == delta.lastIndex && delta[offset].isHighSurrogate()) {
                    deferredHighSurrogate = delta[offset]
                    break
                }
                val codePoint = delta.codePointAt(offset)
                val width = Character.charCount(codePoint)
                accept(codePoint, delta.substring(offset, offset + width), completed)
                offset += width
            }
            return completed
        }

        fun finish(): List<String> {
            val completed = ArrayList<String>()
            deferredHighSurrogate?.let { accept(it.code, it.toString(), completed) }
            deferredHighSurrogate = null
            if (pending.isNotEmpty()) completed += pending.toString()
            pending.clear()
            state.reset()
            return completed
        }

        private fun accept(codePoint: Int, text: String, completed: MutableList<String>) {
            if ((inspected++ and CANCELLATION_MASK) == 0) cancellation.check()
            if (state.breakBefore(codePoint) && pending.isNotEmpty()) {
                completed += pending.toString()
                pending.clear()
            }
            pending.append(text)
        }
    }

    private class BoundaryState {
        private var previous: GraphemeProperty? = null
        private var precedingRegionalIndicators = 0
        private var baseWasExtendedPictographic = false
        private var previousZwjFollowedExtendedPictographic = false

        fun breakBefore(codePoint: Int): Boolean {
            val current = Unicode15GraphemeData.property(codePoint)
            val didBreak = previous?.let { shouldBreak(it, current, codePoint) } ?: false
            if (didBreak) reset()
            consume(current, Unicode15GraphemeData.isExtendedPictographic(codePoint))
            return didBreak
        }

        fun reset() {
            previous = null
            precedingRegionalIndicators = 0
            baseWasExtendedPictographic = false
            previousZwjFollowedExtendedPictographic = false
        }

        private fun consume(current: GraphemeProperty, isExtendedPictographic: Boolean) {
            previousZwjFollowedExtendedPictographic = current == GraphemeProperty.ZWJ && baseWasExtendedPictographic
            if (current != GraphemeProperty.EXTEND && current != GraphemeProperty.ZWJ) {
                baseWasExtendedPictographic = isExtendedPictographic
            }
            precedingRegionalIndicators = if (current == GraphemeProperty.REGIONAL_INDICATOR) precedingRegionalIndicators + 1 else 0
            previous = current
        }

        private fun shouldBreak(previous: GraphemeProperty, current: GraphemeProperty, currentCodePoint: Int): Boolean {
        if (previous == GraphemeProperty.CR && current == GraphemeProperty.LF) return false // GB3
        if (previous in CONTROL_PROPERTIES || current in CONTROL_PROPERTIES) return true // GB4, GB5
        if (previous == GraphemeProperty.L && current in setOf(GraphemeProperty.L, GraphemeProperty.V, GraphemeProperty.LV, GraphemeProperty.LVT)) return false // GB6
        if (previous in setOf(GraphemeProperty.LV, GraphemeProperty.V) && current in setOf(GraphemeProperty.V, GraphemeProperty.T)) return false // GB7
        if (previous in setOf(GraphemeProperty.LVT, GraphemeProperty.T) && current == GraphemeProperty.T) return false // GB8
        if (current in setOf(GraphemeProperty.EXTEND, GraphemeProperty.ZWJ)) return false // GB9
        if (current == GraphemeProperty.SPACING_MARK) return false // GB9a
        if (previous == GraphemeProperty.PREPEND) return false // GB9b
        if (Unicode15GraphemeData.isExtendedPictographic(currentCodePoint) && previous == GraphemeProperty.ZWJ && previousZwjFollowedExtendedPictographic) return false // GB11
        if (previous == GraphemeProperty.REGIONAL_INDICATOR && current == GraphemeProperty.REGIONAL_INDICATOR) {
            if (precedingRegionalIndicators % 2 == 1) return false // GB12, GB13
        }
        return true // GB999
    }
    }

    private val CONTROL_PROPERTIES = setOf(GraphemeProperty.CONTROL, GraphemeProperty.CR, GraphemeProperty.LF)
    private const val CANCELLATION_MASK = 0xFF
}

internal enum class GraphemeProperty {
    OTHER, CR, LF, CONTROL, EXTEND, ZWJ, REGIONAL_INDICATOR, PREPEND, SPACING_MARK, L, V, T, LV, LVT,
}

private object Unicode15GraphemeData {
    fun property(codePoint: Int): GraphemeProperty = rangeValue(
        ranges = Unicode15GraphemeTables.breakRanges,
        stride = 3,
        codePoint = codePoint,
    )?.let(GraphemeProperty.entries::get) ?: GraphemeProperty.OTHER

    fun isExtendedPictographic(codePoint: Int): Boolean = rangeValue(
        ranges = Unicode15GraphemeTables.extendedPictographicRanges,
        stride = 2,
        codePoint = codePoint,
    ) != null

    private fun rangeValue(ranges: IntArray, stride: Int, codePoint: Int): Int? {
        var low = 0
        var high = ranges.size / stride - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val offset = middle * stride
            when {
                codePoint < ranges[offset] -> high = middle - 1
                codePoint > ranges[offset + 1] -> low = middle + 1
                else -> return if (stride == 3) ranges[offset + 2] else 1
            }
        }
        return null
    }
}
