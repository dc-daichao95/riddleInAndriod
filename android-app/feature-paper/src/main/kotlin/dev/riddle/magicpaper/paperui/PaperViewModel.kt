package dev.riddle.magicpaper.paperui

import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.riddle.magicpaper.conversation.*
import dev.riddle.magicpaper.model.*
import dev.riddle.magicpaper.paper.PaperIntent
import dev.riddle.magicpaper.paper.PaperRenderModel
import dev.riddle.magicpaper.paper.RasterizedPage
import dev.riddle.magicpaper.model.SettingsEntryMode
import java.io.FileInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class PaperPhase {
    Listening, Preparing, PreparingRecognition, Recognizing, Thinking, Streaming, Completed,
    Cancelled, Interrupted, RecognitionPreparationFailed, RecognitionFailed, Failed,
}

data class PaperUiState(
    val renderModel: PaperRenderModel = PaperRenderModel(),
    val phase: PaperPhase = PaperPhase.Listening,
    val reply: String = "",
    val helpVisible: Boolean = false,
    val portraitLocked: Boolean = false,
    val settingsEntryMode: SettingsEntryMode = SettingsEntryMode.MAGIC_RUNE_BUTTON,
) {
    val canCancel: Boolean get() = phase in setOf(
        PaperPhase.Preparing,
        PaperPhase.PreparingRecognition,
        PaperPhase.Recognizing,
        PaperPhase.Thinking,
        PaperPhase.Streaming,
    )
}

sealed interface PaperUiIntent {
    data object Cancel : PaperUiIntent
    data object ShowHelp : PaperUiIntent
    data object HideHelp : PaperUiIntent
    data object SettingsClosed : PaperUiIntent
    data object OpenSettings : PaperUiIntent
    data class SetPortraitLocked(val locked: Boolean) : PaperUiIntent
    data class SetSettingsEntryMode(val mode: SettingsEntryMode) : PaperUiIntent
    data class SetMotionScale(val scale: Float) : PaperUiIntent
}

sealed interface PaperEffect { data object OpenSettings : PaperEffect }

data class PaperRecovery(val strokes: List<PaperStroke> = emptyList(), val interrupted: Boolean = false)

interface PaperPersistence {
    suspend fun load(): PaperRecovery
    suspend fun saveDraft(recovery: PaperRecovery)
    suspend fun markStreaming(strokes: List<PaperStroke>)
    suspend fun clearStreamingAndDraft()
}

interface PaperPreferences {
    val portraitLocked: Flow<Boolean>
    val settingsEntryMode: Flow<SettingsEntryMode>
    suspend fun setPortraitLocked(locked: Boolean)
    suspend fun setSettingsEntryMode(mode: SettingsEntryMode)
}

data class SelectedModel(val provider: ModelProvider, val modelId: String, val capabilities: ModelCapabilities)
fun interface ModelSelection { suspend fun selected(): SelectedModel }

