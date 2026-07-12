package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool

data class PaperRenderModel(
    val strokes: List<PaperStroke> = emptyList(),
    val dissolveStage: Int? = null,
)

sealed interface PaperIntent {
    data class StrokeStarted(val strokeId: String, val tool: PaperTool, val point: NormalizedPoint) : PaperIntent
    data class PointAdded(val strokeId: String, val point: NormalizedPoint) : PaperIntent
    data class StrokeEnded(val strokeId: String) : PaperIntent
    data class Erase(val point: NormalizedPoint) : PaperIntent
    data object OpenSettings : PaperIntent
}
