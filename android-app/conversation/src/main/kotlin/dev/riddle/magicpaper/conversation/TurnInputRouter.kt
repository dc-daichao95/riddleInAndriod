package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.ModelCapabilities
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.paper.PageRasterizationError
import dev.riddle.magicpaper.paper.PageRasterizer
import dev.riddle.magicpaper.paper.RasterizedPage
import kotlinx.coroutines.CancellationException
import java.util.Locale

sealed interface TurnInput {
    data class PageImage(val image: RasterizedPage) : TurnInput
    data class RecognizedText(val text: String) : TurnInput
}

sealed class InputRoutingError(message: String) : Exception(message) {
    data object BlankRecognition : InputRoutingError("No handwriting was recognized")
    data class RecognitionFailed(val error: HandwritingRecognitionError) :
        InputRoutingError("Local handwriting recognition failed")
    data class RasterizationFailed(val error: PageRasterizationError) :
        InputRoutingError("The page image could not be prepared")
}

class TurnInputRouter(
    private val rasterizer: PageRasterizer,
    private val recognizer: HandwritingRecognizer,
    private val localeProvider: () -> Locale = Locale::getDefault,
) {
    suspend fun route(
        capabilities: ModelCapabilities,
        strokes: List<PaperStroke>,
        onRecognitionStatus: (HandwritingRecognitionStatus) -> Unit = {},
    ): Result<TurnInput> {
        if (capabilities.vision) {
            return rasterizer.rasterize(strokes).fold(
                onSuccess = { Result.success(TurnInput.PageImage(it)) },
                onFailure = { Result.failure(InputRoutingError.RasterizationFailed(it as PageRasterizationError)) },
            )
        }

        return try {
            recognizer.recognize(strokes, localeProvider(), onRecognitionStatus).fold(
                onSuccess = { text ->
                    val recognizedText = text.trim()
                    if (recognizedText.isEmpty()) Result.failure(InputRoutingError.BlankRecognition)
                    else Result.success(TurnInput.RecognizedText(recognizedText))
                },
                onFailure = { failure ->
                    val error = failure as? HandwritingRecognitionError
                        ?: HandwritingRecognitionError.RecognitionFailed
                    Result.failure(InputRoutingError.RecognitionFailed(error))
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
}
