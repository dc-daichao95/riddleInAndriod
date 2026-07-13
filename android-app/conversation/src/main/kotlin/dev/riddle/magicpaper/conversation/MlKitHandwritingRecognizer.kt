package dev.riddle.magicpaper.conversation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface DigitalInkRecognitionBackend {
    fun supports(languageTag: String): Boolean
    suspend fun isModelDownloaded(languageTag: String): Boolean
    suspend fun recognize(languageTag: String, strokes: List<PaperStroke>): String
}

class MlKitHandwritingRecognizer internal constructor(
    private val backend: DigitalInkRecognitionBackend,
    private val fallbackLanguageTag: String = "en-US",
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HandwritingRecognizer {
    constructor(
        fallbackLanguageTag: String = "en-US",
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(MlKitDigitalInkBackend(), fallbackLanguageTag, dispatcher)

    override suspend fun recognize(strokes: List<PaperStroke>, locale: Locale): Result<String> =
        withContext(dispatcher) {
            try {
                val requested = locale.toLanguageTag()
                val selected = when {
                    backend.supports(requested) -> requested
                    backend.supports(fallbackLanguageTag) -> fallbackLanguageTag
                    else -> return@withContext Result.failure(
                        HandwritingRecognitionError.UnsupportedLocale(requested),
                    )
                }
                if (!backend.isModelDownloaded(selected)) {
                    return@withContext Result.failure(
                        HandwritingRecognitionError.ModelNotDownloaded(selected),
                    )
                }
                Result.success(backend.recognize(selected, strokes))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(HandwritingRecognitionError.RecognitionFailed)
            }
        }
}

private class MlKitDigitalInkBackend(
    private val modelManager: RemoteModelManager = RemoteModelManager.getInstance(),
) : DigitalInkRecognitionBackend {
    override fun supports(languageTag: String): Boolean = identifier(languageTag) != null

    override suspend fun isModelDownloaded(languageTag: String): Boolean =
        modelManager.isModelDownloaded(model(languageTag)).await()

    override suspend fun recognize(languageTag: String, strokes: List<PaperStroke>): String {
        val recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model(languageTag)).build(),
        )
        return try {
            val ink = strokes.toMlKitInk()
            recognizer.recognize(ink).await().candidates.firstOrNull()?.text.orEmpty()
        } finally {
            recognizer.close()
        }
    }

    private fun model(languageTag: String): DigitalInkRecognitionModel =
        DigitalInkRecognitionModel.builder(
            checkNotNull(identifier(languageTag)) { "Unsupported language tag" },
        ).build()

    private fun identifier(languageTag: String): DigitalInkRecognitionModelIdentifier? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
    } catch (_: MlKitException) {
        null
    }
}

/**
 * The normalized stroke domain does not retain event times. ML Kit only requires temporal order,
 * so stable point order is encoded as synthetic milliseconds starting at zero. This avoids wall
 * clock input while keeping timestamps strictly increasing across stroke boundaries.
 */
internal fun List<PaperStroke>.toMlKitInk(): Ink {
    var timestampMillis = 0L
    return Ink.builder().apply {
        filter { it.tool == PaperTool.PEN }.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val inkStroke = Ink.Stroke.builder()
            stroke.points.forEach { point ->
                inkStroke.addPoint(Ink.Point.create(point.x, point.y, timestampMillis++))
            }
            addStroke(inkStroke.build())
        }
    }.build()
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resume(value) }
    addOnFailureListener { failure -> continuation.resumeWithException(failure) }
    addOnCanceledListener { continuation.cancel() }
}
