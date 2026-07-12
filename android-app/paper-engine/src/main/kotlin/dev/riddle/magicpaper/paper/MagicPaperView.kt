package dev.riddle.magicpaper.paper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MagicPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onPaperIntent: (PaperIntent) -> Unit = {}
    var settingsEntryPolicy: SettingsEntryPolicy = ThreeFingerLongPressPolicy()

    private val reducer = StrokeReducer()
    private val dissolvePattern = DissolvePattern()
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.magic_paper_ink)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(inkPaint).apply { style = Paint.Style.FILL }
    private var renderModel = PaperRenderModel()
    private var renderCache: List<CachedStroke> = emptyList()
    private var activeStrokeId: String? = null
    private var activeTool: PointerTool? = null
    private var lastPreviewPoint: NormalizedPoint? = null
    private var stylusHovering = false
    private val previewSegments = mutableListOf<Pair<NormalizedPoint, NormalizedPoint>>()

    init {
        isFocusable = true
        setBackgroundColor(context.getColor(R.color.magic_paper_background))
    }

    /** Replaces the render snapshot; stroke ownership and mutation remain with the caller. */
    fun submitRenderModel(model: PaperRenderModel) {
        renderModel = model.copy(strokes = model.strokes.toList())
        renderCache = renderModel.strokes.filter { it.tool == PaperTool.PEN }.map(::cacheStroke)
        previewSegments.clear()
        lastPreviewPoint = null
        postInvalidateOnAnimation()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                ) {
                    stylusHovering = true
                    reducer.onStylusProximity(true)
                }
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusHovering = false
                reducer.onStylusProximity(false)
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        dispatchSettingsFrame(event)
        val index = event.actionIndex.coerceAtMost(event.pointerCount - 1)
        val tool = pointerTool(event.getToolType(index))
        if (!reducer.accept(tool)) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> startStroke(event, index, tool)
            MotionEvent.ACTION_MOVE -> appendSamples(event, index, tool)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                appendCurrent(event, index, tool)
                endStroke()
            }
            MotionEvent.ACTION_CANCEL -> endStroke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderCache.forEach { drawStroke(canvas, it) }
        previewSegments.forEach { (from, to) -> drawSegment(canvas, from, to, inkPaint) }
    }

    private fun startStroke(event: MotionEvent, index: Int, tool: PointerTool) {
        activeTool = tool
        if (tool != PointerTool.FINGER) reducer.onStylusProximity(true)
        val point = normalized(event.getX(index), event.getY(index), tool, event.getPressure(index))
        if (tool == PointerTool.ERASER) {
            onPaperIntent(PaperIntent.Erase(point))
            invalidatePoint(point)
            return
        }
        val id = "stroke-${nextStrokeId.incrementAndGet()}"
        activeStrokeId = id
        lastPreviewPoint = point
        onPaperIntent(PaperIntent.StrokeStarted(id, PaperTool.PEN, point))
        invalidatePoint(point)
    }

    private fun appendSamples(event: MotionEvent, index: Int, tool: PointerTool) {
        for (history in 0 until event.historySize) {
            appendPoint(normalized(event.getHistoricalX(index, history), event.getHistoricalY(index, history), tool, event.getHistoricalPressure(index, history)))
        }
        appendCurrent(event, index, tool)
    }

    private fun appendCurrent(event: MotionEvent, index: Int, tool: PointerTool) {
        appendPoint(normalized(event.getX(index), event.getY(index), tool, event.getPressure(index)))
    }

    private fun appendPoint(point: NormalizedPoint) {
        if (activeTool == PointerTool.ERASER) {
            onPaperIntent(PaperIntent.Erase(point))
            invalidatePoint(point)
            return
        }
        val id = activeStrokeId ?: return
        val previous = lastPreviewPoint
        if (previous != null && previous != point) {
            previewSegments += previous to point
            invalidateSegment(previous, point)
        }
        lastPreviewPoint = point
        onPaperIntent(PaperIntent.PointAdded(id, point))
    }

    private fun endStroke() {
        activeStrokeId?.let { onPaperIntent(PaperIntent.StrokeEnded(it)) }
        activeStrokeId = null
        activeTool = null
        lastPreviewPoint = null
        if (!stylusHovering) reducer.onStylusProximity(false)
    }

    private fun dispatchSettingsFrame(event: MotionEvent) {
        val contacts = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) emptyList() else
            (0 until event.pointerCount).map { index ->
                TouchContact(event.getPointerId(index), normalized(event.getX(index), event.getY(index), pointerTool(event.getToolType(index)), event.getPressure(index)), pointerTool(event.getToolType(index)))
            }
        if (settingsEntryPolicy.onTouchFrame(TouchFrame(contacts), event.eventTime) == SettingsEntryEvent.OpenSettings) {
            onPaperIntent(PaperIntent.OpenSettings)
        }
    }

    private fun normalized(x: Float, y: Float, tool: PointerTool, pressure: Float) =
        reducer.point(tool, x / width, y / height, pressure)

    private fun pointerTool(androidTool: Int) = when (androidTool) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        else -> PointerTool.FINGER
    }

    private fun cacheStroke(stroke: PaperStroke) = CachedStroke(stroke.points.toList())

    private fun drawStroke(canvas: Canvas, stroke: CachedStroke) {
        val points = stroke.points
        if (points.size == 1) {
            val point = points[0]
            val stage = renderModel.dissolveStage
            if (stage == null || !dissolvePattern.shouldErase((point.x * width).toInt(), (point.y * height).toInt(), stage)) {
                drawPoint(canvas, point)
            }
        }
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            val stage = renderModel.dissolveStage
            val px = (to.x * width).toInt()
            val py = (to.y * height).toInt()
            if (stage == null || !dissolvePattern.shouldErase(px, py, stage)) drawSegment(canvas, from, to, inkPaint)
        }
    }

    private fun drawPoint(canvas: Canvas, point: NormalizedPoint) {
        val radius = point.radius * min(width, height)
        canvas.drawCircle(point.x * width, point.y * height, radius, pointPaint)
    }

    private fun drawSegment(canvas: Canvas, from: NormalizedPoint, to: NormalizedPoint, paint: Paint) {
        paint.strokeWidth = max(1f, (from.radius + to.radius) * min(width, height))
        canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, paint)
    }

    private fun invalidatePoint(point: NormalizedPoint) = invalidateSegment(point, point)

    private fun invalidateSegment(from: NormalizedPoint, to: NormalizedPoint) {
        val radius = max(from.radius, to.radius) * min(width, height) + 2f
        postInvalidateOnAnimation(
            (min(from.x, to.x) * width - radius).toInt(),
            (min(from.y, to.y) * height - radius).toInt(),
            ceil(max(from.x, to.x) * width + radius).toInt(),
            ceil(max(from.y, to.y) * height + radius).toInt(),
        )
    }

    private data class CachedStroke(val points: List<NormalizedPoint>)

    private companion object {
        val nextStrokeId = AtomicLong()
    }
}
