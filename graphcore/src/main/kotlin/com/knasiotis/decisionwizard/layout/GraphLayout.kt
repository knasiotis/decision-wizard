package com.knasiotis.decisionwizard.layout

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator

/** Tune once you see real graphs. Spans longer than this become stub chips. */
const val MAX_DRAWN_SPAN = 2

const val NODE_WIDTH = 200f
const val NODE_H_GAP = 32f
const val LAYER_V_GAP = 80f

data class Position(val x: Float, val y: Float, val layer: Int, val indexInLayer: Int)

enum class EdgeKind {
    /** Short forward hop — draw a real arrow. */
    ARROW,

    /** Back-jump or long forward hop — draw reciprocal stub chips instead. */
    STUB,

    /** Answer with no target yet — draw a short arrow going nowhere. */
    DANGLING
}

data class RenderEdge(
    val answerId: String,
    val label: String,
    val sourceId: String,
    val targetId: String?,
    val kind: EdgeKind
)

data class StubChip(
    val onNodeId: String,
    val otherNodeId: String,
    val text: String,
    val outgoing: Boolean
)

data class GraphLayout(
    val positions: Map<String, Position>,
    val layers: List<List<String>>,
    val edges: List<RenderEdge>,
    val chips: List<StubChip>,
    val orphans: Set<String>,
    val width: Float,
    val height: Float
)

/**
 * Layered layout. Deterministic from the graph alone, so nothing about position
 * is ever persisted and an undo snapshot never has to store coordinates.
 *
 * Cycles are allowed. Back-edges are detected only so the depth pass terminates —
 * the user is never told and never blocked.
 */
object LayoutEngine {

    fun layout(graph: Graph, nodeHeightOf: (String) -> Float = { 64f }): GraphLayout {
        val reachable = GraphValidator.reachableFrom(graph, graph.rootNodeId)
        val backEdges = findBackEdges(graph)
        val depths = assignDepths(graph, backEdges, reachable)

        val orphans = graph.nodes.map { it.id }.filter { it !in reachable }.toSet()
        val orphanLayer = (depths.values.maxOrNull() ?: -1) + 1
        val allDepths = depths + orphans.associateWith { orphanLayer }

        val layers = orderLayers(graph, allDepths)
        val positions = place(layers, nodeHeightOf)
        val (edges, chips) = classifyEdges(graph, allDepths, backEdges)

        // Must agree with the canvas width `place` centred each row against.
        val widest = layers.maxOfOrNull { it.size } ?: 0
        val width = if (widest == 0) 0f else widest * NODE_WIDTH + (widest - 1) * NODE_H_GAP
        // Ask for the height of each real node. Never pass a synthetic id here —
        // the callback is backed by measured composables in the editor.
        val height = positions.entries.maxOfOrNull { (id, p) -> p.y + nodeHeightOf(id) } ?: 0f

        return GraphLayout(positions, layers, edges, chips, orphans, width, height)
    }

    /** Iterative DFS. An edge into a node currently on the stack is a back-edge. */
    private fun findBackEdges(graph: Graph): Set<String> {
        val root = graph.rootNodeId ?: return emptySet()
        val back = mutableSetOf<String>()
        val onStack = mutableSetOf<String>()
        val done = mutableSetOf<String>()

        fun visit(startId: String) {
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(startId to 0)
            onStack.add(startId)

            while (stack.isNotEmpty()) {
                val (id, index) = stack.removeLast()
                val answers = graph.byId[id]?.answers.orEmpty()

                if (index >= answers.size) {
                    onStack.remove(id)
                    done.add(id)
                    continue
                }

                stack.addLast(id to index + 1)
                val answer = answers[index]
                val target = answer.targetNodeId ?: continue
                if (graph.byId[target] == null) continue

                when {
                    target in onStack -> back.add(answer.id)
                    target !in done -> {
                        stack.addLast(target to 0)
                        onStack.add(target)
                    }
                }
            }
        }

        visit(root)
        graph.nodes.forEach { if (it.id !in done) visit(it.id) }
        return back
    }

