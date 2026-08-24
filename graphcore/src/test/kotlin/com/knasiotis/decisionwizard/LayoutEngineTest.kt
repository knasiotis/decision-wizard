package com.knasiotis.decisionwizard

import com.knasiotis.decisionwizard.layout.EdgeKind
import com.knasiotis.decisionwizard.layout.LayoutEngine
import com.knasiotis.decisionwizard.layout.NODE_WIDTH
import com.knasiotis.decisionwizard.layout.Point
import com.knasiotis.decisionwizard.layout.Position
import com.knasiotis.decisionwizard.layout.midpointOf
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator
import com.knasiotis.decisionwizard.model.parseGraph
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

    /**
     * The whole point of routing with right angles: a straight line from a
     * parent to a grandchild runs behind whatever is standing in the layer
     * between, which reads as an edge into that node rather than past it.
     */
    @Test
    fun `an edge across a layer misses the nodes standing in it`() {
        val graph = Fixtures.graph(
            "a",
            Fixtures.node(
                "a",
                Fixtures.answer("e1", "L", "b"),
                Fixtures.answer("e2", "R", "c"),
                // Straight past b and c, into the layer below them.
                Fixtures.answer("e3", "skip", "d")
            ),
            Fixtures.node("b", Fixtures.answer("e4", "on", "d")),
            Fixtures.node("c", Fixtures.answer("e5", "on", "d")),
            Fixtures.node("d")
        )
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        val long = layout.edges.single { it.answerId == "e3" }
        assertEquals(EdgeKind.ARROW, long.kind, "two layers is inside MAX_DRAWN_SPAN")

        val blockers = listOf("b", "c").map { layout.positions.getValue(it) }
        long.route.zipWithNext { from, to ->
            blockers.forEach { blocker ->
                assertTrue(
                    !crosses(from, to, blocker, 64f),
                    "segment $from -> $to runs through a node at $blocker"
                )
            }
        }
    }

    /** Not just the layer between: no segment may run over any node at all. */
    @Test
    fun `no route runs over a node`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
            edge.route.zipWithNext { from, to ->
                layout.positions.forEach { (id, node) ->
                    // The ends touch their own bubbles by definition.
                    if (id == edge.sourceId || id == edge.targetId) return@forEach
                    assertTrue(
                        !crosses(from, to, node, 64f),
                        "${edge.answerId}: $from -> $to runs over $id"
                    )
                }
            }
        }
    }

    @Test
    fun `routes are made of right angles only`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
            assertTrue(edge.route.size >= 2, "a drawn edge needs a route")
            edge.route.zipWithNext { from, to ->
                assertTrue(
                    from.x == to.x || from.y == to.y,
                    "$from -> $to is neither horizontal nor vertical"
                )
            }
        }
    }

    /**
     * The departure point is no longer the middle of the source. Each answer
     * leaves from its own slot along the bottom edge so two answers from one
     * node are not drawn as a single forking line, so what can be asserted is
     * that a route leaves from somewhere under its source — not from where
     * exactly.
     */
    @Test
    fun `a route starts under its source and ends on top of its target`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
            val source = layout.positions.getValue(edge.sourceId)
            val target = layout.positions.getValue(edge.targetId!!)
            val leaves = edge.route.first()
            assertTrue(
                leaves.x > source.x && leaves.x < source.x + NODE_WIDTH,
                "${edge.answerId} leaves from ${leaves.x}, outside its source"
            )
            assertEquals(source.y + 64f, leaves.y)
            assertEquals(target.x + NODE_WIDTH / 2, edge.route.last().x)
            assertEquals(target.y, edge.route.last().y)
        }
    }

    /**
     * Two answers from one node are two different paths. Drawn from the same
     * point and along the same lane they read as one line that happens to fork,
     * and the reader cannot tell which branch they are on.
     */
    @Test
    fun `answers leaving the same node share no line`() {
        listOf(Fixtures.example(), demoGraph()).forEach { graph ->
            val layout = LayoutEngine.layout(graph, wrappedHeights(graph))
            layout.edges
                .filter { it.kind == EdgeKind.ARROW }
                .groupBy { it.sourceId }
                .filterValues { it.size > 1 }
                .forEach { (sourceId, siblings) ->
                    val departures = siblings.map { it.route.first().x }
                    assertEquals(
                        departures.size, departures.toSet().size,
                        "${graph.name}/$sourceId: two answers leave from the same point"
                    )
                    // The horizontal run each one takes across the empty band.
                    val lanes = siblings.map { it.route[1].y }
                    assertEquals(
                        lanes.size, lanes.toSet().size,
                        "${graph.name}/$sourceId: two answers share a lane"
                    )
                }
        }
    }

    /** Only drawn edges carry geometry; a stub is two chips and nothing else. */
    @Test
    fun `stubs and dangling answers carry no route`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        layout.edges.filter { it.kind != EdgeKind.ARROW }.forEach {
            assertTrue(it.route.isEmpty(), "${it.answerId} is a ${it.kind} and should not be drawn")
        }
    }

    @Test
    fun `the label sits on the line, not beside it`() {
        val graph = Fixtures.example()
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
            val middle = midpointOf(edge.route)!!
            val onSome = edge.route.zipWithNext().any { (from, to) ->
                val between = { a: Float, b: Float, v: Float ->
                    v >= minOf(a, b) - 0.01f && v <= maxOf(a, b) + 0.01f
                }
                between(from.x, to.x, middle.x) && between(from.y, to.y, middle.y) &&
                    (from.x == to.x || from.y == to.y)
            }
            assertTrue(onSome, "label point $middle is off the route ${edge.route}")
        }
    }

    /**
     * The demo graph is what the F-Droid screenshots are taken of, and it is
     * shaped for that: no warnings to clutter the canvas, and exactly one stub
     * so there is a single reciprocal chip pair to photograph. Both are easy to
     * break by re-pointing one answer.
     */
    @Test
    fun `the demo graph stays shaped for its screenshots`() {
        val graph = parseGraph(
            checkNotNull(javaClass.getResourceAsStream("/pc-wont-turn-on.dwiz")) {
                "pc-wont-turn-on.dwiz missing from test resources"
            }.bufferedReader().readText()
        )
        val layout = LayoutEngine.layout(graph, strictHeights(graph))

        assertEquals(
            emptyList(), GraphValidator.validate(graph),
            "a badge on a bubble would clutter the canvas shot"
        )
        assertEquals(
            1, layout.edges.count { it.kind == EdgeKind.STUB },
            "one stub, so there is a single chip pair to photograph"
        )
        layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
            edge.route.zipWithNext { from, to ->
                layout.positions.forEach { (id, node) ->
                    if (id == edge.sourceId || id == edge.targetId) return@forEach
                    assertTrue(
                        !crosses(from, to, node, 64f),
                        "${edge.answerId}: $from -> $to runs over $id"
                    )
                }
            }
        }
    }

    /**
     * Every other height check here feeds in a flat 64, which is the one case
     * that cannot go wrong: layers have level bottoms and a route through the
     * band between them clears everything by construction. Real bubbles are as
     * tall as their wrapped title, so a layer's bottom is ragged and a route
     * computed for a short node can still run through a tall neighbour.
     *
     * Heights here vary with the title the way measured ones do, and the same
     * heights are used for the crossing test, so the two cannot quietly
     * disagree.
     */
    @Test
    fun `edges clear the nodes they pass when bubbles differ in height`() {
        listOf(Fixtures.example(), demoGraph()).forEach { graph ->
            val heightOf = wrappedHeights(graph)
            val layout = LayoutEngine.layout(graph, heightOf)

            layout.edges.filter { it.kind == EdgeKind.ARROW }.forEach { edge ->
                edge.route.zipWithNext { from, to ->
                    layout.positions.forEach { (id, node) ->
                        if (id == edge.sourceId || id == edge.targetId) return@forEach
                        assertTrue(
                            !crosses(from, to, node, heightOf(id)),
                            "${graph.name}/${edge.answerId}: $from -> $to runs over $id"
                        )
                    }
                }
            }
        }
    }

    /**
     * A layer's nodes must not overlap the layer under it whatever their
     * heights, or an edge drawn into the lower one arrives behind the upper.
     */
    @Test
    fun `layers keep clear of each other when bubbles differ in height`() {
        listOf(Fixtures.example(), demoGraph()).forEach { graph ->
            val heightOf = wrappedHeights(graph)
            val layout = LayoutEngine.layout(graph, heightOf)

            layout.layers.zipWithNext { upper, lower ->
                val deepest = upper.maxOf { layout.positions.getValue(it).y + heightOf(it) }
                val highest = lower.minOf { layout.positions.getValue(it).y }
                assertTrue(
                    highest >= deepest,
                    "${graph.name}: a layer starting at $highest overlaps one ending at $deepest"
                )
            }
        }
    }

    /** Roughly one extra line per 24 characters, as a 200dp-wide bubble wraps. */
    private fun wrappedHeights(graph: Graph): (String) -> Float = { id ->
        val node = requireNotNull(graph.byId[id]) {
            "layout asked for the height of unknown node id '$id'"
        }
        64f + (node.title.length / 24) * 20f
    }

    private fun demoGraph(): Graph = parseGraph(
        checkNotNull(javaClass.getResourceAsStream("/pc-wont-turn-on.dwiz")) {
            "pc-wont-turn-on.dwiz missing from test resources"
        }.bufferedReader().readText()
    )

    /** Does an axis-aligned segment pass over a node's box? */
    private fun crosses(from: Point, to: Point, node: Position, height: Float): Boolean {
        val left = node.x
        val right = node.x + NODE_WIDTH
        val top = node.y
        val bottom = node.y + height
        val minX = minOf(from.x, to.x)
        val maxX = maxOf(from.x, to.x)
        val minY = minOf(from.y, to.y)
        val maxY = maxOf(from.y, to.y)
        return maxX > left && minX < right && maxY > top && minY < bottom
    }

    @Test
    fun `empty graph does not blow up`() {
        val graph = Graph(graphId = "g", name = "empty")
        val layout = LayoutEngine.layout(graph) { 64f }
        assertTrue(layout.positions.isEmpty())
    }
}
