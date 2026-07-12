package dev.riddle.magicpaper.model

data class NormalizedPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
) {
    init {
        require(x.isFinite() && x in 0f..1f) { "x must be finite and normalized" }
        require(y.isFinite() && y in 0f..1f) { "y must be finite and normalized" }
        require(radius.isFinite() && radius in 0f..1f) { "radius must be finite and normalized" }
    }
}

enum class PaperTool {
    PEN,
    ERASER,
}

data class PaperStroke(
    val id: String,
    val tool: PaperTool,
    val points: List<NormalizedPoint>,
)
