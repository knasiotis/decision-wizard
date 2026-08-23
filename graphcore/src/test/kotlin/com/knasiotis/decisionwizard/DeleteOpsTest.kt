package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.editor.DeleteOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteOpsTest {

    /** a -> b -> c, plus a second parent z -> b. */
    private fun chain() = Fixtures.graph(
        "a",
        Fixtures.node("a", Fixtures.answer("e1", "Yes", "b"), Fixtures.answer("e2", "No", "z")),
        Fixtures.node("z", Fixtures.answer("e3", "on", "b")),
        Fixtures.node("b", Fixtures.answer("e4", "next", "c")),
        Fixtures.node("c")
    )

    @Test
    fun `splice repoints every inbound answer at the only child`() {
        val out = assertNotNull(DeleteOps.splice(chain(), "b"))

        assertNull(out.byId["b"])
        assertEquals("c", out.byId.getValue("a").answers.single { it.id == "e1" }.targetNodeId)
        assertEquals("c", out.byId.getValue("z").answers.single { it.id == "e3" }.targetNodeId)
    }

    @Test
    fun `splice keeps the original answer labels`() {
        val out = assertNotNull(DeleteOps.splice(chain(), "b"))
        assertEquals("Yes", out.byId.getValue("a").answers.single { it.id == "e1" }.label)
    }

    @Test
    fun `splice is refused when there is more than one child`() {
        assertNull(DeleteOps.splice(chain(), "a"))
    }

    /** Two answers pointing at the same child is still one child. */
    @Test
    fun `splice allows two answers sharing one target`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "in", "b")),
            Fixtures.node("b", Fixtures.answer("e2", "Yes", "c"), Fixtures.answer("e3", "No", "c")),
            Fixtures.node("c")
        )
        assertTrue(DeleteOps.preview(graph, "b").spliceAvailable)
        assertNotNull(DeleteOps.splice(graph, "b"))
    }

    @Test
    fun `delete only leaves inbound answers dangling and children in place`() {
        val out = DeleteOps.deleteOnly(chain(), "b")

        assertNull(out.byId["b"])
        assertNotNull(out.byId["c"], "child survives")
        assertNull(out.byId.getValue("a").answers.single { it.id == "e1" }.targetNodeId)
        assertNull(out.byId.getValue("z").answers.single { it.id == "e3" }.targetNodeId)
    }

    @Test
    fun `purge also removes what becomes unreachable`() {
        val out = DeleteOps.deleteAndPurge(chain(), "b")

        assertNull(out.byId["b"])
        assertNull(out.byId["c"], "c was only reachable through b")
        assertNotNull(out.byId["z"], "z is reachable directly from the root")
    }

    @Test
    fun `purge preview counts the orphans it is about to take`() {
        val preview = DeleteOps.preview(chain(), "b")

        assertEquals(1, preview.childCount)
        assertEquals(2, preview.inboundCount)
        assertEquals(1, preview.orphanCount, "only c becomes unreachable")
        assertTrue(preview.spliceAvailable)
    }

    /** A child reachable from elsewhere must not be purged. */
    @Test
    fun `purge spares a child that has another route from the root`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "L", "b"), Fixtures.answer("e2", "R", "c")),
            Fixtures.node("b", Fixtures.answer("e3", "on", "c")),
            Fixtures.node("c")
        )
        val out = DeleteOps.deleteAndPurge(graph, "b")
        assertNotNull(out.byId["c"], "c is still reachable straight from a")
    }

    @Test
    fun `reparent moves children across carrying their labels`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "in", "b")),
            Fixtures.node("b", Fixtures.answer("e2", "Escalate", "c")),
            Fixtures.node("c"),
            Fixtures.node("new")
        )
        val out = assertNotNull(DeleteOps.deleteAndReparent(graph, "b", "new"))

        assertNull(out.byId["b"])
        val adopted = out.byId.getValue("new").answers.single()
        assertEquals("Escalate", adopted.label)
        assertEquals("c", adopted.targetNodeId)
        assertNull(out.byId.getValue("a").answers.single().targetNodeId, "inbound now dangles")
    }

    /**
     * The adoptive node already reaching the child does not mean the branch is
     * covered: the label is the thing the agent reads, and dropping it loses the
     * distinction between two different routes to the same node.
     */
    @Test
    fun `reparent keeps a label even when the target is already reachable`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "in", "b")),
            Fixtures.node("b", Fixtures.answer("e2", "Still broken", "c")),
            Fixtures.node("c"),
            Fixtures.node("new", Fixtures.answer("e3", "Works", "c"))
        )
        val out = assertNotNull(DeleteOps.deleteAndReparent(graph, "b", "new"))

        val labels = out.byId.getValue("new").answers.map { it.label }
        assertContains(labels, "Still broken")
        assertContains(labels, "Works")
    }

    @Test
    fun `reparent onto itself is refused`() {
        assertNull(DeleteOps.deleteAndReparent(chain(), "b", "b"))
    }

    @Test
    fun `deleting a node in a cycle keeps the graph loadable`() {
        val graph = Fixtures.example()
        val out = DeleteOps.deleteOnly(graph, "n-recheck")

        assertNull(out.byId["n-recheck"])
        assertTrue(out.nodes.flatMap { it.answers }.none { it.targetNodeId == "n-recheck" })
    }
}
