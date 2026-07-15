package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelRequest

object HandwritingRequestPolicy {
    fun text(modelId: String, recognizedText: String, languageTag: String): ModelRequest =
        ModelRequest(
            modelId = modelId,
            messages = listOf(
                languageInstruction(languageTag),
                Message(MessageRole.USER, recognizedText),
            ),
        )

    fun vision(modelId: String, imageDataUrl: String, languageTag: String): ModelRequest =
        ModelRequest(
            modelId = modelId,
            messages = listOf(
                languageInstruction(languageTag),
                Message(
                    role = MessageRole.USER,
                    text = "Read the handwriting in this page image and answer in $languageTag.",
                    imageDataUrl = imageDataUrl,
                ),
            ),
        )

    private fun languageInstruction(languageTag: String): Message = Message(
        role = MessageRole.SYSTEM,
        text = "Reply in the same language as the user's handwriting. The selected handwriting language is $languageTag.",
    )
}
