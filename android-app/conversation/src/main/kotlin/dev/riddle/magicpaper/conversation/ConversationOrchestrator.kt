package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelProvider
import dev.riddle.magicpaper.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ConversationOrchestrator(private val provider: ModelProvider) {
    fun collect(request: ModelRequest): Flow<ConversationEffect> = flow {
        var wroteText = false
        provider.stream(request).collect { event ->
            when (event) {
                is ModelEvent.TextDelta -> {
                    emit(ConversationEffect.RenderHandwriting(event.text, append = wroteText))
                    wroteText = true
                }
                is ModelEvent.Completed -> emit(ConversationEffect.StreamCompleted(event.reason))
                is ModelEvent.Failed -> emit(ConversationEffect.ProviderFailed(event.error))
                else -> Unit
            }
        }
    }
}
