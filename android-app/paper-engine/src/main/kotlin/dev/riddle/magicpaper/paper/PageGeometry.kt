package dev.riddle.magicpaper.paper

data class SafePageBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class NormalizedPageBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Immutable geometry for one committed paper turn. */
data class PageGeometry(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val safeBounds: SafePageBounds,
) {
    init {
        require(pageWidthPx > 0 && pageHeightPx > 0)
        require(safeBounds.left >= 0 && safeBounds.top >= 0)
        require(safeBounds.right <= pageWidthPx && safeBounds.bottom <= pageHeightPx)
        require(safeBounds.width > 0 && safeBounds.height > 0)
    }

    val normalizedSafeBounds: NormalizedPageBounds = NormalizedPageBounds(
        left = safeBounds.left.toFloat() / pageWidthPx,
        top = safeBounds.top.toFloat() / pageHeightPx,
        right = safeBounds.right.toFloat() / pageWidthPx,
        bottom = safeBounds.bottom.toFloat() / pageHeightPx,
    )

    companion object {
        fun fullPage(widthPx: Int, heightPx: Int) = PageGeometry(
            widthPx,
            heightPx,
            SafePageBounds(0, 0, widthPx, heightPx),
        )
    }
}
