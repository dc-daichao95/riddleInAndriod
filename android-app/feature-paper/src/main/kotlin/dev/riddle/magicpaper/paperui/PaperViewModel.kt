package dev.riddle.magicpaper.paperui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.paper.PaperRenderModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PaperPhase { Listening, Dissolving, Thinking, Replying, Interrupted, Failed }

data class PaperUiState(
    val renderModel: PaperRenderModel = PaperRenderModel(),
    val phase: PaperPhase = PaperPhase.Listening,
    val reply: String = "",
    val helpVisible: Boolean = false,
    val portraitLocked: Boolean = false,
)

sealed interface PaperUiIntent {
    data object Cancel : PaperUiIntent
    data object ShowHelp : PaperUiIntent
    data object HideHelp : PaperUiIntent
    data object SettingsClosed : PaperUiIntent
    data class SetPortraitLocked(val locked: Boolean) : PaperUiIntent
}

sealed interface PaperEffect { data object OpenSettings : PaperEffect }

data class PaperRecovery(
    val strokes: List<PaperStroke> = emptyList(),
    val interrupted: Boolean = false,
)

interface PaperPersistence {
    suspend fun load(): PaperRecovery
    suspend fun saveDraft(recovery: PaperRecovery)
    suspend fun markStreaming(strokes: List<PaperStroke>)
    suspend fun clearStreamingAndDraft()
}

interface PaperPreferences {
    val portraitLocked: Flow<Boolean>
    suspend fun setPortraitLocked(locked: Boolean)
}

class PaperViewModel(
    private val provider: ModelProvider,
    private val persistence: PaperPersistence,
    private val preferences: PaperPreferences,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val selectedModelId: () -> String = { "fake" },
) : ViewModel() {
    private val mutableState = MutableStateFlow(PaperUiState())
    val state: StateFlow<PaperUiState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<PaperEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PaperEffect> = mutableEffects.asSharedFlow()

    private var activeStroke: MutableStroke? = null
    private var inactivityJob: Job? = null
    private var streamJob: Job? = null
    private var settingsOpen = false

    init {
        viewModelScope.launch(workerDispatcher) {
            val recovered = persistence.load()
            if (recovered.strokes.isNotEmpty() || recovered.interrupted) {
                mutableState.value = mutableState.value.copy(
                    renderModel = PaperRenderModel(recovered.strokes),
                    phase = if (recovered.interrupted) PaperPhase.Interrupted else PaperPhase.Listening,
                )
            }
        }
        viewModelScope.launch(workerDispatcher) {
            preferences.portraitLocked.collect { locked ->
                mutableState.value = mutableState.value.copy(portraitLocked = locked)
            }
        }
    }

    fun onPaperIntent(intent: PaperIntent) {
        when (intent) {
            is PaperIntent.StrokeStarted -> {
                inactivityJob?.cancel()
                activeStroke = MutableStroke(intent.strokeId, intent.tool, mutableListOf(intent.point))
            }
            is PaperIntent.PointAdded -> activeStroke?.takeIf { it.id == intent.strokeId }?.points?.add(intent.point)
            is PaperIntent.StrokeEnded -> finishStroke(intent.strokeId)
            is PaperIntent.StrokeCancelled -> if (activeStroke?.id == intent.strokeId) activeStroke = null
            is PaperIntent.Erase -> eraseNearest(intent.point)
            PaperIntent.OpenSettings -> openSettings()
        }
    }

    fun onIntent(intent: PaperUiIntent) {
        when (intent) {
            PaperUiIntent.Cancel -> cancelActiveTurn()
            PaperUiIntent.ShowHelp -> {
                inactivityJob?.cancel()
                mutableState.value = mutableState.value.copy(helpVisible = true)
            }
            PaperUiIntent.HideHelp -> {
                mutableState.value = mutableState.value.copy(helpVisible = false)
                scheduleCommitIfNeeded()
            }
            PaperUiIntent.SettingsClosed -> {
                settingsOpen = false
                scheduleCommitIfNeeded()
            }
            is PaperUiIntent.SetPortraitLocked -> viewModelScope.launch(workerDispatcher) {
                preferences.setPortraitLocked(intent.locked)
            }
        }
    }

    private fun finishStroke(id: String) {
        val finished = activeStroke?.takeIf { it.id == id } ?: return
        activeStroke = null
        val stroke = PaperStroke(finished.id, finished.tool, finished.points.toList())
        val strokes = mutableState.value.renderModel.strokes + stroke
        mutableState.value = mutableState.value.copy(
            renderModel = PaperRenderModel(strokes),
            phase = PaperPhase.Listening,
            reply = "",
        )
        persistDraft(strokes)
        scheduleCommitIfNeeded()
    }

    private fun eraseNearest(point: NormalizedPoint) {
        val strokes = mutableState.value.renderModel.strokes.filterNot { stroke ->
            stroke.points.any { kotlin.math.abs(it.x - point.x) < .03f && kotlin.math.abs(it.y - point.y) < .03f }
        }
        if (strokes === mutableState.value.renderModel.strokes || strokes.size == mutableState.value.renderModel.strokes.size) return
        mutableState.value = mutableState.value.copy(renderModel = PaperRenderModel(strokes))
        persistDraft(strokes)
        scheduleCommitIfNeeded()
    }

    private fun openSettings() {
        settingsOpen = true
        inactivityJob?.cancel()
        mutableEffects.tryEmit(PaperEffect.OpenSettings)
    }

    private fun scheduleCommitIfNeeded() {
        inactivityJob?.cancel()
        if (settingsOpen || mutableState.value.helpVisible || mutableState.value.renderModel.strokes.isEmpty()) return
        inactivityJob = viewModelScope.launch(workerDispatcher) {
            delay(INACTIVITY_MILLIS)
            beginTurn()
        }
    }

    private suspend fun beginTurn() {
        val strokes = mutableState.value.renderModel.strokes
        if (strokes.isEmpty() || settingsOpen) return
        persistence.markStreaming(strokes)
        mutableState.value = mutableState.value.copy(
            renderModel = PaperRenderModel(emptyList(), dissolveStage = 0),
            phase = PaperPhase.Dissolving,
            reply = "",
        )
        val request = ModelRequest(selectedModelId(), listOf(Message(MessageRole.USER, "Respond to the handwritten page.")))
        streamJob = viewModelScope.launch(workerDispatcher) {
            mutableState.value = mutableState.value.copy(phase = PaperPhase.Thinking)
            try {
                provider.stream(request).collect { event ->
                    when (event) {
                        is ModelEvent.TextDelta -> mutableState.value = mutableState.value.copy(
                            phase = PaperPhase.Replying,
                            reply = mutableState.value.reply + event.text,
                        )
                        is ModelEvent.Completed -> {
                            persistence.clearStreamingAndDraft()
                            mutableState.value = mutableState.value.copy(phase = PaperPhase.Replying)
                        }
                        is ModelEvent.Failed -> mutableState.value = mutableState.value.copy(phase = PaperPhase.Failed)
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun cancelActiveTurn() {
        inactivityJob?.cancel()
        streamJob?.cancel()
        mutableState.value = mutableState.value.copy(phase = PaperPhase.Listening)
    }

    private fun persistDraft(strokes: List<PaperStroke>) {
        viewModelScope.launch(workerDispatcher) { persistence.saveDraft(PaperRecovery(strokes)) }
    }

    private data class MutableStroke(
        val id: String,
        val tool: PaperTool,
        val points: MutableList<NormalizedPoint>,
    )

    companion object { const val INACTIVITY_MILLIS = 2_800L }
}
