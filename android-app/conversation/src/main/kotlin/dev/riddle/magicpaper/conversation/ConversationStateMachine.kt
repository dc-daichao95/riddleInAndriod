package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelEvent

class ConversationStateMachine {
    fun transition(state: ConversationState, input: ConversationInput): Transition = when {
        state is ConversationState.Listening && input is ConversationInput.Tick -> commitIfDue(state, input)
        state is ConversationState.Drinking && input is ConversationInput.TurnStarted -> Transition(
            ConversationState.Thinking(state.page, state.turnStartedAtMillis),
            listOf(ConversationEffect.Rasterize(state.page.id), ConversationEffect.RenderInkDissolve(state.page.id)),
        )
        input is ConversationInput.ProviderEvent -> providerEvent(state, input.event)
        state is ConversationState.Lingering && input is ConversationInput.InkChanged -> Transition(
            ConversationState.Listening(state.page.copy(hasVisibleInk = input.hasVisibleInk), input.atMillis),
            listOf(ConversationEffect.CancelActiveTurn, ConversationEffect.ClearReply),
        )
        input is ConversationInput.TimedOut -> failure(state)
        input is ConversationInput.Cancelled -> Transition(
            ConversationState.Listening(state.page, 0),
            listOf(ConversationEffect.CancelActiveTurn),
        )
        else -> Transition(state)
    }

    private fun commitIfDue(state: ConversationState.Listening, tick: ConversationInput.Tick): Transition {
        if (!state.page.hasVisibleInk || tick.nowMillis - state.lastPointAtMillis < INACTIVITY_MILLIS) return Transition(state)
        return Transition(
            ConversationState.Drinking(state.page, tick.nowMillis),
            listOf(ConversationEffect.BeginTurn(state.page.id)),
        )
    }

    private fun providerEvent(state: ConversationState, event: ModelEvent): Transition = when (event) {
        is ModelEvent.TextDelta -> when (state) {
            is ConversationState.Thinking -> Transition(
                ConversationState.Replying(state.page, event.text, state.turnStartedAtMillis),
                listOf(ConversationEffect.RenderHandwriting(event.text, append = false)),
            )
            is ConversationState.Replying -> Transition(
                state.copy(reply = state.reply + event.text),
                listOf(ConversationEffect.RenderHandwriting(event.text, append = true)),
            )
            else -> Transition(state)
        }
        is ModelEvent.Completed -> if (state is ConversationState.Replying) Transition(
            ConversationState.Lingering(state.page, state.reply, lingerFor(state.reply)),
            listOf(ConversationEffect.PersistCompletedTurn(state.page.id, state.reply)),
        ) else Transition(state)
        is ModelEvent.Failed -> failure(state)
        else -> Transition(state)
    }

    private fun failure(state: ConversationState): Transition = Transition(
        ConversationState.FailureLingering(state.page, (state as? ConversationState.Replying)?.reply.orEmpty()),
    )

    private fun lingerFor(reply: String): Long = (reply.length * MILLIS_PER_CHARACTER)
        .coerceIn(MIN_LINGER_MILLIS, MAX_LINGER_MILLIS)

    private companion object {
        const val INACTIVITY_MILLIS = 2_800L
        const val MILLIS_PER_CHARACTER = 80L
        const val MIN_LINGER_MILLIS = 4_000L
        const val MAX_LINGER_MILLIS = 20_000L
    }
}
