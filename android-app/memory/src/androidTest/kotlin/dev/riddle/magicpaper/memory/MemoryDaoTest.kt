package dev.riddle.magicpaper.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlinx.coroutines.test.runTest
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
        repository.replaceDraft(DraftPage(1, strokes()))
        repository.replaceDraft(DraftPage(2, listOf(stroke("replacement", 0.8f, 0.9f))))

        val draft = repository.loadDraft()

        assertEquals(2L, draft?.revision)
        assertEquals(listOf("replacement"), draft?.strokes?.map(PaperStroke::id))
        assertEquals(listOf(0.8f, 0.9f), draft?.strokes?.single()?.points?.map(NormalizedPoint::x))
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
