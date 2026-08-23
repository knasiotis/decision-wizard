package com.knasiotis.decisionwizard.chat

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Node
import kotlinx.serialization.Serializable

/**
 * One answered question. Records the answer id rather than the node pair,
 * because two answers on the same node may point at the same child and the node
 * pair alone would be ambiguous.
 */
@Serializable
data class Answered(val nodeId: String, val answerId: String)

/**
 * A whole session. Immutable and free of any graph reference, so it can be
 * persisted on its own and replayed against the graph it belongs to.
 *
 * Traversal is instant — there is no model and nothing to wait for. Never add
 * artificial latency to imitate one.
 */
@Serializable
data class ChatState(
    val answered: List<Answered> = emptyList(),
    val currentNodeId: String? = null
)

/** A question as the chat should render it. */
data class ChatTurn(
    val node: Node,
    /** The answer taken. Null means this is the live question. */
    val chosenAnswerId: String?,
    /** Index into [ChatState.answered], or -1 for the live question. */
    val stepIndex: Int
) {
    val isLive: Boolean get() = chosenAnswerId == null
}

object ChatEngine {

    fun start(graph: Graph): ChatState = ChatState(currentNodeId = graph.rootNodeId)

    fun currentNode(graph: Graph, state: ChatState): Node? = graph.node(state.currentNodeId)

    /** True once the session reaches a node with no answers. */
    fun isFinished(graph: Graph, state: ChatState): Boolean =
        currentNode(graph, state)?.isEndpoint == true

    /**
     * True when the last answer taken had no target. A legal graph state, not an
     * error — the branch simply has not been built out yet.
     */
    fun isDeadEnd(graph: Graph, state: ChatState): Boolean =
        state.currentNodeId == null && state.answered.isNotEmpty()

    /**
     * Take [answerId] from the current node. Returns null if that answer is not
     * on the current node, so a stale tap cannot corrupt the session.
     */
    fun answer(graph: Graph, state: ChatState, answerId: String): ChatState? {
        val node = currentNode(graph, state) ?: return null
        val answer = node.answers.firstOrNull { it.id == answerId } ?: return null
        return ChatState(
            answered = state.answered + Answered(node.id, answer.id),
            currentNodeId = answer.targetNodeId
        )
    }

    /**
     * Put the session back to the question at [stepIndex], discarding everything
     * after it. This is what tapping a past answer does.
     */
    fun rewindTo(state: ChatState, stepIndex: Int): ChatState? {
        val step = state.answered.getOrNull(stepIndex) ?: return null
        return ChatState(
            answered = state.answered.take(stepIndex),
            currentNodeId = step.nodeId
        )
    }

    /**
     * Rewind to [stepIndex] and immediately take [answerId]. Tapping a different
     * answer on an earlier question should switch that branch in one gesture, not
     * make the user rewind and then answer again.
     */
    fun rewindAndAnswer(
        graph: Graph,
        state: ChatState,
        stepIndex: Int,
        answerId: String
    ): ChatState? = rewindTo(state, stepIndex)?.let { answer(graph, it, answerId) }

    /**
     * The render list: every answered question in order, then the live one.
     * Derived from the state every time, never cached.
     */
    fun turns(graph: Graph, state: ChatState): List<ChatTurn> {
        val out = state.answered.mapIndexedNotNull { index, step ->
            graph.node(step.nodeId)?.let { ChatTurn(it, step.answerId, index) }
        }.toMutableList()

        graph.node(state.currentNodeId)?.let { out += ChatTurn(it, null, -1) }
        return out
    }
}
