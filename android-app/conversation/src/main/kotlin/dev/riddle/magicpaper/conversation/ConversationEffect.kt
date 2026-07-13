package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.FinishReason

sealed interface ConversationEffect {
    data class BeginTurn(val pageId: String) : ConversationEffect
    data class Rasterize(val pageId: String) : ConversationEffect
    data class RecognizeText(val pageId: String) : ConversationEffect
    data class RequestProvider(val pageId: String) : ConversationEffect
    data class RenderInkDissolve(val pageId: String) : ConversationEffect
    data class RenderHandwriting(val text: String, val append: Boolean) : ConversationEffect
    data class PersistCompletedTurn(val pageId: String, val reply: String) : ConversationEffect
    data class StreamCompleted(val reason: FinishReason) : ConversationEffect
    data object CancelActiveTurn : ConversationEffect
    data object ClearReply : ConversationEffect
}

data class Transition(
    val state: ConversationState,
    val effects: List<ConversationEffect> = emptyList(),
)
