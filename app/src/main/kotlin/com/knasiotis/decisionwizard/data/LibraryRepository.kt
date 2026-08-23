package com.knasiotis.decisionwizard.data

import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.library.GraphSummary
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.parseGraph
import com.knasiotis.decisionwizard.model.toJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * The seam between stored rows and the domain types in :graphcore. Everything
 * above this layer works in Graph and ChatState and never sees an entity.
 */
/** A resumed session and everything needed to render it. */
data class LoadedSession(
    /** Null once the graph has been deleted. The record still renders without it. */
    val graph: Graph?,
    val graphName: String,
    val state: ChatState,
    val title: String,
    val startedAt: Long,
    /** The graph is gone; this was rebuilt from the snapshot and cannot be answered. */
    val readOnly: Boolean
)

class LibraryRepository(
    private val graphs: GraphDao,
    private val sessions: SessionDao,
    private val now: () -> Long = System::currentTimeMillis
) {

    // --- graphs ---

    fun summaries(): Flow<List<GraphSummary>> = graphs.summaries()

    fun allGraphs(): Flow<List<GraphEntity>> = graphs.all()

    suspend fun load(graphId: String): Graph? =
        graphs.byId(graphId)?.let { parseGraph(it.body) }

    suspend fun takenNames(): Set<String> = graphs.allNames().toSet()

    /** Insert or overwrite by graphId. Both importing and updating land here. */
    suspend fun save(graph: Graph) {
        graphs.upsert(
            GraphEntity(
                graphId = graph.graphId,
                name = graph.name,
                description = graph.description,
                revision = graph.revision,
                updatedAt = graph.updatedAt,
                rootNodeId = graph.rootNodeId,
                body = graph.toJson(),
                savedAt = now()
            )
        )
    }

    /**
     * Chats are left alone. They keep a snapshot, so they survive as read-only
     * records instead of disappearing with the graph.
     */
    suspend fun deleteGraph(graphId: String) = graphs.delete(graphId)

    suspend fun sessionCount(graphId: String): Int = sessions.countForGraph(graphId)

    // --- sessions ---

    fun sessionList(): Flow<List<SessionListRow>> = sessions.list()

    suspend fun mostRecentSession(): SessionEntity? = sessions.mostRecentlyOpened()

    /**
     * The live graph if it still exists, so the next question follows the flow
     * as it is now. Its absence is reported rather than hidden: a chat that
     * cannot be continued must not look like one that can.
     */
    suspend fun loadSession(sessionId: String): LoadedSession? {
        val row = sessions.byId(sessionId) ?: return null

        // A row this build cannot read is treated as absent rather than thrown.
        // The blob is versioned by the database schema, so a stale one should
        // already have been cleared — but one unreadable row must never be able
        // to take the app down on launch.
        val state = runCatching {
            Json.decodeFromString(ChatState.serializer(), row.stateJson)
        }.getOrNull() ?: return null

        val live = load(row.graphId)

        return LoadedSession(
            graph = live,
            graphName = live?.name ?: row.graphName,
            state = state,
            title = row.title,
            startedAt = row.startedAt,
            readOnly = live == null
        )
    }

    suspend fun renameSession(sessionId: String, title: String) =
        sessions.updateTitle(sessionId, title)

    suspend fun saveSession(
        sessionId: String,
        graph: Graph,
        state: ChatState,
        title: String,
        startedAt: Long
    ) {
        sessions.upsert(
            SessionEntity(
                sessionId = sessionId,
                graphId = graph.graphId,
                title = title,
                graphRevision = graph.revision,
                stateJson = Json.encodeToString(ChatState.serializer(), state),
                graphName = graph.name,
                startedAt = startedAt,
                lastOpenedAt = now()
            )
        )
    }

    suspend fun deleteSession(sessionId: String) = sessions.delete(sessionId)

    /**
     * Applies the retention setting. [days] of 0 means keep everything, which is
     * the default — silently deleting the user's history is never the default.
     */
    suspend fun pruneSessions(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = now() - days * 24L * 60 * 60 * 1000
        return sessions.deleteOpenedBefore(cutoff)
    }
}
