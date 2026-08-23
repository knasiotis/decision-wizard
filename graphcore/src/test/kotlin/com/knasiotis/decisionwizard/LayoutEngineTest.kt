package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.layout.EdgeKind
import com.knasiotis.decisionwizard.layout.LayoutEngine
import com.knasiotis.decisionwizard.model.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class LayoutEngineTest {

    /**
     * Every id handed to the height callback must be a real node. The callback is
     * backed by measured composables in the editor, so a synthetic id would either
     * throw or silently return a wrong height.
     */
    private fun strictHeights(graph: Graph): (String) -> Float = { id ->
        require(graph.byId.containsKey(id)) { "layout asked for the height of unknown node id '$id'" }
        64f
    }

    @Test
    fun `cyclic sample lays out without hanging or overflowing the stack`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        assertEquals(graph.nodes.size, layout.positions.size, "every node needs a position")
        assertEquals(0, layout.orphans.size, "sample is fully reachable")
        assertTrue(layout.height > 0f)
        assertTrue(layout.width > 0f)
    }

    @Test
    fun `root sits in the first layer`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))
        assertEquals(0, layout.positions.getValue("n-power").layer)
    }

    /**
     * The cycle n-cable -> n-restart -> n-recheck -> n-cable has to be broken
     * somewhere, but which edge the DFS picks depends on traversal order and is
     * not a contract. What matters is that the cycle is not drawn as arrows the
     * whole way round.
     */
    @Test
    fun `the cycle is broken by at least one stub`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        val cycleEdges = setOf("e-9", "e-13", "e-14")
        val stubbed = layout.edges.filter { it.kind == EdgeKind.STUB }.map { it.answerId }
        assertTrue(
            stubbed.any { it in cycleEdges },
            "expected one of $cycleEdges to be a stub, stubs were $stubbed"
        )
    }

    /**
     * Both chips or neither. The inbound chip is what tells a user that something
     * points at this node; without it they will restructure or delete it wrongly.
     */
    @Test
    fun `every stub emits reciprocal chips on both ends`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        val stubs = layout.edges.filter { it.kind == EdgeKind.STUB }
        assertTrue(stubs.isNotEmpty(), "the sample has a cycle, so it must produce stubs")

        stubs.forEach { edge ->
            val target = edge.targetId!!
            val outgoing = layout.chips.single {
                it.onNodeId == edge.sourceId && it.otherNodeId == target && it.outgoing
            }
            val inbound = layout.chips.single {
                it.onNodeId == target && it.otherNodeId == edge.sourceId && !it.outgoing
            }
            assertContains(outgoing.text, edge.label)
            assertContains(inbound.text, graph.byId.getValue(edge.sourceId).title)
        }
    }

    @Test
    fun `answer with no target is dangling`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", null))
        )
        val layout = LayoutEngine.layout(graph, strictHeights(graph))
        assertEquals(EdgeKind.DANGLING, layout.edges.single().kind)
    }

    @Test
    fun `unreachable nodes are parked below the last real layer`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "Yes", "b")),
            Fixtures.node("b"),
            Fixtures.node("island")
        )
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        assertContains(layout.orphans, "island")
        assertTrue(
            layout.positions.getValue("island").layer > layout.positions.getValue("b").layer,
            "orphan band must sit below the reachable layers"
        )
    }

    /** Depth is the longest path, so a node is always below every parent. */
    @Test
    fun `diamond puts the join below both branches`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node("a", Fixtures.answer("e1", "L", "b"), Fixtures.answer("e2", "R", "c")),
            Fixtures.node("b", Fixtures.answer("e3", "on", "c")),
            Fixtures.node("c")
        )
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        assertEquals(0, layout.positions.getValue("a").layer)
        assertEquals(1, layout.positions.getValue("b").layer)
        assertEquals(2, layout.positions.getValue("c").layer, "longest path wins over shortest")
    }

    @Test
    fun `layout is deterministic so nothing about position needs persisting`() {
        val graph = Fixtures.example()
        val first = LayoutEngine.layout(graph, strictHeights(graph))
        val second = LayoutEngine.layout(graph, strictHeights(graph))
        assertEquals(first.positions, second.positions)
        assertEquals(first.layers, second.layers)
    }

    @Test
    fun `empty graph does not blow up`() {
        val graph = Graph(graphId = "g", name = "empty")
        val layout = LayoutEngine.layout(graph) { 64f }
        assertTrue(layout.positions.isEmpty())
    }
}
