package com.knasiotis.decisionwizard.editor

import com.knasiotis.decisionwizard.model.Answer
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator
import com.knasiotis.decisionwizard.model.Node
import com.knasiotis.decisionwizard.model.newId

/**
 * The four options behind the delete button. Which ones the sheet offers depends
 * on the node: SPLICE only appears when the node has exactly one child.
 */
object DeleteOps {

    /** Which options to show, and the numbers to show alongside them. */
    data class DeletePreview(
        val nodeId: String,
        val childCount: Int,
        val inboundCount: Int,
        val orphanCount: Int,
        val spliceAvailable: Boolean
    )

    fun preview(graph: Graph, nodeId: String): DeletePreview {
        val node = graph.byId[nodeId]
        val children = node?.answers?.mapNotNull { it.targetNodeId }?.distinct().orEmpty()
        return DeletePreview(
            nodeId = nodeId,
            childCount = children.size,
            inboundCount = inboundEdges(graph, nodeId).size,
            orphanCount = GraphValidator.orphansAfterDeleting(graph, nodeId).size,
            spliceAvailable = children.size == 1
        )
    }

    fun inboundEdges(graph: Graph, nodeId: String): List<Pair<Node, Answer>> =
        graph.nodes.flatMap { parent ->
            parent.answers.filter { it.targetNodeId == nodeId }.map { parent to it }
        }

    /**
     * Remove the node. Inbound answers become dangling; children stay and may
     * become orphans, which the canvas greys out.
     */
    fun deleteOnly(graph: Graph, nodeId: String): Graph =
        graph.removeNode(nodeId).copy(
            nodes = graph.removeNode(nodeId).nodes.map { node ->
                node.copy(answers = node.answers.map {
                    if (it.targetNodeId == nodeId) it.copy(targetNodeId = null) else it
                })
            }
        )

    /** Remove the node plus everything that becomes unreachable because of it. */
    fun deleteAndPurge(graph: Graph, nodeId: String): Graph {
        val doomed = GraphValidator.orphansAfterDeleting(graph, nodeId) + nodeId
        return graph.copy(
            nodes = graph.nodes
                .filterNot { it.id in doomed }
                .map { node ->
                    node.copy(answers = node.answers.map {
                        if (it.targetNodeId in doomed) it.copy(targetNodeId = null) else it
                    })
                }
        )
    }

    /**
     * Only valid when the node has exactly one child. Every inbound answer is
     * repointed straight at that child, keeping its own label. This is the
     * common case when tidying up a flow.
     */
    fun splice(graph: Graph, nodeId: String): Graph? {
        val child = graph.byId[nodeId]?.answers
            ?.mapNotNull { it.targetNodeId }
            ?.distinct()
            ?.singleOrNull()
            ?: return null

        return graph.copy(
            nodes = graph.nodes
                .filterNot { it.id == nodeId }
                .map { node ->
                    node.copy(answers = node.answers.map {
                        if (it.targetNodeId == nodeId) it.copy(targetNodeId = child) else it
                    })
                }
        )
    }

    /**
     * Move the node's children onto [adoptiveNodeId], carrying their labels across
     * as new answers. The node's own inbound answers become dangling.
     */
    fun deleteAndReparent(graph: Graph, nodeId: String, adoptiveNodeId: String): Graph? {
        val node = graph.byId[nodeId] ?: return null
        val adoptive = graph.byId[adoptiveNodeId] ?: return null
        if (adoptiveNodeId == nodeId) return null

        val existingTargets = adoptive.answers.mapNotNull { it.targetNodeId }.toSet()
        val adopted = node.answers
            .filter { it.targetNodeId != null && it.targetNodeId !in existingTargets }
            .map { Answer(newId("e"), it.label, it.targetNodeId) }

        val updatedAdoptive = adoptive.copy(answers = adoptive.answers + adopted)

        return graph
            .replaceNode(updatedAdoptive)
            .removeNode(nodeId)
            .let { g ->
                g.copy(nodes = g.nodes.map { n ->
                    n.copy(answers = n.answers.map {
                        if (it.targetNodeId == nodeId) it.copy(targetNodeId = null) else it
                    })
                })
            }
    }
}
