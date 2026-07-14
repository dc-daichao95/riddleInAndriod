package dev.riddle.magicpaper.paperui

import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

fun interface MotionScaleSource {
    fun scales(): Flow<Float>
}

class AndroidMotionScaleSource internal constructor(
    private val settings: MotionScaleSettings,
) : MotionScaleSource {
    constructor(context: Context) : this(AndroidMotionScaleSettings(context))

    override fun scales(): Flow<Float> = callbackFlow {
        val unregister = settings.register { trySend(settings.currentScale()) }
        // Register first so a change racing the initial read cannot be lost.
        trySend(settings.currentScale())
        awaitClose(unregister)
    }.distinctUntilChanged()
}

internal interface MotionScaleSettings {
    fun currentScale(): Float
    fun register(onChange: () -> Unit): () -> Unit
}

private class AndroidMotionScaleSettings(context: Context) : MotionScaleSettings {
    private val resolver = context.applicationContext.contentResolver

    override fun currentScale(): Float = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ).coerceAtLeast(0f)

    override fun register(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        return { resolver.unregisterContentObserver(observer) }
    }
}

data class RuneBrightnessKeyframe(
    val brightness: Float,
    val durationMillis: Int,
)

data class RuneMotionPolicy(val durationScale: Float) {
    init {
        require(durationScale >= 0f)
    }

    val initialBrightness: Float = if (durationScale == 0f) .72f else .46f

    val shimmerKeyframes: List<RuneBrightnessKeyframe> = if (durationScale == 0f) {
        emptyList()
    } else {
        listOf(
            RuneBrightnessKeyframe(.92f, 360),
            RuneBrightnessKeyframe(.58f, 420),
        )
    }
}
