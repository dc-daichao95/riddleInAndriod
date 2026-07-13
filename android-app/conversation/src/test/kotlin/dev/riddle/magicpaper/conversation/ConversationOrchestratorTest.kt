package dev.riddle.magicpaper.conversation

import app.cash.turbine.test
import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.ModelRequest
import dev.riddle.magicpaper.model.ModelError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationOrchestratorTest {
    private val request = ModelRequest("fake", listOf(Message(MessageRole.USER, "question")))

    @Test fun `fake provider emits deltas before completion and orchestrator appends handwriting`() = runTest {
        val orchestrator = ConversationOrchestrator(FakeModelProvider(listOf(
            ModelEvent.TextDelta("Hel"), ModelEvent.TextDelta("lo"), ModelEvent.Completed(FinishReason.STOP),
        )))
        orchestrator.collect(request).test {
            assertEquals(ConversationEffect.RenderHandwriting("Hel", false), awaitItem())
            assertEquals(ConversationEffect.RenderHandwriting("lo", true), awaitItem())
            assertTrue(awaitItem() is ConversationEffect.StreamCompleted)
            awaitComplete()
        }
    }

    @Test fun `cancelling orchestration closes provider collection`() = runTest {
        val closed = CompletableDeferred<Unit>()
        val provider = FakeModelProvider(flow {
            emit(ModelEvent.TextDelta("partial"))
            try { awaitCancellation() } finally { closed.complete(Unit) }
        })
        ConversationOrchestrator(provider).collect(request).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(closed.isCompleted)
    }

    @Test fun `each collection treats its first delta as new handwriting`() = runTest {
        val effects = ConversationOrchestrator(FakeModelProvider(listOf(ModelEvent.TextDelta("answer")))).collect(request)
        repeat(2) {
            effects.test {
                assertEquals(ConversationEffect.RenderHandwriting("answer", append = false), awaitItem())
                awaitComplete()
            }
        }
    }

    @Test fun `typed provider failure is preserved as an orchestration effect`() = runTest {
        val error = ModelError.Authentication("invalid")
        ConversationOrchestrator(FakeModelProvider(listOf(ModelEvent.Failed(error)))).collect(request).test {
            assertEquals(ConversationEffect.ProviderFailed(error), awaitItem())
            awaitComplete()
        }
    }
}
