package dev.riddle.magicpaper.paperui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.riddle.magicpaper.paper.MagicPaperView

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
) {
    Box(
        modifier.fillMaxSize().background(Color(0xFFF4F0E5)),
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
        Text(
            text = phaseSummary(state.phase),
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).testTag("paper_status")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            color = Color.Transparent,
        )
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) { Text(stringResource(R.string.paper_cancel)) }
        }
        if (state.helpVisible) {
            Surface(Modifier.fillMaxSize(), color = Color(0xF2F4F0E5)) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.paper_help_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.paper_help_body), modifier = Modifier.padding(vertical = 24.dp))
                    Button(onClick = { onIntent(PaperUiIntent.HideHelp) }) {
                        Text(stringResource(R.string.paper_help_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun phaseSummary(phase: PaperPhase) = stringResource(when (phase) {
    PaperPhase.Listening -> R.string.paper_ready
    PaperPhase.Preparing -> R.string.paper_preparing
    PaperPhase.Thinking -> R.string.paper_thinking
    PaperPhase.Streaming -> R.string.paper_reply_appearing
    PaperPhase.Completed -> R.string.paper_completed
    PaperPhase.Cancelled -> R.string.paper_cancelled
    PaperPhase.Interrupted -> R.string.paper_interrupted
    PaperPhase.Failed -> R.string.paper_failed
})
