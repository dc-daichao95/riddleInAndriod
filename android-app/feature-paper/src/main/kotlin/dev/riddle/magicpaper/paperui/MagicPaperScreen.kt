package dev.riddle.magicpaper.paperui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riddle.magicpaper.paper.MagicPaperView
import dev.riddle.magicpaper.paper.PageGeometry
import dev.riddle.magicpaper.paper.SafePageBounds
import dev.riddle.magicpaper.model.SettingsEntryMode
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicLong

@Composable
fun MagicPaperRoute(
    viewModel: PaperViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it == PaperEffect.OpenSettings) onOpenSettings() }
    }
    MagicPaperScreen(state, viewModel::onPaperIntent, viewModel::onIntent, modifier)
}

@Composable
fun MagicPaperScreen(
    state: PaperUiState,
    onPaperIntent: (dev.riddle.magicpaper.paper.PaperIntent) -> Unit,
    onIntent: (PaperUiIntent) -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
    runeMotionPolicy: RuneMotionPolicy? = null,
    motionScaleSource: MotionScaleSource? = null,
) {
    val context = LocalContext.current
    val effectiveMotionScaleSource = motionScaleSource ?: remember(context) { AndroidMotionScaleSource(context) }
    val motionScales = remember(effectiveMotionScaleSource) { effectiveMotionScaleSource.scales() }
    val durationScale by motionScales.collectAsStateWithLifecycle(initialValue = 1f)
    val effectiveRuneMotionPolicy = runeMotionPolicy ?: RuneMotionPolicy(durationScale)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeLeft = contentInsets.getLeft(density, layoutDirection)
    val safeTop = contentInsets.getTop(density)
    val safeRight = contentInsets.getRight(density, layoutDirection)
    val safeBottom = contentInsets.getBottom(density)
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val geometryPublisherId = remember { nextGeometryPublisherId.incrementAndGet() }
    LaunchedEffect(motionScales, onIntent) {
        forwardObservedMotionScales(motionScales, onIntent)
    }
    LaunchedEffect(pageSize, safeLeft, safeTop, safeRight, safeBottom, onIntent) {
        if (pageSize.width > 0 && pageSize.height > 0) {
            onIntent(
                PaperUiIntent.SetPageGeometry(
                    pageGeometry(pageSize, safeLeft, safeTop, safeRight, safeBottom),
                    geometryPublisherId,
                ),
            )
        }
    }
    Box(
        modifier.fillMaxSize().onSizeChanged { pageSize = it }.background(Color(0xFFF4F0E5)),
    ) {
        val paperLabel = stringResource(R.string.paper_surface_label)
        AndroidView(
            factory = { context -> MagicPaperView(context).apply { this.onPaperIntent = onPaperIntent } },
            update = { view ->
                view.onPaperIntent = onPaperIntent
                view.settingsEntryMode = state.settingsEntryMode
                view.submitRenderModel(state.renderModel)
            },
            modifier = Modifier.fillMaxSize().testTag("magic_paper").semantics {
                contentDescription = paperLabel
            },
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(contentInsets)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = phaseSummary(state.phase),
                modifier = Modifier
                    .weight(1f)
                    .background(statusBackground(state.phase), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("paper_status")
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF3A342A),
            )
            if (state.settingsEntryMode == SettingsEntryMode.MAGIC_RUNE_BUTTON) {
                MagicSettingsRune(
                    onClick = { onIntent(PaperUiIntent.OpenSettings) },
                    motionPolicy = effectiveRuneMotionPolicy,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        if (state.reply.isNotEmpty()) {
            Text(
                state.reply,
                modifier = Modifier.align(Alignment.Center).padding(32.dp).testTag("paper_reply"),
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFF24211B),
            )
        }
        if (state.canCancel) {
            Button(
                onClick = { onIntent(PaperUiIntent.Cancel) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(contentInsets)
                    .padding(24.dp),
            ) { Text(stringResource(R.string.paper_cancel)) }
        }
        if (state.helpVisible) {
            Surface(Modifier.fillMaxSize(), color = Color(0xF2F4F0E5)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(contentInsets)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.paper_help_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(helpBodyResource(state.settingsEntryMode)),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    Button(onClick = { onIntent(PaperUiIntent.HideHelp) }) {
                        Text(stringResource(R.string.paper_help_close))
                    }
                }
            }
        }
    }
}

private val nextGeometryPublisherId = AtomicLong()

internal fun pageGeometry(
    size: IntSize,
    leftInset: Int,
    topInset: Int,
    rightInset: Int,
    bottomInset: Int,
): PageGeometry {
    require(size.width > 0 && size.height > 0)
    val left = leftInset.coerceIn(0, size.width - 1)
    val top = topInset.coerceIn(0, size.height - 1)
    val right = (size.width - rightInset).coerceIn(left + 1, size.width)
    val bottom = (size.height - bottomInset).coerceIn(top + 1, size.height)
    return PageGeometry(size.width, size.height, SafePageBounds(left, top, right, bottom))
}

internal suspend fun forwardObservedMotionScales(
    scales: Flow<Float>,
    onIntent: (PaperUiIntent) -> Unit,
) {
    scales.collect { scale -> onIntent(PaperUiIntent.SetMotionScale(scale)) }
}

private fun statusBackground(phase: PaperPhase): Color = when (phase) {
    PaperPhase.RecognitionPreparationFailed,
    PaperPhase.RecognitionFailed,
    PaperPhase.Failed -> Color(0xFFF2D8CD)
    else -> Color(0xE6EEE7D8)
}

@Composable
private fun MagicSettingsRune(
    onClick: () -> Unit,
    motionPolicy: RuneMotionPolicy,
    modifier: Modifier = Modifier,
) {
    val brightness = remember(motionPolicy.initialBrightness) { Animatable(motionPolicy.initialBrightness) }
    var isShimmering by remember { mutableStateOf(false) }
    var shimmerSequenceCount by remember { mutableStateOf(0) }
    LaunchedEffect(motionPolicy) {
        brightness.snapTo(motionPolicy.initialBrightness)
        isShimmering = motionPolicy.shimmerKeyframes.isNotEmpty()
        if (isShimmering) shimmerSequenceCount += 1
        try {
            motionPolicy.shimmerKeyframes.forEach { keyframe ->
                brightness.animateTo(keyframe.brightness, tween(keyframe.durationMillis))
            }
        } finally {
            isShimmering = false
        }
    }
    val label = stringResource(R.string.paper_open_settings)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("magic_rune_touch")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .testTag(if (isShimmering) "magic_rune_shimmering" else "magic_rune_static"),
        ) {
            Box(Modifier.fillMaxSize().testTag("magic_rune_shimmer_count_$shimmerSequenceCount"))
            Canvas(
                Modifier
                    .fillMaxSize()
                    .alpha(brightness.value)
                    .testTag("magic_rune_visual"),
            ) {
                val ink = Color(0xFF655A83)
                val thin = size.minDimension * .055f
                drawCircle(ink, radius = size.minDimension * .42f, style = Stroke(thin))
                drawLine(ink, Offset(center.x, size.height * .18f), Offset(center.x, size.height * .82f), thin, StrokeCap.Round)
                drawLine(ink, Offset(size.width * .25f, center.y), Offset(size.width * .75f, center.y), thin, StrokeCap.Round)
                drawLine(ink, Offset(size.width * .28f, size.height * .28f), Offset(size.width * .72f, size.height * .72f), thin, StrokeCap.Round)
                drawLine(ink, Offset(size.width * .72f, size.height * .28f), Offset(size.width * .28f, size.height * .72f), thin, StrokeCap.Round)
                drawCircle(ink, radius = size.minDimension * .075f)
            }
        }
    }
}

private fun helpBodyResource(mode: SettingsEntryMode): Int = when (mode) {
    SettingsEntryMode.MAGIC_RUNE_BUTTON -> R.string.paper_help_body_rune
    SettingsEntryMode.THREE_FINGER_LONG_PRESS -> R.string.paper_help_body_three_finger
}

@Composable
private fun phaseSummary(phase: PaperPhase) = stringResource(when (phase) {
    PaperPhase.Listening -> R.string.paper_ready
    PaperPhase.Preparing -> R.string.paper_preparing
    PaperPhase.PreparingRecognition -> R.string.paper_preparing_recognition
    PaperPhase.Recognizing -> R.string.paper_recognizing
    PaperPhase.Thinking -> R.string.paper_thinking
    PaperPhase.Streaming -> R.string.paper_reply_appearing
    PaperPhase.Completed -> R.string.paper_completed
    PaperPhase.Cancelled -> R.string.paper_cancelled
    PaperPhase.Interrupted -> R.string.paper_interrupted
    PaperPhase.RecognitionPreparationFailed -> R.string.paper_recognition_preparation_failed
    PaperPhase.RecognitionFailed -> R.string.paper_recognition_failed
    PaperPhase.Failed -> R.string.paper_failed
})
