package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperTool
import java.util.concurrent.atomic.AtomicLong

data class InputPoint(
    val pointerId: Int,
    val tool: PointerTool,
    val x: Float,
    val y: Float,
    val pressure: Float,
)

sealed interface InputChange {
    data class Down(val point: InputPoint) : InputChange
    data class PointerDown(val point: InputPoint) : InputChange
    data class Move(
        val points: List<InputPoint>,
        val historical: Map<Int, List<InputPoint>> = emptyMap(),
    ) : InputChange
    data class PointerUp(val pointerId: Int, val point: InputPoint) : InputChange
    data class Up(val pointerId: Int, val point: InputPoint) : InputChange
    data object Cancel : InputChange
}

data class PreviewSegment(val from: NormalizedPoint, val to: NormalizedPoint)
data class PreviewDelta(
    val inkDots: List<NormalizedPoint> = emptyList(),
    val inkSegments: List<PreviewSegment> = emptyList(),
    val dirtyPoints: List<NormalizedPoint> = emptyList(),
    val dirtySegments: List<PreviewSegment> = emptyList(),
)

data class InputReduction(
    val intents: List<PaperIntent>,
    val previewDelta: PreviewDelta,
) : List<PaperIntent> by intents

class PaperInputReducer(
    private val strokeReducer: StrokeReducer = StrokeReducer(),
    private val strokeIds: () -> String = { "stroke-${nextStrokeId.incrementAndGet()}" },
) {
    internal var activePointerId: Int? = null
        private set
    private var activeTool: PointerTool? = null
    private var activeStrokeId: String? = null
    private var lastPoint: NormalizedPoint? = null
    private var stylusInProximity = false
    private var deltaBuilder = DeltaBuilder()

    fun onStylusProximity(inProximity: Boolean) {
        stylusInProximity = inProximity
        strokeReducer.onStylusProximity(inProximity || activeTool == PointerTool.STYLUS || activeTool == PointerTool.ERASER)
    }

    fun reduce(change: InputChange): InputReduction {
        deltaBuilder = DeltaBuilder()
        val intents = when (change) {
            is InputChange.Down -> begin(change.point)
            is InputChange.PointerDown -> if (activePointerId == null) begin(change.point) else emptyList()
            is InputChange.Move -> move(change)
            is InputChange.PointerUp -> if (change.pointerId == activePointerId) finish(change.point) else emptyList()
            is InputChange.Up -> if (change.pointerId == activePointerId) finish(change.point) else emptyList()
            InputChange.Cancel -> cancel()
        }
        return InputReduction(intents, deltaBuilder.build())
    }

    private fun begin(input: InputPoint): List<PaperIntent> {
        if (activePointerId != null || !strokeReducer.accept(input.tool)) return emptyList()
        activePointerId = input.pointerId
        activeTool = input.tool
        if (input.tool != PointerTool.FINGER) strokeReducer.onStylusProximity(true)
        val point = normalize(input)
        lastPoint = point
        if (input.tool == PointerTool.ERASER) deltaBuilder.dirtyPoints += point else deltaBuilder.inkDots += point
        return if (input.tool == PointerTool.ERASER) {
            listOf(PaperIntent.Erase(point))
        } else {
            val id = strokeIds()
            activeStrokeId = id
            listOf(PaperIntent.StrokeStarted(id, PaperTool.PEN, point))
        }
    }

    private fun move(change: InputChange.Move): List<PaperIntent> {
        val id = activePointerId ?: return emptyList()
        val current = change.points.firstOrNull { it.pointerId == id } ?: return emptyList()
        val samples = change.historical[id].orEmpty() + current
        return samples.flatMap(::append)
    }

    private fun append(input: InputPoint): List<PaperIntent> {
        if (input.pointerId != activePointerId || input.tool != activeTool) return emptyList()
        val point = normalize(input)
        val previous = lastPoint
        if (point == previous) return emptyList()
        if (previous != null) {
            val segment = PreviewSegment(previous, point)
            if (activeTool == PointerTool.ERASER) deltaBuilder.dirtySegments += segment else deltaBuilder.inkSegments += segment
        }
        lastPoint = point
        return if (activeTool == PointerTool.ERASER) {
            listOf(PaperIntent.Erase(point))
        } else {
            activeStrokeId?.let { listOf(PaperIntent.PointAdded(it, point)) }.orEmpty()
        }
    }

    private fun finish(point: InputPoint): List<PaperIntent> {
        val intents = append(point).toMutableList()
        activeStrokeId?.let { intents += PaperIntent.StrokeEnded(it) }
        resetActive()
        return intents
    }

    private fun cancel(): List<PaperIntent> {
        val intent = activeStrokeId?.let { PaperIntent.StrokeCancelled(it) }
        resetActive()
        return listOfNotNull(intent)
    }

    private fun resetActive() {
        activePointerId = null
        activeTool = null
        activeStrokeId = null
        lastPoint = null
        strokeReducer.onStylusProximity(stylusInProximity)
    }

    private fun normalize(input: InputPoint) = strokeReducer.point(input.tool, input.x, input.y, input.pressure)

    private companion object {
        val nextStrokeId = AtomicLong()
    }

    private class DeltaBuilder {
        val inkDots = mutableListOf<NormalizedPoint>()
        val inkSegments = mutableListOf<PreviewSegment>()
        val dirtyPoints = mutableListOf<NormalizedPoint>()
        val dirtySegments = mutableListOf<PreviewSegment>()
        fun build() = PreviewDelta(inkDots, inkSegments, dirtyPoints, dirtySegments)
    }
}
