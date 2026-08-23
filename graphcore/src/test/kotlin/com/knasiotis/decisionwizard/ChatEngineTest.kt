package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.chat.ChatEngine
import com.knasiotis.decisionwizard.chat.ChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatEngineTest {

    private val graph = Fixtures.example()

    private fun ChatState.take(answerId: String): ChatState =
        assertNotNull(ChatEngine.answer(graph, this, answerId), "answer $answerId was refused")

    @Test
    fun `a session starts at the root`() {
        val state = ChatEngine.start(graph)
        assertEquals("n-power", state.currentNodeId)
        assertTrue(state.answered.isEmpty())
        assertFalse(ChatEngine.isFinished(graph, state))
    }

    @Test
    fun `answering walks to the target node`() {
        val state = ChatEngine.start(graph).take("e-1")
        assertEquals("n-lights", state.currentNodeId)
        assertEquals(1, state.answered.size)
        assertEquals("n-power", state.answered.single().nodeId)
    }

    @Test
    fun `a node with no answers ends the session`() {
        // n-power -> No -> n-plug -> Still dead -> n-swap-psu (an endpoint)
        val state = ChatEngine.start(graph).take("e-2").take("e-4")
        assertEquals("n-swap-psu", state.currentNodeId)
        assertTrue(ChatEngine.isFinished(graph, state))
    }

    @Test
    fun `an answer that is not on the current node is refused`() {
        val state = ChatEngine.start(graph)
        assertNull(ChatEngine.answer(graph, state, "e-16"), "e-16 belongs to n-wifi, not the root")
        assertNull(ChatEngine.answer(graph, state, "nonsense"))
    }

    @Test
    fun `rewinding drops everything after the chosen step`() {
        val state = ChatEngine.start(graph).take("e-1").take("e-7").take("e-9")
        assertEquals(3, state.answered.size)

        val back = assertNotNull(ChatEngine.rewindTo(state, 1))
        assertEquals(1, back.answered.size)
        assertEquals("n-lights", back.currentNodeId, "back at the question that step 1 answered")
    }

    @Test
    fun `rewinding to step zero returns to the root`() {
        val state = ChatEngine.start(graph).take("e-1").take("e-5")
        val back = assertNotNull(ChatEngine.rewindTo(state, 0))
        assertEquals(ChatEngine.start(graph), back)
    }

    @Test
    fun `rewinding past the end is refused`() {
        val state = ChatEngine.start(graph).take("e-1")
        assertNull(ChatEngine.rewindTo(state, 5))
        assertNull(ChatEngine.rewindTo(state, -1))
    }

    @Test
    fun `tapping a different past answer switches that branch in one step`() {
        val state = ChatEngine.start(graph).take("e-1").take("e-5")

        val switched = assertNotNull(ChatEngine.rewindAndAnswer(graph, state, 1, "e-7"))
        assertEquals(2, switched.answered.size)
        assertEquals("e-7", switched.answered[1].answerId)
        assertEquals("n-cable", switched.currentNodeId)
    }

    /** Cycles are legal, so a node can legitimately be visited more than once. */
    @Test
    fun `walking a cycle records the node twice`() {
        val state = ChatEngine.start(graph)
            .take("e-1")   // n-power  -> n-lights
            .take("e-7")   // n-lights -> n-cable
            .take("e-9")   // n-cable  -> n-restart
            .take("e-13")  // n-restart-> n-recheck
            .take("e-14")  // n-recheck-> n-cable   (round the loop)

        assertEquals("n-cable", state.currentNodeId, "back at the question we already answered")
        assertEquals(1, state.answered.count { it.nodeId == "n-cable" }, "answered once so far")
        assertEquals(
            2,
            ChatEngine.turns(graph, state).count { it.node.id == "n-cable" },
            "the transcript shows the node twice: once answered, once live"
        )
    }

    @Test
    fun `a dangling answer is a dead end, not an error`() {
        val g = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", null))
        )
        val state = assertNotNull(ChatEngine.answer(g, ChatEngine.start(g), "e1"))

        assertNull(state.currentNodeId)
        assertTrue(ChatEngine.isDeadEnd(g, state))
        assertFalse(ChatEngine.isFinished(g, state))
    }

    @Test
    fun `a fresh session is not a dead end`() {
        assertFalse(ChatEngine.isDeadEnd(graph, ChatEngine.start(graph)))
    }

    @Test
    fun `turns lists every answered question then the live one`() {
        val state = ChatEngine.start(graph).take("e-1").take("e-7")
        val turns = ChatEngine.turns(graph, state)

        assertEquals(3, turns.size)
        assertEquals(listOf("n-power", "n-lights", "n-cable"), turns.map { it.node.id })
        assertEquals(listOf(0, 1, -1), turns.map { it.stepIndex })
        assertTrue(turns.last().isLive)
        assertFalse(turns.first().isLive)
    }

    @Test
    fun `turns has no live entry at a dead end`() {
        val g = Fixtures.graph("a", Fixtures.node("a", Fixtures.answer("e1", "Yes", null)))
        val state = assertNotNull(ChatEngine.answer(g, ChatEngine.start(g), "e1"))

        val turns = ChatEngine.turns(g, state)
        assertEquals(1, turns.size)
        assertFalse(turns.single().isLive)
    }

    @Test
    fun `session state carries no graph reference so it can be stored alone`() {
        val state = ChatEngine.start(graph).take("e-1")
        val json = kotlinx.serialization.json.Json.encodeToString(ChatState.serializer(), state)
        val back = kotlinx.serialization.json.Json.decodeFromString(ChatState.serializer(), json)
        assertEquals(state, back)
    }
}
