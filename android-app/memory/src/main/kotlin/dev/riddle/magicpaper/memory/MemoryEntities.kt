package dev.riddle.magicpaper.memory

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import dev.riddle.magicpaper.model.PaperStroke

@Entity(
    tableName = "memory_pages",
    indices = [Index(value = ["completedAtEpochMillis"])],
)
data class MemoryPageEntity(
    @androidx.room.PrimaryKey val pageId: String,
    val completedAtEpochMillis: Long,
    val transcription: String,
    val reply: String,
    val status: String,
    val providerId: String,
    val modelId: String,
    val recordSchemaVersion: Int,
)

@Entity(
    tableName = "memory_points",
    primaryKeys = ["pageId", "strokeOrder", "pointOrder"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryPageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pageId"])],
)
data class MemoryPointEntity(
    val pageId: String,
    val strokeOrder: Int,
    val pointOrder: Int,
    val strokeId: String,
    val tool: String,
    val x: Float,
    val y: Float,
    val radius: Float,
)

@Entity(tableName = "app_preferences")
data class AppPreferenceEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val memoryEnabled: Boolean = true,
    val recentContextCount: Int = DEFAULT_RECENT_CONTEXT_COUNT,
    val memoryRevision: Long = 0,
) {
    companion object {
        const val SINGLETON_ID = 0
        const val DEFAULT_RECENT_CONTEXT_COUNT = 6
    }
}

@Entity(tableName = "draft_pages")
data class DraftPageEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val revision: Long,
) {
    companion object { const val SINGLETON_ID = 0 }
}

@Entity(
    tableName = "draft_points",
    primaryKeys = ["draftId", "strokeOrder", "pointOrder"],
    foreignKeys = [
        ForeignKey(
            entity = DraftPageEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["draftId"])],
)
data class DraftPointEntity(
    val draftId: Int = DraftPageEntity.SINGLETON_ID,
    val strokeOrder: Int,
    val pointOrder: Int,
    val strokeId: String,
    val tool: String,
    val x: Float,
    val y: Float,
    val radius: Float,
)

@Entity(
    tableName = "active_reply_runs",
    foreignKeys = [
        ForeignKey(
            entity = DraftPageEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["draftId"])],
)
data class ActiveReplyRunEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val runId: String,
    val draftId: Int = DraftPageEntity.SINGLETON_ID,
    val draftRevision: Long,
    val partialReplySourceText: String,
    val status: String,
    val updatedAtEpochMillis: Long,
) {
    companion object { const val SINGLETON_ID = 0 }
}

data class CompletedMemoryPage(
    val pageId: String,
    val completedAtEpochMillis: Long,
    val transcription: String,
    val reply: String,
    val status: MemoryPageStatus = MemoryPageStatus.COMPLETED,
    val providerId: String,
    val modelId: String,
    val strokes: List<PaperStroke>,
)

enum class MemoryPageStatus(val persistedValue: String) {
    COMPLETED("COMPLETED");

    companion object {
        fun fromPersistedValue(value: String): MemoryPageStatus = entries.singleOrNull {
            it.persistedValue == value
        } ?: throw IllegalStateException("Unknown memory page status")
    }
}

data class DraftPage(
    val revision: Long,
    val strokes: List<PaperStroke>,
)

data class ActiveReplyRun(
    val runId: String,
    val draftRevision: Long,
    val partialReplySourceText: String,
    val status: ActiveReplyRunStatus,
    val updatedAtEpochMillis: Long,
)

enum class ActiveReplyRunStatus(val persistedValue: String) {
    ACTIVE("ACTIVE"),
    INTERRUPTED("INTERRUPTED"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromPersistedValue(value: String): ActiveReplyRunStatus = entries.singleOrNull {
            it.persistedValue == value
        } ?: throw IllegalStateException("Unknown active reply run status")
    }
}

data class RecoveredReplyRun(
    val draft: DraftPage,
    val run: ActiveReplyRun,
)

data class DialogueTurn(
    val transcription: String,
    val reply: String,
)

data class MemoryCatalogEntry(
    val pageId: String,
    val completedAtEpochMillis: Long,
)

data class MemoryCatalog(
    val revision: Long,
    val entries: List<MemoryCatalogEntry>,
)
