package com.knasiotis.decisionwizard.model

enum class Severity { FATAL, WARNING }

data class Issue(
    val severity: Severity,
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val answerId: String? = null
)

/**
 * Nothing here blocks editing. WARNING issues surface as badges on the canvas.
 * FATAL issues only stop an import — a file with duplicate ids cannot be loaded
 * unambiguously, so it is rejected rather than silently mangled.
 */
object GraphValidator {

    fun validate(graph: Graph): List<Issue> {
        val issues = mutableListOf<Issue>()
        issues += duplicateIds(graph)
        issues += rootIssues(graph)
        issues += edgeIssues(graph)
        issues += orphanIssues(graph)
        issues += attachmentIssues(graph)
        return issues
    }

    fun isImportable(graph: Graph): Boolean =
        validate(graph).none { it.severity == Severity.FATAL }

    private fun duplicateIds(graph: Graph): List<Issue> {
        val issues = mutableListOf<Issue>()

        graph.nodes.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach {
                issues += Issue(
                    Severity.FATAL, "duplicate_node_id",
                    "More than one node uses the id \"$it\".", nodeId = it
                )
            }

        graph.nodes.flatMap { it.answers }
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach {
                issues += Issue(
                    Severity.FATAL, "duplicate_answer_id",
                    "More than one answer uses the id \"$it\".", answerId = it
                )
            }

        return issues
    }

    private fun rootIssues(graph: Graph): List<Issue> = when {
        graph.rootNodeId == null -> listOf(
            Issue(Severity.WARNING, "no_root", "This graph has no starting question yet.")
        )
        graph.byId[graph.rootNodeId] == null -> listOf(
            Issue(
                Severity.WARNING, "missing_root",
                "The starting node \"${graph.rootNodeId}\" does not exist.",
                nodeId = graph.rootNodeId
            )
        )
        else -> emptyList()
    }

    /**
     * An answer with no target is **not** reported. `targetNodeId: null` is a
     * documented legal state — the branch exists and has not been built out yet
     * — and it is the normal condition of every question the moment it is
     * created. Warning about it made a brand-new node look broken and buried the
     * warnings that mean something.
     */
    private fun edgeIssues(graph: Graph): List<Issue> =
        graph.nodes.flatMap { node ->
            node.answers.mapNotNull { answer ->
                when {
                    answer.targetNodeId == null -> null
                    graph.byId[answer.targetNodeId] == null -> Issue(
                        Severity.WARNING, "broken_target",
                        "\"${answer.label}\" points at a node that no longer exists.",
                        nodeId = node.id, answerId = answer.id
                    )
                    else -> null
                }
            }
        }

    private fun orphanIssues(graph: Graph): List<Issue> {
        val reachable = reachableFrom(graph, graph.rootNodeId)
        return graph.nodes
            .filter { it.id !in reachable }
            .map {
                Issue(
                    Severity.WARNING, "orphan",
                    "\"${it.title}\" cannot be reached from the start.",
                    nodeId = it.id
                )
            }
    }

    private fun attachmentIssues(graph: Graph): List<Issue> =
        graph.nodes.flatMap { node ->
            node.attachments.mapNotNull { att ->
                when {
                    att.kind == Attachment.KIND_IMAGE && att.path.isNullOrBlank() -> Issue(
                        Severity.WARNING, "attachment_no_path",
                        "An image on \"${node.title}\" has no file.", nodeId = node.id
                    )
                    att.kind == Attachment.KIND_LINK && att.url.isNullOrBlank() -> Issue(
                        Severity.WARNING, "attachment_no_url",
                        "A link on \"${node.title}\" has no address.", nodeId = node.id
                    )
                    else -> null
                }
            }
        }

    /** Iterative so a cyclic graph cannot blow the stack. */
    fun reachableFrom(graph: Graph, startId: String?): Set<String> {
        val start = startId ?: return emptySet()
        if (graph.byId[start] == null) return emptySet()

        val seen = linkedSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(start) }

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            graph.byId[id]?.answers?.forEach { answer ->
                answer.targetNodeId
                    ?.takeIf { it !in seen && graph.byId[it] != null }
                    ?.let { queue.add(it) }
            }
        }
        return seen
    }

    /** Nodes that would become unreachable if [nodeId] were deleted. */
    fun orphansAfterDeleting(graph: Graph, nodeId: String): Set<String> {
        val before = reachableFrom(graph, graph.rootNodeId)
        val after = reachableFrom(graph.removeNode(nodeId), graph.rootNodeId)
        return (before - after) - setOf(nodeId)
    }
}
