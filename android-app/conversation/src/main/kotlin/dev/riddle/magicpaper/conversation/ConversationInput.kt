package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelRequest

sealed interface ConversationInput {
    data class Tick(val nowMillis: Long) : ConversationInput
    data class TurnInputPrepared(val request: ModelRequest) : ConversationInput
    data class ProviderEvent(val event: ModelEvent) : ConversationInput
    data class InkChanged(val atMillis: Long, val hasVisibleInk: Boolean) : ConversationInput
    data object TimedOut : ConversationInput
    data object Cancelled : ConversationInput
}