class PaperViewModel(
    private val modelSelection: ModelSelection,
    private val persistence: PaperPersistence,
    private val preferences: PaperPreferences,
    private val turnInputRouter: TurnInputRouter,
    private val stateMachine: ConversationStateMachine,
    private val orchestratorFactory: (ModelProvider) -> ConversationOrchestrator,
    private val savedStateHandle: SavedStateHandle,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val questionMarkClassifier: QuestionMarkClassifier = QuestionMarkClassifier(),
    private val beforeSubmittedInkOwnership: suspend () -> Unit = {},
    private val beforePreparingStatePublished: suspend () -> Unit = {},
    private val afterInactivityDeadlineBeforeJobOwnership: suspend () -> Unit = {},
    private val clock: AppClock = object : AppClock {
        override fun elapsedRealtimeMillis() = System.nanoTime() / 1_000_000L
        override fun wallClockMillis() = System.currentTimeMillis()
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(PaperUiState())
    val state: StateFlow<PaperUiState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<PaperEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PaperEffect> = mutableEffects.asSharedFlow()
    private val turnGeneration = AtomicLong()
    private val draftRevision = AtomicLong()
    private val latestDraftWrite = AtomicReference<DraftWrite?>()
    private val submittedInk = AtomicReference<SubmittedInk?>()
    // Serializes turn ownership with ink-restoring stop transitions. Never call suspend code while held.
    private val turnOwnershipLock = Any()
    private val persistenceMutex = Mutex()

    private var activeStroke: MutableStroke? = null
    private val inactivityJob = AtomicReference<Job?>()
    private val activeTurnJob = AtomicReference<Job?>()
    private val settingsOpen = AtomicBoolean(false)
    private val motionScale = AtomicReference(1f)

    init {
        val recoveryBaselineRevision = draftRevision.get()
        val recoveryRunToken = savedStateHandle.get<Long>(RUN_TOKEN_KEY)
        val recoveryLegacyActive = savedStateHandle.get<Boolean>(ACTIVE_TURN_KEY) == true
        viewModelScope.launch(workerDispatcher) {
            val recovered = persistenceMutex.withLock { persistence.load() }
            val wasActive = recovered.interrupted || recoveryRunToken != null || recoveryLegacyActive
            mutableState.update { current ->
                if (current.renderModel.strokes.isNotEmpty() || current.phase != PaperPhase.Listening) current
                else current.copy(
                    renderModel = PaperRenderModel(recovered.strokes),
                    phase = if (wasActive) PaperPhase.Interrupted else PaperPhase.Listening,
                )
            }
            if (wasActive) {
                persistenceMutex.withLock {
                    val tokenStillOwned = recoveryRunToken != null && currentRunToken() == recoveryRunToken
                    val legacyStillOwned = recoveryRunToken == null && recoveryLegacyActive && currentRunToken() == null
                    val recoveryStillOwned = when {
                        recoveryRunToken != null -> tokenStillOwned
                        recoveryLegacyActive -> legacyStillOwned
                        else -> recovered.interrupted && currentRunToken() == null
                    }
                    if (recoveryBaselineRevision == draftRevision.get() && recoveryStillOwned) {
                        persistence.saveDraft(recovered.copy(interrupted = false))
                        compareAndClearRunToken(recoveryRunToken)
                        savedStateHandle[ACTIVE_TURN_KEY] = false
                    }
                }
            }
        }
        viewModelScope.launch(workerDispatcher) {
            preferences.portraitLocked.collect { locked -> mutableState.update { it.copy(portraitLocked = locked) } }
        }
        viewModelScope.launch(workerDispatcher) {
            preferences.settingsEntryMode.collect { mode -> mutableState.update { it.copy(settingsEntryMode = mode) } }
        }
    }

    fun onPaperIntent(intent: PaperIntent) {
        when (intent) {
            is PaperIntent.StrokeStarted -> {
                interruptForWriting()
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
                cancelScheduledTurn()
                mutableState.update { it.copy(helpVisible = true) }
            }
            PaperUiIntent.HideHelp -> {
                mutableState.update { it.copy(helpVisible = false) }
                scheduleCommitIfNeeded()
            }
            PaperUiIntent.SettingsClosed -> {
                settingsOpen.set(false)
                scheduleCommitIfNeeded()
            }
            PaperUiIntent.OpenSettings -> openSettings()
            is PaperUiIntent.SetPortraitLocked -> viewModelScope.launch(workerDispatcher) {
                preferences.setPortraitLocked(intent.locked)
            }
            is PaperUiIntent.SetSettingsEntryMode -> viewModelScope.launch(workerDispatcher) {
                preferences.setSettingsEntryMode(intent.mode)
            }
            is PaperUiIntent.SetMotionScale -> motionScale.set(intent.scale.coerceAtLeast(0f))
        }
    }

    private fun finishStroke(id: String) {
        val finished = activeStroke?.takeIf { it.id == id } ?: return
        activeStroke = null
        val stroke = PaperStroke(finished.id, finished.tool, finished.points.toList())
        val strokes = mutableState.value.renderModel.strokes + stroke
        mutableState.update { it.copy(renderModel = PaperRenderModel(strokes), phase = PaperPhase.Listening, reply = "") }
        persistDraft(strokes)
        scheduleCommitIfNeeded()
    }

    private fun eraseNearest(point: NormalizedPoint) {
        val before = mutableState.value.renderModel.strokes
        val strokes = before.filterNot { stroke -> stroke.points.any { kotlin.math.abs(it.x - point.x) < .03f && kotlin.math.abs(it.y - point.y) < .03f } }
        if (strokes.size == before.size) return
        mutableState.update { it.copy(renderModel = PaperRenderModel(strokes)) }
        persistDraft(strokes)
        scheduleCommitIfNeeded()
    }

    private fun openSettings() {
        settingsOpen.set(true)
        stopActiveTurn(PaperPhase.Listening, clearReply = true)
        cancelScheduledTurn()
        mutableEffects.tryEmit(PaperEffect.OpenSettings)
    }

    private fun scheduleCommitIfNeeded() {
        cancelScheduledTurn()
        if (settingsOpen.get() || mutableState.value.helpVisible || mutableState.value.renderModel.strokes.isEmpty()) return
        val generation = turnGeneration.incrementAndGet()
        val deadline = clock.deadlineAfter(INACTIVITY_MILLIS)
        lateinit var job: Job
        job = viewModelScope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            try {
                deadline.await()
                afterInactivityDeadlineBeforeJobOwnership()
                val activated = synchronized(turnOwnershipLock) {
                    if (!job.isActive || generation != turnGeneration.get() || settingsOpen.get() ||
                        inactivityJob.get() !== job || activeTurnJob.get() != null
                    ) {
                        false
                    } else if (inactivityJob.compareAndSet(job, null)) {
                        activeTurnJob.set(job)
                        true
                    } else {
                        false
                    }
                }
                if (activated) runTurn(generation, job)
            } finally {
                inactivityJob.compareAndSet(job, null)
                activeTurnJob.compareAndSet(job, null)
            }
        }
        inactivityJob.getAndSet(job)?.cancel()
        job.start()
    }

    private suspend fun runTurn(generation: Long, ownerJob: Job) {
        val strokes = mutableState.value.renderModel.strokes
        val runDraftRevision = draftRevision.get()
        if (strokes.isEmpty() || settingsOpen.get() || generation != turnGeneration.get()) return
        if (questionMarkClassifier.isLargeQuestionMark(strokes)) {
            mutableState.update { it.copy(helpVisible = true, phase = PaperPhase.Listening) }
            return
        }

        var pageImage: RasterizedPage? = null
        var markerAttempted = false
        var submission: SubmittedInk? = null
        try {
            val page = ConversationPage("active-page", hasVisibleInk = true)
            val committedAtMillis = clock.elapsedRealtimeMillis()
            var conversationState: ConversationState = ConversationState.Listening(
                page,
                committedAtMillis - INACTIVITY_MILLIS,
            )
            val commit = stateMachine.transition(
                conversationState,
                ConversationInput.Tick(committedAtMillis),
            )
            conversationState = commit.state
            check(conversationState is ConversationState.Drinking)
            beforeSubmittedInkOwnership()
            val candidate = SubmittedInk(generation, strokes)
            val ownershipAcquired = synchronized(turnOwnershipLock) {
                if (!ownerJob.isActive || activeTurnJob.get() !== ownerJob ||
                    generation != turnGeneration.get() || settingsOpen.get() || submittedInk.get() != null
                ) false
                else {
                    submittedInk.set(candidate)
                    true
                }
            }
            if (!ownershipAcquired) return
            submission = candidate
            if (!publishPreparingOwnership(generation, ownerJob, candidate)) return
            awaitDraftWrite(runDraftRevision)
            val markerPersisted = persistenceMutex.withLock {
                if (!isCurrentRun(generation, runDraftRevision)) return@withLock false
                markerAttempted = true
                persistence.markStreaming(strokes)
                if (isCurrentRun(generation, runDraftRevision)) {
                    savedStateHandle[RUN_TOKEN_KEY] = generation
                    savedStateHandle[ACTIVE_TURN_KEY] = false
                    true
                } else {
                    if (runDraftRevision == draftRevision.get()) {
                        persistence.saveDraft(PaperRecovery(strokes, interrupted = false))
                        compareAndClearRunToken(generation)
                        savedStateHandle[ACTIVE_TURN_KEY] = false
                    }
                    false
                }
            }
            if (!markerPersisted) return
            if (!runInputDissolve(generation, ownerJob, candidate)) return

            val selected = modelSelection.selected()
            check(selected.capabilities.streaming) { "Selected model does not support streaming" }
            val routed = turnInputRouter.route(selected.capabilities, strokes) { status ->
                if (generation == turnGeneration.get()) {
                    mutableState.update {
                        it.copy(
                            phase = when (status) {
                                HandwritingRecognitionStatus.PREPARING_MODEL -> PaperPhase.PreparingRecognition
                                HandwritingRecognitionStatus.RECOGNIZING -> PaperPhase.Recognizing
                            },
                        )
                    }
                }
            }.getOrThrow()
            val request = when (routed) {
                is TurnInput.RecognizedText -> ModelRequest(selected.modelId, listOf(Message(MessageRole.USER, routed.text)))
                is TurnInput.PageImage -> {
                    pageImage = routed.image
                    ModelRequest(selected.modelId, listOf(Message(MessageRole.USER, PAGE_IMAGE_PROMPT, imageDataUrl = routed.image.dataUrl())))
                }
            }
            val prepared = stateMachine.transition(conversationState, ConversationInput.TurnInputPrepared(request))
            conversationState = prepared.state
            val providerRequest = prepared.effects.filterIsInstance<ConversationEffect.RequestProvider>().single().request
            mutableState.update { it.copy(phase = PaperPhase.Thinking) }

            var completed = false
            orchestratorFactory(selected.provider).collect(providerRequest).collect { effect ->
                if (generation != turnGeneration.get()) return@collect
                when (effect) {
                    is ConversationEffect.RenderHandwriting -> {
                        conversationState = stateMachine.transition(
                            conversationState,
                            ConversationInput.ProviderEvent(ModelEvent.TextDelta(effect.text)),
                        ).state
                        mutableState.update { current ->
                            current.copy(
                                phase = PaperPhase.Streaming,
                                reply = if (effect.append) current.reply + effect.text else effect.text,
                            )
                        }
                    }
                    is ConversationEffect.StreamCompleted -> {
                        conversationState = stateMachine.transition(
                            conversationState,
                            ConversationInput.ProviderEvent(ModelEvent.Completed(effect.reason)),
                        ).state
                        val cleared = persistenceMutex.withLock {
                            if (!isOwnedRun(generation, runDraftRevision)) return@withLock false
                            persistence.clearStreamingAndDraft()
                            compareAndClearRunToken(generation)
                            savedStateHandle[ACTIVE_TURN_KEY] = false
                            isCurrentRun(generation, runDraftRevision)
                        }
                        if (!cleared) return@collect
                        mutableState.update { it.copy(phase = PaperPhase.Completed) }
                        completed = true
                    }
                    is ConversationEffect.ProviderFailed -> throw ProviderStreamFailure(effect.error)
                    else -> Unit
                }
            }
            if (!completed) throw IllegalStateException("Provider stream ended without completion")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    cleanInterruptedRunBestEffort(generation, runDraftRevision, strokes, markerAttempted)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            persistenceMutex.withLock {
                cleanInterruptedRunBestEffort(generation, runDraftRevision, strokes, markerAttempted)
            }
            if (isCurrentRun(generation, runDraftRevision)) {
                mutableState.update { current ->
                    current.copy(
                        phase = when ((failure as? InputRoutingError.RecognitionFailed)?.error) {
                            is HandwritingRecognitionError.ModelDownloadFailed -> PaperPhase.RecognitionPreparationFailed
                            is HandwritingRecognitionError -> PaperPhase.RecognitionFailed
                            null -> if (failure is InputRoutingError.BlankRecognition) PaperPhase.RecognitionFailed else PaperPhase.Failed
                        },
                        renderModel = if (current.reply.isEmpty()) PaperRenderModel(strokes) else current.renderModel,
                    )
                }
            }
        } finally {
            submission?.let { owned ->
                synchronized(turnOwnershipLock) { submittedInk.compareAndSet(owned, null) }
            }
            pageImage?.close()
            activeTurnJob.compareAndSet(currentCoroutineContext()[Job], null)
        }
    }

    private suspend fun runInputDissolve(
        generation: Long,
        ownerJob: Job,
        submission: SubmittedInk,
    ): Boolean {
        for (stage in 0 until INPUT_DISSOLVE_STAGES) {
            if (!publishDissolveStage(generation, ownerJob, submission, stage)) return false
            val delayMillis = (INPUT_DISSOLVE_STAGE_MILLIS * motionScale.get()).toLong()
            if (delayMillis == 0L) yield() else clock.deadlineAfter(delayMillis).await()
        }
        return synchronized(turnOwnershipLock) {
            if (!ownsSubmission(generation, ownerJob, submission)) {
                false
            } else {
                mutableState.update { current ->
                    current.copy(renderModel = PaperRenderModel(), phase = PaperPhase.Preparing, reply = "")
                }
                true
            }
        }
    }

    private suspend fun publishPreparingOwnership(
        generation: Long,
        ownerJob: Job,
        submission: SubmittedInk,
    ): Boolean {
        while (true) {
            val current = mutableState.value
            beforePreparingStatePublished()
            when (synchronized(turnOwnershipLock) {
                if (!ownsSubmission(generation, ownerJob, submission)) {
                    PreparingPublication.STALE
                } else {
                    val preparing = current.copy(
                        renderModel = PaperRenderModel(submission.strokes),
                        phase = PaperPhase.Preparing,
                        reply = "",
                    )
                    if (mutableState.compareAndSet(current, preparing)) PreparingPublication.PUBLISHED
                    else PreparingPublication.RETRY
                }
            }) {
                PreparingPublication.STALE -> return false
                PreparingPublication.PUBLISHED -> return true
                PreparingPublication.RETRY -> Unit
            }
        }
    }

    private fun publishDissolveStage(
        generation: Long,
        ownerJob: Job,
        submission: SubmittedInk,
        stage: Int,
    ): Boolean {
        while (true) {
            val current = mutableState.value
            when (synchronized(turnOwnershipLock) {
                if (!ownsSubmission(generation, ownerJob, submission)) {
                    PreparingPublication.STALE
                } else {
                    val preparing = current.copy(
                        renderModel = PaperRenderModel(submission.strokes, dissolveStage = stage),
                        phase = PaperPhase.Preparing,
                        reply = "",
                    )
                    if (mutableState.compareAndSet(current, preparing)) PreparingPublication.PUBLISHED
                    else PreparingPublication.RETRY
                }
            }) {
                PreparingPublication.STALE -> return false
                PreparingPublication.PUBLISHED -> return true
                PreparingPublication.RETRY -> Unit
            }
        }
    }

    private fun ownsSubmission(generation: Long, ownerJob: Job, submission: SubmittedInk): Boolean =
        ownerJob.isActive && activeTurnJob.get() === ownerJob &&
            generation == turnGeneration.get() && !settingsOpen.get() && submittedInk.get() === submission

    private fun cancelActiveTurn() {
        if (!mutableState.value.canCancel) return
        stopActiveTurn(PaperPhase.Cancelled, clearReply = false)
    }

    private fun interruptForWriting() {
        if (!stopActiveTurn(PaperPhase.Listening, clearReply = true) &&
            mutableState.value.phase != PaperPhase.Listening
        ) {
            mutableState.update { it.copy(phase = PaperPhase.Listening, reply = "") }
        }
    }

    private fun stopActiveTurn(phase: PaperPhase, clearReply: Boolean): Boolean {
        var scheduledToCancel: Job? = null
        var activeToCancel: Job? = null
        val stopped = synchronized(turnOwnershipLock) {
            val generation = turnGeneration.get()
            val submission = submittedInk.get()?.takeIf { it.generation == generation }
            val sourceInk = submission?.strokes.orEmpty()
            val current = mutableState.value
            scheduledToCancel = inactivityJob.getAndSet(null)
            activeToCancel = activeTurnJob.getAndSet(null)
            if (sourceInk.isEmpty() && !current.canCancel && scheduledToCancel == null && activeToCancel == null) {
                false
            } else {
                if (submission != null) submittedInk.compareAndSet(submission, null)
                turnGeneration.incrementAndGet()
                mutableState.update { latest ->
                    latest.copy(
                        renderModel = PaperRenderModel(mergeStrokes(sourceInk, latest.renderModel.strokes)),
                        phase = phase,
                        reply = if (clearReply) "" else latest.reply,
                    )
                }
                true
            }
        }
        if (!stopped) return false
        scheduledToCancel?.cancel()
        activeToCancel?.takeUnless { it === scheduledToCancel }?.cancel()
        return true
    }

    private fun cancelScheduledTurn() {
        inactivityJob.getAndSet(null)?.cancel()
    }

    private fun mergeStrokes(first: List<PaperStroke>, second: List<PaperStroke>): List<PaperStroke> {
        val firstIds = first.asSequence().map { it.id }.toHashSet()
        return first + second.filterNot { it.id in firstIds }
    }

    private fun persistDraft(strokes: List<PaperStroke>) {
        val revision = draftRevision.incrementAndGet()
        val write = DraftWrite(revision, CompletableDeferred())
        latestDraftWrite.set(write)
        val supersededRunToken = currentRunToken()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result: Result<Unit> = Result.success(Unit)
            try {
                withContext(NonCancellable + workerDispatcher) {
                    persistenceMutex.withLock {
                        if (revision == draftRevision.get()) {
                            persistence.saveDraft(PaperRecovery(strokes, interrupted = false))
                            compareAndClearRunToken(supersededRunToken)
                            savedStateHandle[ACTIVE_TURN_KEY] = false
                        }
                    }
                }
            } catch (failure: Exception) {
                result = Result.failure(failure)
            } finally {
                write.ack.complete(result)
            }
        }
    }

    private suspend fun awaitDraftWrite(revision: Long) {
        latestDraftWrite.get()?.takeIf { it.revision == revision }?.ack?.await()?.getOrThrow()
    }

    private fun isCurrentRun(generation: Long, revision: Long): Boolean =
        generation == turnGeneration.get() && revision == draftRevision.get()

    private fun isOwnedRun(generation: Long, revision: Long): Boolean =
        isCurrentRun(generation, revision) && currentRunToken() == generation

    private fun currentRunToken(): Long? = savedStateHandle.get(RUN_TOKEN_KEY)

    private fun compareAndClearRunToken(ownerToken: Long?) {
        if (ownerToken != null && currentRunToken() == ownerToken) {
            savedStateHandle.remove<Long>(RUN_TOKEN_KEY)
        }
    }

    private suspend fun cleanInterruptedRunBestEffort(
        generation: Long,
        revision: Long,
        strokes: List<PaperStroke>,
        markerAttempted: Boolean,
    ) {
        if (revision != draftRevision.get()) return
        val token = currentRunToken()
        if (token != generation && !(markerAttempted && token == null)) return
        try {
            persistence.saveDraft(PaperRecovery(strokes, interrupted = false))
            compareAndClearRunToken(generation)
            savedStateHandle[ACTIVE_TURN_KEY] = false
        } catch (_: Exception) {
            // Keep the marker/token recoverable and preserve the triggering failure or cancellation.
        }
    }

    override fun onCleared() {
        turnGeneration.incrementAndGet()
        cancelScheduledTurn()
        activeTurnJob.getAndSet(null)?.cancel()
        super.onCleared()
    }

    private fun RasterizedPage.dataUrl(): String = FileInputStream(file).use { input ->
        "data:image/png;base64," + Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
    }

    private data class MutableStroke(val id: String, val tool: PaperTool, val points: MutableList<NormalizedPoint>)
    private data class DraftWrite(val revision: Long, val ack: CompletableDeferred<Result<Unit>>)
    private data class SubmittedInk(val generation: Long, val strokes: List<PaperStroke>)
    private enum class PreparingPublication { STALE, PUBLISHED, RETRY }
    private class ProviderStreamFailure(val error: ModelError) : Exception("Provider stream failed")

    companion object {
        const val INACTIVITY_MILLIS = 2_800L
        const val INPUT_DISSOLVE_STAGE_MILLIS = 70L
        const val INPUT_DISSOLVE_STAGES = 14
        private const val ACTIVE_TURN_KEY = "paper_active_turn"
        private const val RUN_TOKEN_KEY = "paper_active_run_token"
        private const val PAGE_IMAGE_PROMPT = "Read and respond to the handwriting in this page image."
    }
}
