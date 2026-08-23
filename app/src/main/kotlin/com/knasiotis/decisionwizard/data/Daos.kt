package com.knasiotis.decisionwizard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    /** REPLACE is what makes "update an existing graph" a single call. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(graph: GraphEntity)

    @Query("DELETE FROM graphs WHERE graphId = :graphId")
    suspend fun delete(graphId: String)

    @Query("SELECT COUNT(*) FROM graphs")
    fun count(): Flow<Int>
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY lastOpenedAt DESC")
    fun all(): Flow<List<SessionEntity>>

    /** Backs the "resume last session" launch preference. */
    @Query("SELECT * FROM sessions ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun mostRecentlyOpened(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun byId(sessionId: String): SessionEntity?

    /** For the delete confirmation: "3 chats will also be deleted". */
    @Query("SELECT COUNT(*) FROM sessions WHERE graphId = :graphId")
    suspend fun countForGraph(graphId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
