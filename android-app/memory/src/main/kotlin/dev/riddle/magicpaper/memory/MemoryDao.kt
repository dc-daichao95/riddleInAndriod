package dev.riddle.magicpaper.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertPage(page: MemoryPageEntity)

    @Insert
    suspend fun insertMemoryPoints(points: List<MemoryPointEntity>)

    @Query("SELECT * FROM memory_pages WHERE pageId = :pageId")
    suspend fun page(pageId: String): MemoryPageEntity?

    @Query(
        "SELECT * FROM memory_points WHERE pageId = :pageId " +
            "ORDER BY strokeOrder ASC, pointOrder ASC",
    )
    suspend fun memoryPoints(pageId: String): List<MemoryPointEntity>

    @Query(
        "SELECT * FROM memory_pages ORDER BY completedAtEpochMillis DESC, pageId DESC LIMIT :limit",
    )
    suspend fun newestPages(limit: Int): List<MemoryPageEntity>

    @Query(
        "DELETE FROM memory_pages WHERE pageId IN (" +
            "SELECT pageId FROM memory_pages " +
            "ORDER BY completedAtEpochMillis DESC, pageId DESC LIMIT -1 OFFSET :keepCount" +
            ")",
    )
    suspend fun pruneToNewest(keepCount: Int)

    @Query("DELETE FROM memory_pages")
    suspend fun deleteAllPages()

    @Query("SELECT COUNT(*) FROM memory_pages")
    suspend fun pageCount(): Int

    @Query("SELECT COUNT(*) FROM memory_points")
    suspend fun memoryPointCount(): Int

    @Query(
        "SELECT COUNT(*) FROM memory_points AS point " +
            "LEFT JOIN memory_pages AS page ON page.pageId = point.pageId " +
            "WHERE page.pageId IS NULL",
    )
    suspend fun orphanMemoryPointCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: AppPreferenceEntity)

    @Query("SELECT * FROM app_preferences WHERE id = 0")
    suspend fun preference(): AppPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftPageEntity)

    @Insert
    suspend fun insertDraftPoints(points: List<DraftPointEntity>)

    @Query("SELECT * FROM draft_pages WHERE id = 0")
    suspend fun draft(): DraftPageEntity?

    @Query(
        "SELECT * FROM draft_points WHERE draftId = 0 " +
            "ORDER BY strokeOrder ASC, pointOrder ASC",
    )
    suspend fun draftPoints(): List<DraftPointEntity>

    @Query("DELETE FROM draft_pages")
    suspend fun deleteDraft()

    @Query(
        "INSERT INTO active_reply_runs (id, runId, draftId, draftRevision, partialReplySourceText, status, updatedAtEpochMillis) " +
            "SELECT :id, :runId, :draftId, :draftRevision, :partialReplySourceText, :status, :updatedAtEpochMillis " +
            "WHERE :id = 0 " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "runId = excluded.runId, " +
            "draftId = excluded.draftId, " +
            "draftRevision = excluded.draftRevision, " +
            "partialReplySourceText = excluded.partialReplySourceText, " +
            "status = excluded.status, " +
            "updatedAtEpochMillis = excluded.updatedAtEpochMillis",
    )
    suspend fun upsertActiveReplyRun(
        id: Int,
        runId: String,
        draftId: Int,
        draftRevision: Long,
        partialReplySourceText: String,
        status: String,
        updatedAtEpochMillis: Long,
    ): Long

    @Query("SELECT * FROM active_reply_runs WHERE id = 0")
    suspend fun activeReplyRun(): ActiveReplyRunEntity?

    @Query(
        "UPDATE active_reply_runs SET status = :status, updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE id = 0 AND runId = :runId AND status = 'ACTIVE'",
    )
    suspend fun resolveActiveReplyRun(
        runId: String,
        status: String,
        updatedAtEpochMillis: Long,
    ): Int
}
