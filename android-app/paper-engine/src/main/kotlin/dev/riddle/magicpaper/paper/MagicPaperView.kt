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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MagicPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onPaperIntent: (PaperIntent) -> Unit = {}
    var settingsEntryPolicy: SettingsEntryPolicy = ThreeFingerLongPressPolicy()

    private val inputReducer = PaperInputReducer()
    private val dissolvePattern = DissolvePattern()
    private val dissolveSelection = DissolveSelection(dissolvePattern)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.magic_paper_ink)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(inkPaint).apply { style = Paint.Style.FILL }
    private var renderModel = PaperRenderModel()
    private var renderCache: List<CachedStroke> = emptyList()

    init {
        isFocusable = true
        setBackgroundColor(context.getColor(R.color.magic_paper_background))
    }

    /** Replaces the render snapshot; stroke ownership and mutation remain with the caller. */
    fun submitRenderModel(model: PaperRenderModel) {
        renderModel = model.copy(strokes = model.strokes.toList())
        rebuildRenderCache()
        inputReducer.clearPreview()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildRenderCache()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.pointerCount <= 0) return false
        val tool = pointerTool(event.getToolType(0))
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) inputReducer.onStylusProximity(true)
            MotionEvent.ACTION_HOVER_EXIT ->
                if (tool == PointerTool.STYLUS || tool == PointerTool.ERASER) inputReducer.onStylusProximity(false)
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        if (event.pointerCount > 0) dispatchSettingsFrame(event)
        val change = toInputChange(event) ?: return true
        val before = inputReducer.preview
        inputReducer.reduce(change).forEach(onPaperIntent)
        invalidatePreviewDelta(before, inputReducer.preview)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderCache.forEach { stroke -> stroke.primitives.forEach { drawPrimitive(canvas, it) } }
        val preview = inputReducer.preview
        preview.points.forEach { drawPoint(canvas, it) }
        preview.segments.forEach { drawSegment(canvas, it.from, it.to) }
    }

    private fun toInputChange(event: MotionEvent): InputChange? {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pointAt(event, safeActionIndex(event))?.let(InputChange::Down)
            MotionEvent.ACTION_POINTER_DOWN -> pointAt(event, safeActionIndex(event))?.let(InputChange::PointerDown)
            MotionEvent.ACTION_MOVE -> {
                val activeId = inputReducer.activePointerId ?: return null
                val activeIndex = event.findPointerIndex(activeId)
                if (activeIndex < 0) return null
                val current = pointAt(event, activeIndex) ?: return null
                val history = (0 until event.historySize).mapNotNull { position -> pointAt(event, activeIndex, position) }
                InputChange.Move(listOf(current), mapOf(activeId to history))
            }
            MotionEvent.ACTION_POINTER_UP -> pointAt(event, safeActionIndex(event))?.let { InputChange.PointerUp(it.pointerId, it) }
            MotionEvent.ACTION_UP -> pointAt(event, safeActionIndex(event))?.let { InputChange.Up(it.pointerId, it) }
            MotionEvent.ACTION_CANCEL -> InputChange.Cancel
            else -> null
        }
    }

    private fun safeActionIndex(event: MotionEvent): Int = event.actionIndex.takeIf { it in 0 until event.pointerCount } ?: 0

    private fun pointAt(event: MotionEvent, index: Int, historyPosition: Int? = null): InputPoint? {
        if (index !in 0 until event.pointerCount) return null
        val x = if (historyPosition == null) event.getX(index) else event.getHistoricalX(index, historyPosition)
        val y = if (historyPosition == null) event.getY(index) else event.getHistoricalY(index, historyPosition)
        val pressure = if (historyPosition == null) event.getPressure(index) else event.getHistoricalPressure(index, historyPosition)
        return InputPoint(event.getPointerId(index), pointerTool(event.getToolType(index)), x / width, y / height, pressure)
    }

    private fun dispatchSettingsFrame(event: MotionEvent) {
        val liftedId = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.getPointerId(safeActionIndex(event)) else null
        val contacts = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) emptyList() else
            (0 until event.pointerCount).filter { event.getPointerId(it) != liftedId }.mapNotNull { index ->
                pointAt(event, index)?.let { input ->
                    TouchContact(input.pointerId, NormalizedPoint(input.x.coerceIn(0f, 1f), input.y.coerceIn(0f, 1f), 0f), input.tool)
                }
            }
        if (settingsEntryPolicy.onTouchFrame(TouchFrame(contacts), event.eventTime) == SettingsEntryEvent.OpenSettings) {
            onPaperIntent(PaperIntent.OpenSettings)
        }
    }

    private fun pointerTool(androidTool: Int) = when (androidTool) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER
        else -> PointerTool.FINGER
    }

    private fun rebuildRenderCache() {
        if (width <= 0 || height <= 0) return
        renderCache = renderModel.strokes.filter { it.tool == PaperTool.PEN }.map(::cacheStroke)
    }

    private fun cacheStroke(stroke: PaperStroke): CachedStroke {
        val stage = renderModel.dissolveStage
        if (stage == null) return CachedStroke(stroke.points.zipWithNext { from, to -> DrawPrimitive.NormalizedLine(from, to) } + stroke.points.take(1).map(DrawPrimitive::NormalizedDot))
        val primitives = mutableListOf<DrawPrimitive>()
        if (stroke.points.size == 1) {
            val point = stroke.points.single()
            val pixel = pixel(point)
            if (!dissolvePattern.shouldErase(pixel.x, pixel.y, stage)) primitives += DrawPrimitive.NormalizedDot(point)
        }
        stroke.points.zipWithNext().forEach { (from, to) ->
            val width = max(1f, (from.radius + to.radius) * min(this.width, this.height))
            dissolveSelection.survivingRuns(pixel(from), pixel(to), stage).forEach { run -> primitives += DrawPrimitive.PixelLine(run.pixels, width) }
        }
        return CachedStroke(primitives)
    }

    private fun pixel(point: NormalizedPoint) = PixelCoordinate((point.x * width).toInt(), (point.y * height).toInt())

    private fun drawPrimitive(canvas: Canvas, primitive: DrawPrimitive) = when (primitive) {
        is DrawPrimitive.NormalizedDot -> drawPoint(canvas, primitive.point)
        is DrawPrimitive.NormalizedLine -> drawSegment(canvas, primitive.from, primitive.to)
        is DrawPrimitive.PixelLine -> {
            inkPaint.strokeWidth = primitive.width
            if (primitive.pixels.size == 1) canvas.drawCircle(primitive.pixels[0].x.toFloat(), primitive.pixels[0].y.toFloat(), primitive.width / 2f, pointPaint)
            primitive.pixels.zipWithNext().forEach { (from, to) -> canvas.drawLine(from.x.toFloat(), from.y.toFloat(), to.x.toFloat(), to.y.toFloat(), inkPaint) }
        }
    }

    private fun drawPoint(canvas: Canvas, point: NormalizedPoint) {
        val radius = point.radius * min(width, height)
        canvas.drawCircle(point.x * width, point.y * height, radius, pointPaint)
    }

    private fun drawSegment(canvas: Canvas, from: NormalizedPoint, to: NormalizedPoint) {
        inkPaint.strokeWidth = max(1f, (from.radius + to.radius) * min(width, height))
        canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, inkPaint)
    }

    private fun invalidatePreviewDelta(before: PaperInputPreview, after: PaperInputPreview) {
        after.points.drop(before.points.size).forEach(::invalidatePoint)
        after.segments.drop(before.segments.size).forEach { invalidateSegment(it.from, it.to) }
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

    private data class CachedStroke(val primitives: List<DrawPrimitive>)
    private sealed interface DrawPrimitive {
        data class NormalizedDot(val point: NormalizedPoint) : DrawPrimitive
        data class NormalizedLine(val from: NormalizedPoint, val to: NormalizedPoint) : DrawPrimitive
        data class PixelLine(val pixels: List<PixelCoordinate>, val width: Float) : DrawPrimitive
    }
}
