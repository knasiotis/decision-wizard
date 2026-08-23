package com.knasiotis.decisionwizard.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.knasiotis.decisionwizard.library.GraphSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface GraphDao {

    /**
     * Projects straight into the type ImportPlanner consumes, so deciding an
     * import never loads a graph body.
     */
    @Query("SELECT graphId, name, revision FROM graphs ORDER BY name COLLATE NOCASE")
    fun summaries(): Flow<List<GraphSummary>>

    @Query("SELECT * FROM graphs ORDER BY savedAt DESC")
    fun all(): Flow<List<GraphEntity>>

    @Query("SELECT * FROM graphs WHERE graphId = :graphId")
    suspend fun byId(graphId: String): GraphEntity?

    @Query("SELECT name FROM graphs")
    suspend fun allNames(): List<String>

    /**
     * @Upsert, never @Insert(REPLACE). SQLite implements REPLACE as DELETE plus
     * INSERT, and that DELETE fires the ON DELETE CASCADE on sessions — so
     * saving a graph silently destroyed every chat that ran on it. @Upsert
     * compiles to ON CONFLICT DO UPDATE, which touches no other table.
     */
    @Upsert
    suspend fun upsert(graph: GraphEntity)

    @Query("DELETE FROM graphs WHERE graphId = :graphId")
    suspend fun delete(graphId: String)
}

/** One row of the Chats list, joined with its graph so the name is available. */
data class SessionListRow(
    val sessionId: String,
    val graphId: String,
    val title: String,
    val graphName: String,
    val graphRevision: Int,
    val sessionRevision: Int,
    val lastOpenedAt: Long,
    val stateJson: String
)

@Dao
interface SessionDao {

    /**
     * An INNER JOIN, so a session whose graph is gone simply never appears.
     * The cascade should prevent that, but the list should not depend on it.
     */
    @Query(
        """
        SELECT s.sessionId       AS sessionId,
               s.graphId         AS graphId,
               s.title           AS title,
               g.name            AS graphName,
               g.revision        AS graphRevision,
               s.graphRevision   AS sessionRevision,
               s.lastOpenedAt    AS lastOpenedAt,
               s.stateJson       AS stateJson
        FROM sessions s
        JOIN graphs g ON g.graphId = s.graphId
        ORDER BY s.lastOpenedAt DESC
        """
    )
    fun list(): Flow<List<SessionListRow>>

    /** Backs the "resume last session" launch preference. */
    @Query("SELECT * FROM sessions ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun mostRecentlyOpened(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun byId(sessionId: String): SessionEntity?

    /** For the delete confirmation: "3 chats will also be deleted". */
    @Query("SELECT COUNT(*) FROM sessions WHERE graphId = :graphId")
    suspend fun countForGraph(graphId: String): Int

    /** Same reasoning as graphs: never REPLACE a row other rows may depend on. */
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title WHERE sessionId = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    /**
     * Retention prune. Keyed on lastOpenedAt rather than startedAt, so a chat
     * someone keeps coming back to is never swept up because it began long ago.
     * Returns how many rows went.
     */
    @Query("DELETE FROM sessions WHERE lastOpenedAt < :cutoff")
    suspend fun deleteOpenedBefore(cutoff: Long): Int
}
