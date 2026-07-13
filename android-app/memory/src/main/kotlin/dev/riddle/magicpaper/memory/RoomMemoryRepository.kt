package dev.riddle.magicpaper.memory

import androidx.room.withTransaction
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool

class RoomMemoryRepository(
    private val database: RiddleDatabase,
) {
    private val dao get() = database.memoryDao()

    suspend fun setMemoryEnabled(enabled: Boolean) = database.withTransaction {
        val current = dao.preference() ?: AppPreferenceEntity()
        dao.upsertPreference(
            current.copy(
                memoryEnabled = enabled,
                memoryRevision = current.memoryRevision + if (current.memoryEnabled == enabled) 0 else 1,
            ),
        )
    }

    suspend fun setRecentContextCount(count: Int) {
        require(count >= 0) { "Recent context count cannot be negative" }
        database.withTransaction {
            val current = dao.preference() ?: AppPreferenceEntity()
            dao.upsertPreference(current.copy(recentContextCount = count))
        }
    }

    suspend fun appendCompleted(page: CompletedMemoryPage): Boolean = database.withTransaction {
        val preference = dao.preference() ?: AppPreferenceEntity()
        if (!preference.memoryEnabled) return@withTransaction false

        dao.insertPage(page.toEntity())
        dao.insertMemoryPoints(page.strokes.toMemoryPoints(page.pageId))
        dao.pruneToNewest(MAX_MEMORIES)
        dao.upsertPreference(preference.copy(memoryRevision = preference.memoryRevision + 1))
        true
    }

    suspend fun page(pageId: String): CompletedMemoryPage? = database.withTransaction {
        val page = dao.page(pageId) ?: return@withTransaction null
        page.toDomain(dao.memoryPoints(pageId).toMemoryStrokes())
    }

    suspend fun recentDialogue(): List<DialogueTurn> = database.withTransaction {
        val preference = dao.preference() ?: AppPreferenceEntity()
        if (!preference.memoryEnabled) return@withTransaction emptyList()
        dao.newestPages(preference.recentContextCount)
            .asReversed()
            .map { DialogueTurn(it.transcription, it.reply) }
    }

    suspend fun catalog(): MemoryCatalog = database.withTransaction {
        val preference = dao.preference() ?: AppPreferenceEntity()
        val entries = if (preference.memoryEnabled) {
            dao.newestPages(MAX_CATALOG_PAGES).map {
                MemoryCatalogEntry(it.pageId, it.completedAtEpochMillis)
            }
        } else {
            emptyList()
        }
        MemoryCatalog(preference.memoryRevision, entries)
    }

    suspend fun clearAll() = database.withTransaction {
        dao.deleteDraft()
        dao.deleteAllPages()
        val preference = dao.preference() ?: AppPreferenceEntity()
        dao.upsertPreference(preference.copy(memoryRevision = preference.memoryRevision + 1))
    }

    suspend fun replaceDraft(draft: DraftPage) = database.withTransaction {
        dao.deleteDraft()
        dao.insertDraft(DraftPageEntity(revision = draft.revision))
        dao.insertDraftPoints(draft.strokes.toDraftPoints())
    }

    suspend fun loadDraft(): DraftPage? = database.withTransaction {
        val draft = dao.draft() ?: return@withTransaction null
        DraftPage(draft.revision, dao.draftPoints().toDraftStrokes())
    }

    suspend fun clearDraft() = database.withTransaction { dao.deleteDraft() }

    private fun CompletedMemoryPage.toEntity() = MemoryPageEntity(
        pageId = pageId,
        completedAtEpochMillis = completedAtEpochMillis,
        transcription = transcription,
        reply = reply,
        providerId = providerId,
        modelId = modelId,
        recordSchemaVersion = RECORD_SCHEMA_VERSION,
    )

    private fun MemoryPageEntity.toDomain(strokes: List<PaperStroke>) = CompletedMemoryPage(
        pageId = pageId,
        completedAtEpochMillis = completedAtEpochMillis,
        transcription = transcription,
        reply = reply,
        providerId = providerId,
        modelId = modelId,
        strokes = strokes,
    )

    private fun List<PaperStroke>.toMemoryPoints(pageId: String) = flatMapIndexed { strokeOrder, stroke ->
        stroke.points.mapIndexed { pointOrder, point ->
            MemoryPointEntity(
                pageId = pageId,
                strokeOrder = strokeOrder,
                pointOrder = pointOrder,
                strokeId = stroke.id,
                tool = stroke.tool.name,
                x = point.x,
                y = point.y,
                radius = point.radius,
            )
        }
    }

    private fun List<PaperStroke>.toDraftPoints() = flatMapIndexed { strokeOrder, stroke ->
        stroke.points.mapIndexed { pointOrder, point ->
            DraftPointEntity(
                strokeOrder = strokeOrder,
                pointOrder = pointOrder,
                strokeId = stroke.id,
                tool = stroke.tool.name,
                x = point.x,
                y = point.y,
                radius = point.radius,
            )
        }
    }

    private fun <T> List<T>.toStrokes(
        strokeOrder: (T) -> Int,
        strokeId: (T) -> String,
        tool: (T) -> String,
        x: (T) -> Float,
        y: (T) -> Float,
        radius: (T) -> Float,
    ): List<PaperStroke> = groupBy(strokeOrder)
        .toSortedMap()
        .values
        .map { points ->
            val first = points.first()
            PaperStroke(
                id = strokeId(first),
                tool = PaperTool.valueOf(tool(first)),
                points = points.map { NormalizedPoint(x(it), y(it), radius(it)) },
            )
        }

    private fun List<MemoryPointEntity>.toMemoryStrokes() = toStrokes(
        MemoryPointEntity::strokeOrder,
        MemoryPointEntity::strokeId,
        MemoryPointEntity::tool,
        MemoryPointEntity::x,
        MemoryPointEntity::y,
        MemoryPointEntity::radius,
    )

    private fun List<DraftPointEntity>.toDraftStrokes() = toStrokes(
        DraftPointEntity::strokeOrder,
        DraftPointEntity::strokeId,
        DraftPointEntity::tool,
        DraftPointEntity::x,
        DraftPointEntity::y,
        DraftPointEntity::radius,
    )

    companion object {
        const val MAX_MEMORIES = 400
        const val MAX_CATALOG_PAGES = 40
        const val RECORD_SCHEMA_VERSION = 1
    }
}
