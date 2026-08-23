package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.chat.ChatEngine
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.chat.Transcript
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranscriptTest {

    private val graph = Fixtures.example()

    private fun ChatState.take(answerId: String): ChatState =
        assertNotNull(ChatEngine.answer(graph, this, answerId), "answer $answerId refused")

    private fun text(state: ChatState) =
        Transcript.format(state, "Tuesday callout", "Internet down", "2026-08-24")

    @Test
    fun `records the questions and the answers taken`() {
        val out = text(ChatEngine.start(graph).take("e-2").take("e-4"))

        assertContains(out, "Is the router powered on?")
        assertContains(out, "> No")
        assertContains(out, "Check the power supply")
        assertContains(out, "> Still dead")
    }

    @Test
    fun `carries the heading`() {
        val out = text(ChatEngine.start(graph))
        assertContains(out, "Tuesday callout")
        assertContains(out, "Flow: Internet down")
        assertContains(out, "Exported: 2026-08-24")
    }

    /** The snippets are the part someone actually pastes onward. */
    @Test
    fun `includes snippets with their labels`() {
        val out = text(ChatEngine.start(graph).take("e-2"))
        assertContains(out, "Ticket note:")
        assertContains(out, "Customer confirmed router had no power")
    }

    @Test
    fun `marks the end of the flow rather than trailing off`() {
        // n-power -> No -> n-plug -> Still dead -> n-swap-psu, an endpoint.
        val out = text(ChatEngine.start(graph).take("e-2").take("e-4"))
        assertContains(out, "(end of the flow)")
    }

    @Test
    fun `marks a question that has not been answered`() {
        val out = text(ChatEngine.start(graph))
        assertContains(out, "(not yet answered)")
    }

    @Test
    fun `says so when nothing was answered and there is no question`() {
        val out = Transcript.format(ChatState(), "Empty", "Gone", "2026-08-24")
        assertContains(out, "Nothing was answered.")
    }

    /**
     * A transcript must say what was asked at the time, not what the flow says
     * now — that is the whole reason a chat is a record.
     */
    @Test
    fun `reports the wording used at the time, not the current graph`() {
        val state = ChatEngine.start(graph).take("e-1")
        graph.replaceNode(graph.byId.getValue("n-power").copy(title = "REWRITTEN"))

        val out = text(state)
        assertContains(out, "Is the router powered on?")
        assertTrue(!out.contains("REWRITTEN"))
    }

    @Test
    fun `file name is slugged from the chat name`() {
        assertEquals(
            "tuesday-callout-2026-08-24.txt",
            Transcript.fileName("Tuesday callout", "2026-08-24")
        )
        assertEquals("chat-2026-08-24.txt", Transcript.fileName("///", "2026-08-24"))
    }
}