    /** Longest path from the root, ignoring back-edges. */
    private fun assignDepths(
        graph: Graph,
        backEdges: Set<String>,
        reachable: Set<String>
    ): Map<String, Int> {
        val root = graph.rootNodeId ?: return emptyMap()
        if (root !in reachable) return emptyMap()

        val depth = mutableMapOf(root to 0)
        val indegree = mutableMapOf<String, Int>()
        reachable.forEach { indegree[it] = 0 }

        reachable.forEach { id ->
            graph.byId[id]?.answers.orEmpty()
                .filter { it.id !in backEdges }
                .mapNotNull { it.targetNodeId }
                .filter { it in reachable }
                .forEach { indegree[it] = (indegree[it] ?: 0) + 1 }
        }

        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        if (queue.isEmpty()) queue.add(root)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val here = depth.getOrPut(id) { 0 }
            graph.byId[id]?.answers.orEmpty()
                .filter { it.id !in backEdges }
                .mapNotNull { it.targetNodeId }
                .filter { it in reachable }
                .forEach { target ->
                    depth[target] = maxOf(depth[target] ?: 0, here + 1)
                    indegree[target] = (indegree[target] ?: 1) - 1
                    if (indegree[target] == 0) queue.add(target)
                }
        }

        reachable.forEach { depth.putIfAbsent(it, 0) }
        return depth
    }

    /** Two barycenter passes to cut crossings. Good enough; not optimal. */
    private fun orderLayers(graph: Graph, depths: Map<String, Int>): List<List<String>> {
        val maxDepth = depths.values.maxOrNull() ?: return emptyList()
        var layers = (0..maxDepth).map { d ->
            depths.filterValues { it == d }.keys.sorted().toMutableList()
        }

        repeat(2) {
            for (d in 1..maxDepth) {
                val above = layers[d - 1].withIndex().associate { (i, id) -> id to i.toFloat() }
                val parentsOf = mutableMapOf<String, MutableList<Float>>()

                layers[d - 1].forEach { parentId ->
                    graph.byId[parentId]?.answers.orEmpty()
                        .mapNotNull { it.targetNodeId }
                        .filter { depths[it] == d }
                        .forEach {
                            parentsOf.getOrPut(it) { mutableListOf() }
                                .add(above[parentId] ?: 0f)
                        }
                }

                layers[d].sortBy { id -> parentsOf[id]?.average()?.toFloat() ?: Float.MAX_VALUE }
            }
        }
        return layers.map { it.toList() }
    }

    private fun place(
        layers: List<List<String>>,
        nodeHeightOf: (String) -> Float
    ): Map<String, Position> {
        val positions = mutableMapOf<String, Position>()
        val widest = layers.maxOfOrNull { it.size } ?: 1
        val canvasWidth = widest * NODE_WIDTH + (widest - 1) * NODE_H_GAP
        var y = 0f

        layers.forEachIndexed { layerIndex, layer ->
            val rowWidth = layer.size * NODE_WIDTH + (layer.size - 1) * NODE_H_GAP
            var x = (canvasWidth - rowWidth) / 2f
            var tallest = 0f

            layer.forEachIndexed { indexInLayer, id ->
                positions[id] = Position(x, y, layerIndex, indexInLayer)
                x += NODE_WIDTH + NODE_H_GAP
                tallest = maxOf(tallest, nodeHeightOf(id))
            }
            y += tallest + LAYER_V_GAP
        }
        return positions
    }

    private fun classifyEdges(
        graph: Graph,
        depths: Map<String, Int>,
        backEdges: Set<String>
    ): Pair<List<RenderEdge>, List<StubChip>> {
        val edges = mutableListOf<RenderEdge>()
        val chips = mutableListOf<StubChip>()

        graph.nodes.forEach { node ->
            node.answers.forEach { answer ->
                val target = answer.targetNodeId
                val targetNode = graph.byId[target]

                if (target == null || targetNode == null) {
                    edges += RenderEdge(
                        answer.id, answer.label, node.id, null, EdgeKind.DANGLING
                    )
                    return@forEach
                }

                val from = depths[node.id] ?: 0
                val to = depths[target] ?: 0
                val span = to - from
                val drawn = answer.id !in backEdges && span in 1..MAX_DRAWN_SPAN

                edges += RenderEdge(
                    answer.id, answer.label, node.id, target,
                    if (drawn) EdgeKind.ARROW else EdgeKind.STUB
                )

                if (!drawn) {
                    chips += StubChip(
                        onNodeId = node.id,
                        otherNodeId = target,
                        text = "${answer.label} \u21a9 ${targetNode.title}",
                        outgoing = true
                    )
                    chips += StubChip(
                        onNodeId = target,
                        otherNodeId = node.id,
                        text = "\u21aa from: ${node.title}",
                        outgoing = false
                    )
                }
            }
        }
        return edges to chips
    }
}
