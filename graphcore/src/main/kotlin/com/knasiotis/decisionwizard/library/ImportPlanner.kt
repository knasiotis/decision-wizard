package com.knasiotis.decisionwizard.library

import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Issue
import com.knasiotis.decisionwizard.model.Severity
import com.knasiotis.decisionwizard.model.GraphValidator

/**
 * Enough of a library entry to decide an import against, without loading every
 * graph body out of the database.
 */
data class GraphSummary(
    val graphId: String,
    val name: String,
    val revision: Int
)

/** What the import sheet should offer. */
sealed interface ImportPlan {

    /**
     * Duplicate node or answer ids make a file impossible to load
     * unambiguously, so it is refused rather than silently mangled. This is the
     * only case where the app blocks the user.
     */
    data class Rejected(val fatal: List<Issue>) : ImportPlan

    /** Nothing in the library shares this graphId. Just add it. */
    data class AddNew(val incoming: Graph, val warnings: List<Issue>) : ImportPlan

    /** The library already holds this graphId. */
    data class Conflict(
        val existing: GraphSummary,
        val incoming: Graph,
        val warnings: List<Issue>,
        /**
         * True only when the incoming revision is higher. Overwriting the
         * library copy with an older or equal revision would silently discard
         * work, so the sheet offers duplication alone in that case.
         */
        val canUpdate: Boolean
    ) : ImportPlan
}

/**
 * The whole sync story between two people: same `graphId` with a higher
 * `revision` means an update, anything else means a duplicate.
 */
object ImportPlanner {

    fun plan(incoming: Graph, library: List<GraphSummary>): ImportPlan {
        val issues = GraphValidator.validate(incoming)
        val fatal = issues.filter { it.severity == Severity.FATAL }
        if (fatal.isNotEmpty()) return ImportPlan.Rejected(fatal)

        val warnings = issues.filter { it.severity == Severity.WARNING }
        val existing = library.firstOrNull { it.graphId == incoming.graphId }
            ?: return ImportPlan.AddNew(incoming, warnings)

        return ImportPlan.Conflict(
            existing = existing,
            incoming = incoming,
            warnings = warnings,
            canUpdate = incoming.revision > existing.revision
        )
    }

    /**
     * A duplicate is a new document lineage, so it takes a fresh [newGraphId]
     * and its revision restarts at 1. Without a new id the copy would collide
     * with the original on the next import.
     *
     * Node and answer ids are left alone: they are scoped to a single graph, so
     * two graphs sharing them cannot conflict, and rewriting them would make the
     * file needlessly unlike the one it was copied from.
     */
    fun duplicate(source: Graph, newGraphId: String, takenNames: Set<String>): Graph =
        source.copy(
            graphId = newGraphId,
            name = uniqueName("${source.name} (copy)", takenNames),
            revision = 1
        )

    /** Appends " (2)", " (3)" … until the name is free. */
    fun uniqueName(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        var n = 2
        while ("$desired ($n)" in taken) n++
        return "$desired ($n)"
    }
}
