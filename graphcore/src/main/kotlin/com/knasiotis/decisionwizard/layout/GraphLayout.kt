package com.knasiotis.decisionwizard.layout

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator

/**
 * Spans longer than this become stub chips. The default; the editor lets the
 * user lower it to 1, because a line that crosses a whole layer has to thread a
 * corridor between other nodes and can be hard to follow however it is drawn.
 */
const val MAX_DRAWN_SPAN = 2

/** What the setting may be set to. One layer only, or up to two. */
val DRAWN_SPAN_CHOICES = listOf(1, 2)

const val NODE_WIDTH = 200f
const val NODE_H_GAP = 32f
const val LAYER_V_GAP = 80f

data class Position(val x: Float, val y: Float, val layer: Int, val indexInLayer: Int)

/** A corner of an edge's route, in the same dp space as [Position]. */
data class Point(val x: Float, val y: Float)

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
    val kind: EdgeKind,
    /**
     * Orthogonal polyline from the bottom of the source to the top of the
     * target, corner to corner. Empty unless [kind] is [EdgeKind.ARROW].
     *
     * Right angles rather than a straight line because a straight line across
     * two layers runs behind whatever sits in the layer between, which reads as
     * an edge to that node rather than past it.
     */
    val route: List<Point> = emptyList()
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

    fun layout(
        graph: Graph,
        nodeHeightOf: (String) -> Float = { 64f },
        /** Trailing, so the common `layout(graph) { height }` call still reads well. */
        maxDrawnSpan: Int = MAX_DRAWN_SPAN
    ): GraphLayout {
        val reachable = GraphValidator.reachableFrom(graph, graph.rootNodeId)
        val backEdges = findBackEdges(graph)
        val depths = assignDepths(graph, backEdges, reachable)

        val orphans = graph.nodes.map { it.id }.filter { it !in reachable }.toSet()
        val orphanLayer = (depths.values.maxOrNull() ?: -1) + 1
        val allDepths = depths + orphans.associateWith { orphanLayer }

        val layers = orderLayers(graph, allDepths)
        val positions = place(layers, nodeHeightOf)
        val (edges, chips) = classifyEdges(
            graph, allDepths, backEdges, layers, positions, nodeHeightOf, maxDrawnSpan
        )

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

    /**
     * Right-angled route from the bottom of the source to the top of the target.
     *
     * A hop to the next layer only has to dog-leg inside the empty band between
     * the two layers. A hop across a layer has to get past whatever is standing
     * in it, so it runs down a corridor — the middle of a gap between two of
     * that layer's nodes, or just outside the row — instead of straight through.
     */
    private fun routeBetween(
        source: Position,
        sourceHeight: Float,
        target: Position,
        /** Which of the source's drawn answers this is, and how many there are. */
        slot: Int,
        slots: Int,
        layers: List<List<String>>,
        positions: Map<String, Position>,
        nodeHeightOf: (String) -> Float
    ): List<Point> {
        // Each answer leaves from its own point along the bottom edge, and runs
        // along its own lane in the band below. Sharing either draws two answers
        // as one line that happens to fork, and they are different paths — the
        // reader cannot tell which branch they are following.
        val fromX = source.x + NODE_WIDTH * (slot + 1f) / (slots + 1f)
        val toX = target.x + NODE_WIDTH / 2
        val startY = source.y + sourceHeight
        val endY = target.y

        val crossed = (source.layer + 1) until target.layer
        val enterY = laneBelow(source.layer, slot, slots, layers, positions, nodeHeightOf)
        if (crossed.isEmpty()) {
            // The empty band rather than the midpoint of the two nodes: the
            // source may be the short one in its layer, and a run level with a
            // taller neighbour would cut straight through it.
            return simplify(
                listOf(
                    Point(fromX, startY),
                    Point(fromX, enterY),
                    Point(toX, enterY),
                    Point(toX, endY)
                )
            )
        }

        val corridorX = corridorThrough(crossed, layers, positions, (fromX + toX) / 2)
        val leaveY = bandBelow(target.layer - 1, layers, positions, nodeHeightOf)
        return simplify(
            listOf(
                Point(fromX, startY),
                Point(fromX, enterY),
                Point(corridorX, enterY),
                Point(corridorX, leaveY),
                Point(toX, leaveY),
                Point(toX, endY)
            )
        )
    }

    /** Middle of the empty band between a layer and the one under it. */
    private fun bandBelow(
        layerIndex: Int,
        layers: List<List<String>>,
        positions: Map<String, Position>,
        nodeHeightOf: (String) -> Float
    ): Float = laneBelow(layerIndex, 0, 1, layers, positions, nodeHeightOf)

    /**
     * One of [slots] evenly spaced lanes across the empty band under
     * [layerIndex]. A single slot is the middle of the band, which is what a
     * node with one drawn answer gets.
     *
     * The band is measured from the deepest bottom in the layer, not from this
     * node's own: the source may be the short one, and a run level with a taller
     * neighbour would cut straight through it.
     */
    private fun laneBelow(
        layerIndex: Int,
        slot: Int,
        slots: Int,
        layers: List<List<String>>,
        positions: Map<String, Position>,
        nodeHeightOf: (String) -> Float
    ): Float {
        val bottom = layers.getOrNull(layerIndex).orEmpty()
            .mapNotNull { id -> positions[id]?.let { it.y + nodeHeightOf(id) } }
            .maxOrNull() ?: 0f
        val next = layers.getOrNull(layerIndex + 1).orEmpty()
            .firstNotNullOfOrNull { positions[it]?.y }
            ?: (bottom + LAYER_V_GAP)
        return bottom + (next - bottom) * (slot + 1f) / (slots + 1f)
    }

    /**
     * An x with no node on it in any of [crossed], as near [preferredX] as
     * possible. Candidates are the middle of every gap between two neighbours
     * and the outsides of the widest row, which is why nothing has to move to
     * make room: the gaps are already there.
     */
    private fun corridorThrough(
        crossed: IntRange,
        layers: List<List<String>>,
        positions: Map<String, Position>,
        preferredX: Float
    ): Float {
        val spans = crossed
            .flatMap { layers.getOrNull(it).orEmpty() }
            .mapNotNull { positions[it] }
            .map { it.x to it.x + NODE_WIDTH }
            .sortedBy { it.first }

        val merged = mutableListOf<Pair<Float, Float>>()
        spans.forEach { (start, end) ->
            val last = merged.lastOrNull()
            if (last != null && start <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, end)
            } else {
                merged += start to end
            }
        }
        if (merged.isEmpty()) return preferredX

        val candidates = mutableListOf(
            merged.first().first - NODE_H_GAP / 2,
            merged.last().second + NODE_H_GAP / 2
        )
        for (i in 0 until merged.size - 1) {
            candidates += (merged[i].second + merged[i + 1].first) / 2
        }
        return candidates.minBy { kotlin.math.abs(it - preferredX) }
    }

    /** Drops repeated and collinear corners, so a straight run stays one line. */
    private fun simplify(points: List<Point>): List<Point> {
        val kept = mutableListOf<Point>()
        points.forEach { point ->
            if (kept.lastOrNull() != point) kept += point
        }
        var i = 1
        while (i < kept.size - 1) {
            val before = kept[i - 1]
            val after = kept[i + 1]
            val redundant = (before.x == kept[i].x && kept[i].x == after.x) ||
                (before.y == kept[i].y && kept[i].y == after.y)
            if (redundant) kept.removeAt(i) else i++
        }
        return kept
    }

    private fun classifyEdges(
        graph: Graph,
        depths: Map<String, Int>,
        backEdges: Set<String>,
        layers: List<List<String>>,
        positions: Map<String, Position>,
        nodeHeightOf: (String) -> Float,
        maxDrawnSpan: Int
    ): Pair<List<RenderEdge>, List<StubChip>> {
        val edges = mutableListOf<RenderEdge>()
        val chips = mutableListOf<StubChip>()

        graph.nodes.forEach { node ->
            // Slots along this node's bottom edge, one per answer that gets a
            // line. Answers that become chips or dangle take no slot, or the
            // drawn ones would be bunched to one side of a node whose other
            // answers are not drawn at all.
            val here = depths[node.id] ?: 0
            val drawnAnswers = node.answers.filter { candidate ->
                val to = candidate.targetNodeId ?: return@filter false
                if (graph.byId[to] == null) return@filter false
                val span = (depths[to] ?: 0) - here
                candidate.id !in backEdges && span in 1..maxDrawnSpan
            }
            val slotOf = drawnAnswers.withIndex().associate { (i, a) -> a.id to i }
            val slots = drawnAnswers.size

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
                val drawn = answer.id !in backEdges && span in 1..maxDrawnSpan

                val source = positions[node.id]
                val destination = positions[target]
                edges += RenderEdge(
                    answer.id, answer.label, node.id, target,
                    if (drawn) EdgeKind.ARROW else EdgeKind.STUB,
                    route = if (drawn && source != null && destination != null) {
                        routeBetween(
                            source, nodeHeightOf(node.id), destination,
                            slotOf[answer.id] ?: 0, slots,
                            layers, positions, nodeHeightOf
                        )
                    } else {
                        emptyList()
                    }
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

/**
 * Where an answer's label belongs on its route: the middle of its longest
 * horizontal run, or the midpoint by length when it has none.
 *
 * The horizontal run is the part that lies in the empty band between two layers,
 * and that band is clear of bubbles across the **whole width** of the canvas. A
 * label centred there cannot end up behind a node however wide the text is.
 *
 * The midpoint by length cannot promise that. It often lands on a vertical
 * segment running down beside a bubble, and an answer label — "It posts but will
 * not load Windows" — is many times wider than the 32dp gap between two columns,
 * so it disappears behind whatever is standing next to it. Short bubbles suffer
 * worst, which is why endpoints were where this showed up.
 */
fun labelAnchorOf(route: List<Point>): Point? {
    if (route.isEmpty()) return null
    val longestFlat = route.zipWithNext()
        .filter { (a, b) -> a.y == b.y && a.x != b.x }
        .maxByOrNull { (a, b) -> kotlin.math.abs(b.x - a.x) }
    longestFlat?.let { (a, b) -> return Point((a.x + b.x) / 2f, a.y) }
    return midpointOf(route)
}

/**
 * The point half way along a route by length. Kept because a route with no
 * horizontal run at all still needs somewhere to put the label. The middle of
 * the bounding box would fall off the line whenever the route dog-legs.
 */
fun midpointOf(route: List<Point>): Point? {
    if (route.isEmpty()) return null
    if (route.size == 1) return route.first()

    val lengths = route.zipWithNext { a, b ->
        kotlin.math.abs(b.x - a.x) + kotlin.math.abs(b.y - a.y)
    }
    val half = lengths.sum() / 2f
    var travelled = 0f
    lengths.forEachIndexed { i, length ->
        if (travelled + length >= half) {
            val t = if (length == 0f) 0f else (half - travelled) / length
            val a = route[i]
            val b = route[i + 1]
            return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        travelled += length
    }
    return route.last()
}
