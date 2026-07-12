package dev.riddle.magicpaper.paper

import dev.riddle.magicpaper.model.NormalizedPoint
import kotlin.math.hypot

enum class PointerTool {
    FINGER,
    STYLUS,
    ERASER,
}

data class TouchContact(
    val id: Int,
    val position: NormalizedPoint,
    val tool: PointerTool,
)

data class TouchFrame(
    val contacts: List<TouchContact>,
)

sealed interface SettingsEntryEvent {
    data object OpenSettings : SettingsEntryEvent
}

fun interface SettingsEntryPolicy {
    fun onTouchFrame(frame: TouchFrame, monotonicMillis: Long): SettingsEntryEvent?
}

class ThreeFingerLongPressPolicy(
    private val holdMillis: Long = 2_000,
    private val slopNormalized: Float = 0.02f,
) : SettingsEntryPolicy {
    private var startedAtMillis: Long? = null
    private var initialPositions: Map<Int, NormalizedPoint> = emptyMap()
    private var blockedUntilRelease = false
    private var emitted = false

    init {
        require(holdMillis >= 0) { "hold duration cannot be negative" }
        require(slopNormalized.isFinite() && slopNormalized >= 0f) { "slop must be finite and non-negative" }
    }

    override fun onTouchFrame(frame: TouchFrame, monotonicMillis: Long): SettingsEntryEvent? {
        if (frame.contacts.isEmpty()) {
            reset()
            return null
        }
        if (blockedUntilRelease || emitted) return null

        if (frame.contacts.size != REQUIRED_CONTACTS ||
            frame.contacts.any { it.tool != PointerTool.FINGER } ||
            frame.contacts.map { it.id }.toSet().size != REQUIRED_CONTACTS
        ) {
            blockedUntilRelease = true
            return null
        }

        val start = startedAtMillis
        if (start == null) {
            startedAtMillis = monotonicMillis
            initialPositions = frame.contacts.associate { it.id to it.position }
            return null
        }

        val movedTooFar = frame.contacts.any { contact ->
            val initial = initialPositions[contact.id] ?: return@any true
            hypot(contact.position.x - initial.x, contact.position.y - initial.y) > slopNormalized
        }
        if (movedTooFar) {
            blockedUntilRelease = true
            return null
        }

        return if (monotonicMillis - start >= holdMillis) {
            emitted = true
            SettingsEntryEvent.OpenSettings
        } else {
            null
        }
    }

    private fun reset() {
        startedAtMillis = null
        initialPositions = emptyMap()
        blockedUntilRelease = false
        emitted = false
    }

    private companion object {
        const val REQUIRED_CONTACTS = 3
    }
}
