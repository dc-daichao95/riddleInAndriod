package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelEvent

sealed interface ConversationInput {
    data class Tick(val nowMillis: Long) : ConversationInput
    data object TurnStarted : ConversationInput
    data class ProviderEvent(val event: ModelEvent) : ConversationInput
    data class InkChanged(val atMillis: Long, val hasVisibleInk: Boolean) : ConversationInput
    data object TimedOut : ConversationInput
    data object Cancelled : ConversationInput
}
