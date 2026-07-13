package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.PaperStroke
import java.util.Locale

interface HandwritingRecognizer {
    suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String>
}

sealed class HandwritingRecognitionError(message: String) : Exception(message) {
    data class UnsupportedLocale(val languageTag: String) :
        HandwritingRecognitionError("No handwriting model supports $languageTag")

    data class ModelNotDownloaded(val languageTag: String) :
        HandwritingRecognitionError("The handwriting model for $languageTag must be downloaded first")

    data object RecognitionFailed : HandwritingRecognitionError("Local handwriting recognition failed")
}
