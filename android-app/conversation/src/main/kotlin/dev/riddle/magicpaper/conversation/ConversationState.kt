package dev.riddle.magicpaper.conversation

data class ConversationPage(val id: String, val hasVisibleInk: Boolean)

sealed interface ConversationState {
    val page: ConversationPage

    data class Listening(
        override val page: ConversationPage,
        val lastPointAtMillis: Long,
    ) : ConversationState

    data class Drinking(
        override val page: ConversationPage,
        val turnStartedAtMillis: Long,
    ) : ConversationState

    data class Thinking(
        override val page: ConversationPage,
        val turnStartedAtMillis: Long,
    ) : ConversationState

    data class Replying(
        override val page: ConversationPage,
        val reply: String,
        val turnStartedAtMillis: Long,
    ) : ConversationState

    data class Lingering(
        override val page: ConversationPage,
        val reply: String,
        val lingerMillis: Long,
    ) : ConversationState

    data class FailureLingering(
        override val page: ConversationPage,
        val partialReply: String,
    ) : ConversationState
}
