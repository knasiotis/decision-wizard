package com.knasiotis.decisionwizard.chat

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Node
import com.knasiotis.decisionwizard.model.Snippet
import kotlinx.serialization.Serializable

/** One answer as it was offered at the time it was offered. */
@Serializable
data class TurnOption(val id: String, val label: String)

/**
 * A question exactly as it was put, captured the moment it was reached.
 *
 * **Nothing already on screen is ever re-read from the graph.** A chat is a
 * record of a conversation that happened; editing the flow afterwards cannot
 * change what was said, and that includes the question currently waiting for an
 * answer — it was asked already.
 */
@Serializable
data class TurnRecord(
    val nodeId: String,
    val question: String,
    val detail: String = "",
    val snippets: List<Snippet> = emptyList(),
    val options: List<TurnOption> = emptyList()
)

/** A recorded question together with the answer taken on it. */
@Serializable
data class Answered(
    val turn: TurnRecord,
    val answerId: String
)

/**
 * A whole session, and a complete account on its own: it can be rendered with no
 * graph at all, which is what lets a chat outlive the graph it ran on.
 */
@Serializable
data class ChatState(
    val answered: List<Answered> = emptyList(),
    /** The question waiting for an answer, as it was put. Null at a dead end. */
    val current: TurnRecord? = null
)

/** A question as the chat should render it. */
data class ChatTurn(
    val record: TurnRecord,
    /** The answer taken. Null means this question is still waiting. */
    val chosenAnswerId: String?,
    /** Index into [ChatState.answered], or -1 for the live question. */
    val stepIndex: Int
) {
    val isLive: Boolean get() = chosenAnswerId == null
    val nodeId: String get() = record.nodeId
    val question: String get() = record.question
    val detail: String get() = record.detail
    val snippets: List<Snippet> get() = record.snippets
    val options: List<TurnOption> get() = record.options
}

/**
 * Traversal is instant — there is no model and nothing to wait for. Never add
 * artificial latency to imitate one.
 *
 * The graph is consulted at exactly one moment: **when an answer is tapped**, to
 * find where that answer leads and to capture the question it leads to. Every
 * other operation, rendering included, works from the record alone.
 */
object ChatEngine {

    private fun capture(node: Node) = TurnRecord(
        nodeId = node.id,
        question = node.title,
        detail = node.body,
        snippets = node.snippets,
        options = node.answers.map { TurnOption(it.id, it.label) }
    )

    fun start(graph: Graph): ChatState =
        ChatState(current = graph.node(graph.rootNodeId)?.let(::capture))

    /** True once the chat reaches a question with no answers. */
    fun isFinished(state: ChatState): Boolean = state.current?.options?.isEmpty() == true

    /**
     * True when the last answer taken had no target. A legal graph state, not an
     * error — the branch simply has not been built out yet.
     */
    fun isDeadEnd(state: ChatState): Boolean =
        state.current == null && state.answered.isNotEmpty()

    /**
     * The question this chat is waiting on no longer exists in the graph, so it
     * cannot go any further. What was already said is unaffected.
     */
    fun isBroken(graph: Graph?, state: ChatState): Boolean {
        val current = state.current ?: return false
        return graph != null && graph.node(current.nodeId) == null
    }

    /**
     * Take [answerId] and walk on.
     *
     * This is the one place the graph is read: where the answer leads is looked
     * up **now**, so the next question follows the flow as it currently stands.
     * That question is then captured, and from this point on it is history and
     * will not change again.
     *
     * Returns null when the answer is not on the live question, or when the
     * question or answer has since been deleted from the graph — a stale tap
     * cannot corrupt the record.
     */
    fun answer(graph: Graph, state: ChatState, answerId: String): ChatState? {
        val current = state.current ?: return null
        if (current.options.none { it.id == answerId }) return null

        val liveNode = graph.node(current.nodeId) ?: return null
        val answer = liveNode.answers.firstOrNull { it.id == answerId } ?: return null

        return ChatState(
            answered = state.answered + Answered(current, answerId),
            current = answer.targetNodeId?.let { graph.node(it) }?.let(::capture)
        )
    }

    /**
     * Put the chat back to the question at [stepIndex], discarding everything
     * after it. That question returns exactly as it was put.
     */
    fun rewindTo(state: ChatState, stepIndex: Int): ChatState? {
        val step = state.answered.getOrNull(stepIndex) ?: return null
        return ChatState(
            answered = state.answered.take(stepIndex),
            current = step.turn
        )
    }

    /**
     * Rewind to [stepIndex] and immediately take [answerId], so switching a
     * branch is one gesture. The re-answer goes through [answer], so where it
     * leads is resolved against the graph as it stands now.
     */
    fun rewindAndAnswer(
        graph: Graph,
        state: ChatState,
        stepIndex: Int,
        answerId: String
    ): ChatState? = rewindTo(state, stepIndex)?.let { answer(graph, it, answerId) }

    /**
     * The render list. Takes no graph, deliberately: everything on screen has
     * already been recorded, so there is nothing left to look up.
     */
    fun turns(state: ChatState): List<ChatTurn> {
        val out = state.answered.mapIndexed { index, step ->
            ChatTurn(step.turn, step.answerId, index)
        }.toMutableList()

        state.current?.let { out += ChatTurn(it, null, -1) }
        return out
    }
}
