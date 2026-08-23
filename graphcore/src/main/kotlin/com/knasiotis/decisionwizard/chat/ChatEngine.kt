package com.knasiotis.decisionwizard.chat

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Node
import com.knasiotis.decisionwizard.model.Snippet
import kotlinx.serialization.Serializable

/** One answer as it was offered at the time it was offered. */
@Serializable
data class TurnOption(val id: String, val label: String)

/**
 * A question that was answered, recorded as it stood at that moment.
 *
 * The wording is stored rather than looked up later on purpose: **a chat is a
 * record of a conversation that happened.** Editing the graph afterwards must
 * not rewrite what was said, any more than editing a script changes a recording
 * of the performance.
 */
@Serializable
data class Answered(
    val nodeId: String,
    val answerId: String,
    val question: String,
    val detail: String = "",
    val snippets: List<Snippet> = emptyList(),
    val options: List<TurnOption> = emptyList()
)

/**
 * A whole session. Immutable and free of any graph reference, so it can be
 * persisted on its own and read back without the graph it ran on.
 */
@Serializable
data class ChatState(
    val answered: List<Answered> = emptyList(),
    val currentNodeId: String? = null
)

/** A question as the chat should render it. */
data class ChatTurn(
    val nodeId: String,
    val question: String,
    val detail: String,
    val snippets: List<Snippet>,
    val options: List<TurnOption>,
    /** The answer taken. Null means this is the live question. */
    val chosenAnswerId: String?,
    /** Index into [ChatState.answered], or -1 for the live question. */
    val stepIndex: Int
) {
    val isLive: Boolean get() = chosenAnswerId == null
}

/**
 * Traversal is instant — there is no model and nothing to wait for. Never add
 * artificial latency to imitate one.
 *
 * The split that matters: answered turns come from the session's own records,
 * while the **live** question is resolved against the graph at the moment of
 * interaction. So the next step always reflects the current flow, and the steps
 * already taken never change.
 */
object ChatEngine {

    fun start(graph: Graph): ChatState = ChatState(currentNodeId = graph.rootNodeId)

    fun currentNode(graph: Graph?, state: ChatState): Node? =
        graph?.node(state.currentNodeId)

    /** True once the session reaches a node with no answers. */
    fun isFinished(graph: Graph?, state: ChatState): Boolean =
        currentNode(graph, state)?.isEndpoint == true

    /**
     * True when the last answer taken had no target. A legal graph state, not an
     * error — the branch simply has not been built out yet.
     */
    fun isDeadEnd(state: ChatState): Boolean =
        state.currentNodeId == null && state.answered.isNotEmpty()

    /**
     * The question this chat was sitting on has been deleted from the graph.
     * The record of what came before is intact, but it cannot go on.
     */
    fun isBroken(graph: Graph?, state: ChatState): Boolean =
        graph != null && state.currentNodeId != null && graph.node(state.currentNodeId) == null

    /**
     * Take [answerId] from the live question, reading it out of the graph as it
     * stands right now, and record what was on screen when it was taken.
     *
     * Returns null if that answer is not on the current node, so a stale tap —
     * or one aimed at an answer since deleted — cannot corrupt the session.
     */
    fun answer(graph: Graph, state: ChatState, answerId: String): ChatState? {
        val node = currentNode(graph, state) ?: return null
        val answer = node.answers.firstOrNull { it.id == answerId } ?: return null

        return ChatState(
            answered = state.answered + Answered(
                nodeId = node.id,
                answerId = answer.id,
                question = node.title,
                detail = node.body,
                snippets = node.snippets,
                options = node.answers.map { TurnOption(it.id, it.label) }
            ),
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
     * answer on an earlier question should switch that branch in one gesture.
     *
     * The re-answer goes through [answer], so it resolves against the current
     * graph: rewinding into a question that has since changed follows the flow
     * as it is now, not as it was.
     */
    fun rewindAndAnswer(
        graph: Graph,
        state: ChatState,
        stepIndex: Int,
        answerId: String
    ): ChatState? = rewindTo(state, stepIndex)?.let { answer(graph, it, answerId) }

    /**
     * The render list: every answered question from its own record, then the
     * live one from the graph. With no graph — because it was deleted — only the
     * record remains, which is still a complete account of what happened.
     */
    fun turns(graph: Graph?, state: ChatState): List<ChatTurn> {
        val out = state.answered.mapIndexed { index, step ->
            ChatTurn(
                nodeId = step.nodeId,
                question = step.question,
                detail = step.detail,
                snippets = step.snippets,
                options = step.options,
                chosenAnswerId = step.answerId,
                stepIndex = index
            )
        }.toMutableList()

        currentNode(graph, state)?.let { node ->
            out += ChatTurn(
                nodeId = node.id,
                question = node.title,
                detail = node.body,
                snippets = node.snippets,
                options = node.answers.map { TurnOption(it.id, it.label) },
                chosenAnswerId = null,
                stepIndex = -1
            )
        }
        return out
    }
}
