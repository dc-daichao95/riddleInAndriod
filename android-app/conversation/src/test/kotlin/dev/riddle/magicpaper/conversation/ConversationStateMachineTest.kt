package dev.riddle.magicpaper.conversation

import dev.riddle.magicpaper.model.FinishReason
import dev.riddle.magicpaper.model.ModelError
import dev.riddle.magicpaper.model.ModelEvent
import dev.riddle.magicpaper.model.Message
import dev.riddle.magicpaper.model.MessageRole
import dev.riddle.magicpaper.model.ModelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationStateMachineTest {
    private val machine = ConversationStateMachine()
    private val page = ConversationPage("page-1", hasVisibleInk = true)

    @Test fun `visible ink commits exactly once after inactivity`() {
        val listening = ConversationState.Listening(page, lastPointAtMillis = 1_000)
        assertTrue(machine.transition(listening, ConversationInput.Tick(3_799)).effects.isEmpty())

        val committed = machine.transition(listening, ConversationInput.Tick(3_800))
        assertEquals(
            listOf(
                ConversationEffect.BeginTurn("page-1"),
                ConversationEffect.Rasterize("page-1"),
                ConversationEffect.RenderInkDissolve("page-1"),
            ),
            committed.effects,
        )
        assertIs<ConversationState.Drinking>(committed.state)
        assertTrue(machine.transition(committed.state, ConversationInput.Tick(9_000)).effects.isEmpty())
    }

    @Test fun `erased page never commits`() {
        val state = ConversationState.Listening(page.copy(hasVisibleInk = false), 1_000)
        assertTrue(machine.transition(state, ConversationInput.Tick(10_000)).effects.isEmpty())
        assertEquals(state, machine.transition(state, ConversationInput.Tick(10_000)).state)
    }

    @Test fun `prepared input atomically enters thinking and requests provider exactly once`() {
        val request = request()
        val result = machine.transition(
            ConversationState.Drinking(page, turnStartedAtMillis = 3_800),
            ConversationInput.TurnInputPrepared(request),
        )
        assertEquals(listOf(ConversationEffect.RequestProvider(request)), result.effects)
        assertIs<ConversationState.Thinking>(result.state)
        assertTrue(machine.transition(result.state, ConversationInput.TurnInputPrepared(request)).effects.isEmpty())
    }

    @Test fun `first text delta starts handwriting and later delta appends`() {
        val first = machine.transition(ConversationState.Thinking(page, 3_800), ConversationInput.ProviderEvent(ModelEvent.TextDelta("Hel")))
        assertEquals(listOf(ConversationEffect.RenderHandwriting("Hel", append = false)), first.effects)
        val replying = assertIs<ConversationState.Replying>(first.state)
        val later = machine.transition(replying, ConversationInput.ProviderEvent(ModelEvent.TextDelta("lo")))
        assertEquals("Hello", assertIs<ConversationState.Replying>(later.state).reply)
        assertEquals(listOf(ConversationEffect.RenderHandwriting("lo", append = true)), later.effects)
    }

    @Test fun `completion persists once and clamps linger between four and twenty seconds`() {
        fun complete(length: Int): Transition = machine.transition(
            ConversationState.Replying(page, "x".repeat(length), 3_800),
            ConversationInput.ProviderEvent(ModelEvent.Completed(FinishReason.STOP)),
        )
        val short = complete(1)
        assertEquals(4_000, assertIs<ConversationState.Lingering>(short.state).lingerMillis)
        assertTrue(short.effects.single() is ConversationEffect.PersistCompletedTurn)
        assertEquals(20_000, assertIs<ConversationState.Lingering>(complete(10_000).state).lingerMillis)
        assertTrue(machine.transition(short.state, ConversationInput.ProviderEvent(ModelEvent.Completed(FinishReason.STOP))).effects.isEmpty())
    }

    @Test fun `writing interrupts lingering response`() {
        val result = machine.transition(ConversationState.Lingering(page, "answer", 4_000), ConversationInput.InkChanged(9_000, true))
        assertIs<ConversationState.Listening>(result.state)
        assertEquals(listOf(ConversationEffect.CancelActiveTurn, ConversationEffect.ClearReply), result.effects)
    }

    @Test fun `timeout and provider failure linger without persistence`() {
        val replying = ConversationState.Replying(page, "partial", 3_800)
        listOf(
            machine.transition(replying, ConversationInput.TimedOut),
            machine.transition(replying, ConversationInput.ProviderEvent(ModelEvent.Failed(ModelError.Network()))),
        ).forEach {
            assertIs<ConversationState.FailureLingering>(it.state)
            assertTrue(it.effects.none { effect -> effect is ConversationEffect.PersistCompletedTurn })
        }
    }

    @Test fun `cancellation returns listening and never persists`() {
        val result = machine.transition(ConversationState.Thinking(page, 3_800), ConversationInput.Cancelled)
        assertIs<ConversationState.Listening>(result.state)
        assertEquals(listOf(ConversationEffect.CancelActiveTurn), result.effects)
        assertTrue(result.effects.none { it is ConversationEffect.PersistCompletedTurn })
        val listening = assertIs<ConversationState.Listening>(result.state)
        assertTrue(!listening.page.hasVisibleInk)
        assertTrue(machine.transition(listening, ConversationInput.Tick(Long.MAX_VALUE)).effects.isEmpty())
    }

    @Test fun `ink change updates blank listening page and starts exactly one turn at its deadline`() {
        val blank = ConversationState.Listening(page.copy(hasVisibleInk = false), 0)
        val inked = machine.transition(blank, ConversationInput.InkChanged(1_000, true))
        val listening = assertIs<ConversationState.Listening>(inked.state)
        assertTrue(listening.page.hasVisibleInk)
        assertEquals(1_000, listening.lastPointAtMillis)
        assertTrue(machine.transition(listening, ConversationInput.Tick(3_799)).effects.isEmpty())
        val committed = machine.transition(listening, ConversationInput.Tick(3_800))
        assertEquals(1, committed.effects.count { it is ConversationEffect.BeginTurn })
        assertTrue(committed.effects.none { it is ConversationEffect.RequestProvider })
        assertTrue(machine.transition(committed.state, ConversationInput.Tick(6_600)).effects.isEmpty())
    }

    @Test fun `immediate first delta after prepared input starts handwriting`() {
        val blank = ConversationState.Listening(page.copy(hasVisibleInk = false), 0)
        val listening = machine.transition(blank, ConversationInput.InkChanged(1_000, true)).state
        val drinking = machine.transition(listening, ConversationInput.Tick(3_800))
        assertTrue(drinking.effects.none { it is ConversationEffect.RequestProvider })
        val prepared = machine.transition(drinking.state, ConversationInput.TurnInputPrepared(request()))
        assertEquals(1, prepared.effects.count { it is ConversationEffect.RequestProvider })
        val delta = machine.transition(prepared.state, ConversationInput.ProviderEvent(ModelEvent.TextDelta("now")))
        assertEquals(listOf(ConversationEffect.RenderHandwriting("now", append = false)), delta.effects)
        assertEquals("now", assertIs<ConversationState.Replying>(delta.state).reply)
    }

    @Test fun `backward and overflowing ticks do not commit`() {
        val ordinary = ConversationState.Listening(page, 10_000)
        assertEquals(ordinary, machine.transition(ordinary, ConversationInput.Tick(9_999)).state)
        val extreme = ConversationState.Listening(page, Long.MAX_VALUE - 1_000)
        assertTrue(machine.transition(extreme, ConversationInput.Tick(Long.MAX_VALUE)).effects.isEmpty())
    }

    private fun request() = ModelRequest(
        modelId = "fake",
        messages = listOf(Message(MessageRole.USER, "question")),
    )
}
