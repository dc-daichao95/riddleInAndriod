package dev.riddle.magicpaper.paper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.SettingsEntryMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MagicPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onPaperIntent: (PaperIntent) -> Unit = {}
    var settingsEntryMode: SettingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON
        set(value) {
            if (field == value) return
            field = value
            settingsEntryPolicy = value.createPolicy()
        }
    private var settingsEntryPolicy: SettingsEntryPolicy = settingsEntryMode.createPolicy()

    private val inputReducer = PaperInputReducer()
    private val inkBitmapCache = InkBitmapCache(inkColor = context.getColor(R.color.magic_paper_ink))
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.magic_paper_ink)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(inkPaint).apply { style = Paint.Style.FILL }
    private var renderModel = PaperRenderModel()
    private var renderCache: CachedInkBitmap? = null
    private val previewDots = mutableListOf<NormalizedPoint>()
    private val previewSegments = mutableListOf<PreviewSegment>()

    init {
        isFocusable = true
        setBackgroundColor(context.getColor(R.color.magic_paper_background))
    }

    /** Replaces the render snapshot; stroke ownership and mutation remain with the caller. */
    fun submitRenderModel(model: PaperRenderModel) {
        val snapshot = model.copy(strokes = model.strokes.toList())
        if (snapshot == renderModel) return
        renderModel = snapshot
        rebuildRenderCache()
        previewDots.clear()
        previewSegments.clear()
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
        val reduction = inputReducer.reduce(change)
        reduction.intents.forEach(onPaperIntent)
        applyPreviewDelta(reduction.previewDelta)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderCache?.let { canvas.drawBitmap(it.bitmap, 0f, 0f, null) }
        previewDots.forEach { drawPoint(canvas, it) }
        previewSegments.forEach { drawSegment(canvas, it.from, it.to) }
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
        renderCache?.bitmap?.recycle()
        renderCache = inkBitmapCache.build(renderModel.strokes, width, height, renderModel.dissolveStage)
    }

    private fun drawPoint(canvas: Canvas, point: NormalizedPoint) {
        val radius = point.radius * min(width, height)
        canvas.drawCircle(point.x * width, point.y * height, radius, pointPaint)
    }

    private fun drawSegment(canvas: Canvas, from: NormalizedPoint, to: NormalizedPoint) {
        inkPaint.strokeWidth = max(1f, (from.radius + to.radius) * min(width, height))
        canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, inkPaint)
    }

    private fun applyPreviewDelta(delta: PreviewDelta) {
        previewDots += delta.inkDots
        previewSegments += delta.inkSegments
        delta.inkDots.forEach(::invalidatePoint)
        delta.dirtyPoints.forEach(::invalidatePoint)
        delta.inkSegments.forEach { invalidateSegment(it.from, it.to) }
        delta.dirtySegments.forEach { invalidateSegment(it.from, it.to) }
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
}
