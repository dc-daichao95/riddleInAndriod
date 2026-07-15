package dev.riddle.magicpaper.paper

/**
 * Pure, generation-owned reply-page playback state. Time ownership stays with the
 * lifecycle layer: it waits with MonotonicDeadline, then calls the matching transition.
 */
enum class ReplyPlaybackPhase { Writing, Lingering, Dissolving, Completed, Cancelled }

data class ReplyPlaybackState(
    val phase: ReplyPlaybackPhase,
    val page: ReplyPage?,
    val writtenGlyphCount: Int,
    val dissolveStage: Int?,
)

class ReplyPlayback(initialPage: ReplyPage, private val normalMotion: Boolean) {
    private val queuedPages = ArrayDeque<ReplyPage>()
    private var streamFinalized = false

    var state: ReplyPlaybackState = initialState(requireValidPage(initialPage), normalMotion)
        private set

    fun enqueue(page: ReplyPage) {
        if (state.phase in setOf(ReplyPlaybackPhase.Completed, ReplyPlaybackPhase.Cancelled)) return
        check(!streamFinalized) { "Cannot enqueue after the stream has finalized" }
        requireValidPage(page)
        val predecessor = queuedPages.lastOrNull() ?: checkNotNull(state.page)
        require(page.index == predecessor.index + 1) { "Pages must have contiguous indexes" }
        require(page.sourceStart == predecessor.sourceEnd) { "Pages must have contiguous source ranges" }
        queuedPages.addLast(page)
    }

    /** Replaces the still-open final page after a later provider delta extends it. */
    fun replaceOpenPage(page: ReplyPage) {
        if (state.phase != ReplyPlaybackPhase.Writing || streamFinalized) return
        requireValidPage(page)
        val current = checkNotNull(state.page)
        require(page.index == current.index && page.sourceStart == current.sourceStart) {
            "An open page can only be replaced by its own extension"
        }
        require(page.glyphs.size >= current.glyphs.size) { "An open page cannot shrink" }
        require(page.glyphs.take(current.glyphs.size) == current.glyphs) {
            "An open page extension must preserve its glyph prefix"
        }
        state = state.copy(
            page = page,
            writtenGlyphCount = if (normalMotion) state.writtenGlyphCount else page.glyphs.size,
        )
    }

    /** Allows the completed final page to begin its linger interval. */
    fun finalizeStream() {
        if (state.phase != ReplyPlaybackPhase.Writing || streamFinalized) return
        streamFinalized = true
        val page = checkNotNull(state.page)
        if (state.writtenGlyphCount == page.glyphs.size) {
            state = state.copy(phase = ReplyPlaybackPhase.Lingering)
        }
    }

    fun writeNextGlyph() {
        if (state.phase != ReplyPlaybackPhase.Writing) return
        val page = checkNotNull(state.page)
        val written = (state.writtenGlyphCount + 1).coerceAtMost(page.glyphs.size)
        state = state.copy(
            writtenGlyphCount = written,
            phase = if (streamFinalized && written == page.glyphs.size) ReplyPlaybackPhase.Lingering else ReplyPlaybackPhase.Writing,
        )
    }

    fun beginDissolve() {
        if (state.phase == ReplyPlaybackPhase.Lingering) {
            state = state.copy(phase = ReplyPlaybackPhase.Dissolving, dissolveStage = 0)
        }
    }

    fun advanceDissolve() {
        if (state.phase != ReplyPlaybackPhase.Dissolving) return
        val stage = checkNotNull(state.dissolveStage)
        if (stage < LAST_DISSOLVE_STAGE) {
            state = state.copy(dissolveStage = stage + 1)
            return
        }
        val nextPage = queuedPages.removeFirstOrNull()
        state = if (nextPage == null) {
            ReplyPlaybackState(ReplyPlaybackPhase.Completed, null, 0, null)
        } else {
            initialState(nextPage, normalMotion)
        }
    }

    fun cancel() {
        if (state.phase in setOf(ReplyPlaybackPhase.Completed, ReplyPlaybackPhase.Cancelled)) return
        queuedPages.clear()
        state = ReplyPlaybackState(ReplyPlaybackPhase.Cancelled, null, 0, null)
    }

    private fun initialState(page: ReplyPage, normalMotion: Boolean): ReplyPlaybackState {
        val written = if (normalMotion) 0 else page.glyphs.size
        return ReplyPlaybackState(
            phase = if (!normalMotion && streamFinalized) ReplyPlaybackPhase.Lingering else ReplyPlaybackPhase.Writing,
            page = page,
            writtenGlyphCount = written,
            dissolveStage = null,
        )
    }

    private companion object {
        const val LAST_DISSOLVE_STAGE = 9

        fun requireValidPage(page: ReplyPage): ReplyPage {
            require(page.index >= 0) { "Page indexes must be non-negative" }
            require(page.glyphs.isNotEmpty()) { "Pages must contain at least one glyph" }
            require(page.sourceStart >= 0 && page.sourceEnd > page.sourceStart) { "Page source range must be non-empty" }
            page.glyphs.forEach { glyph ->
                require(glyph.sourceStart >= page.sourceStart && glyph.sourceEnd <= page.sourceEnd) {
                    "Glyph source range must stay within its page"
                }
                require(glyph.sourceEnd > glyph.sourceStart) { "Glyph source range must be non-empty and forward" }
            }
            require(page.glyphs.first().sourceStart == page.sourceStart) { "Page start must match its first glyph" }
            require(page.glyphs.last().sourceEnd == page.sourceEnd) { "Page end must match its final glyph" }
            page.glyphs.zipWithNext().forEach { (left, right) ->
                require(left.sourceEnd == right.sourceStart) { "Page glyph ranges must be contiguous" }
            }
            return page
        }
    }
}
