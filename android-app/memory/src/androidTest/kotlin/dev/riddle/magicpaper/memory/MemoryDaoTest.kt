package dev.riddle.magicpaper.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoTest {
    private lateinit var database: RiddleDatabase
    private lateinit var repository: RoomMemoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RiddleDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomMemoryRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun completedPageRoundTripsWithStableStrokeAndPointOrder() = runTest {
        repository.appendCompleted(page("page-a", 42, strokes(reverseIds = true)))

        val stored = repository.page("page-a")

        assertEquals("question", stored?.transcription)
        assertEquals("answer", stored?.reply)
        assertEquals("provider", stored?.providerId)
        assertEquals("model", stored?.modelId)
        assertEquals(MemoryPageStatus.COMPLETED, stored?.status)
        assertEquals("COMPLETED", database.memoryDao().page("page-a")?.status)
        assertEquals(listOf("z-stroke", "a-stroke"), stored?.strokes?.map(PaperStroke::id))
        assertEquals(listOf(0.1f, 0.2f), stored?.strokes?.first()?.points?.map(NormalizedPoint::x))
    }

    @Test
    fun memoryDisabledPreventsPersistenceAndContextTransmission() = runTest {
        repository.setMemoryEnabled(false)

        assertFalse(repository.appendCompleted(page("not-stored", 1, strokes())))
        assertNull(repository.page("not-stored"))
        assertTrue(repository.recentDialogue().isEmpty())
        assertTrue(repository.catalog().entries.isEmpty())
    }

    @Test
    fun changingMemoryAvailabilityInvalidatesPreviouslyNumberedCatalog() = runTest {
        repository.appendCompleted(page("page-a", 1, strokes()))
        val visibleCatalog = repository.catalog()

        repository.setMemoryEnabled(false)

        val disabledCatalog = repository.catalog()
        assertTrue(disabledCatalog.revision > visibleCatalog.revision)
        assertTrue(disabledCatalog.entries.isEmpty())
    }

    @Test
    fun clearAllRemovesPagesStrokesAndDraftInOneOperation() = runTest {
        repository.appendCompleted(page("page-a", 1, strokes()))
        repository.replaceDraft(DraftPage(7, strokes()))

        repository.clearAll()

        assertEquals(0, database.memoryDao().pageCount())
        assertEquals(0, database.memoryDao().memoryPointCount())
        assertNull(repository.loadDraft())
    }

    @Test
    fun fourHundredOneInsertsKeepNewestFourHundredWithoutOrphanStrokes() = runTest {
        repeat(401) { index ->
            repository.appendCompleted(page("page-$index", index.toLong(), strokes()))
        }

        assertEquals(400, database.memoryDao().pageCount())
        assertNull(repository.page("page-0"))
        assertEquals("page-400", repository.catalog().entries.first().pageId)
        assertEquals(800, database.memoryDao().memoryPointCount())
        assertEquals(0, database.memoryDao().orphanMemoryPointCount())
    }

    @Test
    fun draftReplaceKeepsOnlyLatestRevisionAndNormalizedPoints() = runTest {
        assertTrue(repository.replaceDraft(DraftPage(1, strokes())))
        assertTrue(repository.replaceDraft(DraftPage(2, listOf(stroke("replacement", 0.8f, 0.9f)))))

        val draft = repository.loadDraft()

        assertEquals(2L, draft?.revision)
        assertEquals(listOf("replacement"), draft?.strokes?.map(PaperStroke::id))
        assertEquals(listOf(0.8f, 0.9f), draft?.strokes?.single()?.points?.map(NormalizedPoint::x))
    }

    @Test
    fun delayedOlderDraftCannotOverwriteNewerRevisionOrPoints() = runTest {
        val newestCommitted = CompletableDeferred<Unit>()

        val results = coroutineScope {
            val delayedOlder = async(Dispatchers.IO) {
                newestCommitted.await()
                repository.replaceDraft(DraftPage(10, listOf(stroke("older", 0.1f))))
            }
            val newest = async(Dispatchers.IO) {
                val replaced = repository.replaceDraft(DraftPage(11, listOf(stroke("newest", 0.9f))))
                newestCommitted.complete(Unit)
                replaced
            }
            awaitAll(newest, delayedOlder)
        }

        assertEquals(listOf(true, false), results)
        assertEquals(DraftPage(11, listOf(stroke("newest", 0.9f))), repository.loadDraft())
    }

    @Test
    fun equalDraftRevisionIsIdempotentAndDoesNotReplacePoints() = runTest {
        assertTrue(repository.replaceDraft(DraftPage(11, listOf(stroke("original", 0.4f)))))

        assertFalse(repository.replaceDraft(DraftPage(11, listOf(stroke("conflict", 0.8f)))))

        assertEquals(DraftPage(11, listOf(stroke("original", 0.4f))), repository.loadDraft())
    }

    @Test
    fun activeReplyRunPersistsTheCurrentDraftAndLatestPartialReplyAtomically() = runTest {
        val draft = DraftPage(12, listOf(stroke("submitted", 0.3f)))
        assertTrue(repository.replaceDraft(draft))

        repository.recordActiveReplyRun(
            runId = "run-12",
            draftRevision = draft.revision,
            partialReplySourceText = "first",
            updatedAtEpochMillis = 100,
        )
        repository.recordActiveReplyRun(
            runId = "run-12",
            draftRevision = draft.revision,
            partialReplySourceText = "first reply",
            updatedAtEpochMillis = 101,
        )

        assertEquals(
            ActiveReplyRun(
                runId = "run-12",
                draftRevision = 12,
                partialReplySourceText = "first reply",
                status = ActiveReplyRunStatus.ACTIVE,
                updatedAtEpochMillis = 101,
            ),
            repository.activeReplyRun(),
        )
        assertEquals(draft, repository.loadDraft())
    }

    @Test
    fun activeReplyRunRejectsAnArbitrarySingletonIdAtTheDaoMutationBoundary() = runTest {
        assertTrue(repository.replaceDraft(DraftPage(12, strokes())))

        database.memoryDao().upsertActiveReplyRun(
            id = 99,
            runId = "invalid-id",
            draftId = DraftPageEntity.SINGLETON_ID,
            draftRevision = 12,
            partialReplySourceText = "must not persist",
            status = ActiveReplyRunStatus.ACTIVE.persistedValue,
            updatedAtEpochMillis = 120,
        )

        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM active_reply_runs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        assertNull(repository.activeReplyRun())
    }

    @Test
    fun submittedDraftAndInitialActiveReplyRunAreCommittedTogether() = runTest {
        val submitted = DraftPage(13, listOf(stroke("submitted", 0.7f)))

        assertTrue(
            repository.replaceDraftAndRecordActiveReplyRun(
                draft = submitted,
                runId = "run-13",
                partialReplySourceText = "",
                updatedAtEpochMillis = 130,
            ),
        )

        assertEquals(submitted, repository.loadDraft())
        assertEquals(
            ActiveReplyRun("run-13", 13, "", ActiveReplyRunStatus.ACTIVE, 130),
            repository.activeReplyRun(),
        )
    }

    @Test
    fun activeReplyRunRejectsMissingOrStaleDraftRevisionWithoutWritingARecord() = runTest {
        assertTrue(repository.replaceDraft(DraftPage(20, strokes())))

        assertFalse(
            repository.recordActiveReplyRun(
                runId = "run-stale",
                draftRevision = 19,
                partialReplySourceText = "must not persist",
                updatedAtEpochMillis = 200,
            ),
        )

        assertNull(repository.activeReplyRun())
        assertEquals(20L, repository.loadDraft()?.revision)
    }

    @Test
    fun recoveryInterruptsAnActiveRunAndReturnsItsDraftAndPartialReplyExactlyOnce() = runTest {
        val draft = DraftPage(30, listOf(stroke("recoverable", 0.6f)))
        assertTrue(repository.replaceDraft(draft))
        assertTrue(
            repository.recordActiveReplyRun(
                runId = "run-30",
                draftRevision = draft.revision,
                partialReplySourceText = "partial answer",
                updatedAtEpochMillis = 300,
            ),
        )

        val recovered = repository.recoverActiveReplyRun(interruptedAtEpochMillis = 301)

        assertEquals(
            RecoveredReplyRun(
                draft = draft,
                run = ActiveReplyRun(
                    runId = "run-30",
                    draftRevision = 30,
                    partialReplySourceText = "partial answer",
                    status = ActiveReplyRunStatus.INTERRUPTED,
                    updatedAtEpochMillis = 301,
                ),
            ),
            recovered,
        )
        assertNull(repository.recoverActiveReplyRun(interruptedAtEpochMillis = 302))
        assertEquals(ActiveReplyRunStatus.INTERRUPTED, repository.activeReplyRun()?.status)
    }

    @Test
    fun resolvingTheMatchingActiveRunIsTransactionalAndDoesNotResolveAnotherRun() = runTest {
        val draft = DraftPage(40, strokes())
        assertTrue(repository.replaceDraft(draft))
        assertTrue(repository.recordActiveReplyRun("run-40", 40, "reply", 400))

        assertFalse(repository.resolveActiveReplyRun("other-run", ActiveReplyRunStatus.CANCELLED, 401))
        assertEquals(ActiveReplyRunStatus.ACTIVE, repository.activeReplyRun()?.status)

        assertTrue(repository.resolveActiveReplyRun("run-40", ActiveReplyRunStatus.COMPLETED, 402))
        assertEquals(ActiveReplyRunStatus.COMPLETED, repository.activeReplyRun()?.status)
        assertEquals(402L, repository.activeReplyRun()?.updatedAtEpochMillis)
        assertEquals(draft, repository.loadDraft())
    }

    private fun page(id: String, timestamp: Long, strokes: List<PaperStroke>) = CompletedMemoryPage(
        pageId = id,
        completedAtEpochMillis = timestamp,
        transcription = "question",
        reply = "answer",
        providerId = "provider",
        modelId = "model",
        strokes = strokes,
    )

    private fun strokes(reverseIds: Boolean = false): List<PaperStroke> = if (reverseIds) {
        listOf(stroke("z-stroke", 0.1f, 0.2f), stroke("a-stroke", 0.3f, 0.4f))
    } else {
        listOf(stroke("stroke", 0.1f, 0.2f))
    }

    private fun stroke(id: String, vararg xs: Float) = PaperStroke(
        id = id,
        tool = PaperTool.PEN,
        points = xs.map { NormalizedPoint(it, 0.5f, 0.01f) },
    )
}
