package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.PaperStroke

data class ReconstructionCatalogEntry(
    val pageId: String,
    val completedAtEpochMillis: Long,
)

data class ReconstructionCatalog(
    val revision: Long,
    val entries: List<ReconstructionCatalogEntry>,
)

data class HistoricalPage(
    val pageId: String,
    val completedAtEpochMillis: Long,
    val originalStrokes: List<PaperStroke>,
    val replyPaths: List<PaperStroke>,
)

data class CurrentPageRestore(
    val pageId: String,
    val strokes: List<PaperStroke>,
    val draftRevision: Long,
)

data class ReconstructionPlan(
    val historicalPageId: String,
    val completedAtEpochMillis: Long,
    val originalStrokes: List<PaperStroke>,
    val fadedReplyPaths: List<PaperStroke>,
    val replyPathAlpha: Float,
    val timeoutMillis: Long,
    val currentPageRestore: CurrentPageRestore,
)

sealed interface ReconstructionResult {
    data class Ready(val plan: ReconstructionPlan) : ReconstructionResult
    data object StaleCatalog : ReconstructionResult
    data object OutOfRange : ReconstructionResult
    data class MissingPage(val pageId: String) : ReconstructionResult
}

class ReconstructionPlanner {
    fun plan(
        selectedNumber: Int,
        selectedCatalog: ReconstructionCatalog,
        currentCatalogRevision: Long,
        pagesById: Map<String, HistoricalPage>,
        currentPage: CurrentPageRestore,
    ): ReconstructionResult {
        if (selectedCatalog.revision != currentCatalogRevision) return ReconstructionResult.StaleCatalog
        val catalogEntry = selectedCatalog.entries.getOrNull(selectedNumber - 1)
            ?: return ReconstructionResult.OutOfRange
        val page = pagesById[catalogEntry.pageId]
            ?.takeIf { it.pageId == catalogEntry.pageId }
            ?: return ReconstructionResult.MissingPage(catalogEntry.pageId)
        return ReconstructionResult.Ready(
            ReconstructionPlan(
                historicalPageId = page.pageId,
                completedAtEpochMillis = page.completedAtEpochMillis,
                originalStrokes = page.originalStrokes.toList(),
                fadedReplyPaths = page.replyPaths.toList(),
                replyPathAlpha = FADED_REPLY_ALPHA,
                timeoutMillis = RECONSTRUCTION_TIMEOUT_MILLIS,
                currentPageRestore = currentPage.copy(strokes = currentPage.strokes.toList()),
            ),
        )
    }

    companion object {
        const val FADED_REPLY_ALPHA = 0.35f
        const val RECONSTRUCTION_TIMEOUT_MILLIS = 120_000L
    }
}
