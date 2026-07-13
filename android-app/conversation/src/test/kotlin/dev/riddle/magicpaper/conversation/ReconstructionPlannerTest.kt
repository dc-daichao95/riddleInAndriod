package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.NormalizedPoint
import dev.riddle.magicpaper.model.PaperStroke
import dev.riddle.magicpaper.model.PaperTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReconstructionPlannerTest {
    private val planner = ReconstructionPlanner()

    @Test
    fun currentCatalogNumberResolvesStablePageIdAndBuildsFadedPlan() {
        val original = listOf(stroke("original", 0.1f))
        val replyPaths = listOf(stroke("reply-path", 0.7f))
        val current = CurrentPageRestore("current-page", listOf(stroke("draft", 0.9f)), draftRevision = 12)
        val catalog = ReconstructionCatalog(
            revision = 8,
            entries = listOf(
                ReconstructionCatalogEntry("newest", 200),
                ReconstructionCatalogEntry("stable-selected-id", 100),
            ),
        )

        val result = planner.plan(
            selectedNumber = 2,
            selectedCatalog = catalog,
            currentCatalogRevision = 8,
            pagesById = mapOf(
                "newest" to HistoricalPage("newest", 200, emptyList(), emptyList()),
                "stable-selected-id" to HistoricalPage("stable-selected-id", 100, original, replyPaths),
            ),
            currentPage = current,
        )

        val ready = assertIs<ReconstructionResult.Ready>(result).plan
        assertEquals("stable-selected-id", ready.historicalPageId)
        assertEquals(100, ready.completedAtEpochMillis)
        assertEquals(original, ready.originalStrokes)
        assertEquals(replyPaths, ready.fadedReplyPaths)
        assertEquals(0.35f, ready.replyPathAlpha)
        assertEquals(120_000L, ready.timeoutMillis)
        assertEquals(current, ready.currentPageRestore)
    }

    @Test
    fun staleCatalogSelectionFailsWithoutResolvingAReplacementPage() {
        val result = planner.plan(
            selectedNumber = 1,
            selectedCatalog = catalog(revision = 4),
            currentCatalogRevision = 5,
            pagesById = mapOf("page" to history("page")),
            currentPage = currentPage(),
        )

        assertIs<ReconstructionResult.StaleCatalog>(result)
    }

    @Test
    fun outOfRangeCatalogNumberFails() {
        val result = planner.plan(
            selectedNumber = 2,
            selectedCatalog = catalog(revision = 4),
            currentCatalogRevision = 4,
            pagesById = mapOf("page" to history("page")),
            currentPage = currentPage(),
        )

        assertIs<ReconstructionResult.OutOfRange>(result)
    }

    @Test
    fun missingStablePageFailsRatherThanSubstitutingCatalogPosition() {
        val result = planner.plan(
            selectedNumber = 1,
            selectedCatalog = catalog(revision = 4),
            currentCatalogRevision = 4,
            pagesById = mapOf("different-page" to history("different-page")),
            currentPage = currentPage(),
        )

        assertEquals(ReconstructionResult.MissingPage("page"), result)
    }

    private fun catalog(revision: Long) = ReconstructionCatalog(
        revision = revision,
        entries = listOf(ReconstructionCatalogEntry("page", 100)),
    )

    private fun history(id: String) = HistoricalPage(id, 100, listOf(stroke("original", 0.1f)), emptyList())

    private fun currentPage() = CurrentPageRestore("current", listOf(stroke("draft", 0.9f)), 3)

    private fun stroke(id: String, x: Float) = PaperStroke(
        id = id,
        tool = PaperTool.PEN,
        points = listOf(NormalizedPoint(x, 0.5f, 0.01f)),
    )
}
