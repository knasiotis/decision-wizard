package com.knasiotis.decisionwizard.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The graph body stays a JSON blob rather than being shredded into node and
 * answer tables. The file format is the source of truth, so storing anything
 * else would mean two representations to keep in step.
 *
 * The columns beside [body] are denormalised copies of fields inside it, so the
 * library list can render without parsing every graph.
 */
@Entity(tableName = "graphs")
data class GraphEntity(
    @PrimaryKey val graphId: String,
    val name: String,
    val description: String,
    val revision: Int,
    /** The graph's own timestamp, carried in from the file. Informational. */
    val updatedAt: String,
    val rootNodeId: String?,
    val body: String,
    /** Local wall-clock time this row was written. Used for ordering. */
    val savedAt: Long
)

/**
 * A chat session. Stores only the answers taken, not the questions, so it
 * cannot be rendered without its graph — which is why deleting a graph cascades
 * to its sessions.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = GraphEntity::class,
            parentColumns = ["graphId"],
            childColumns = ["graphId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("graphId"), Index("lastOpenedAt")]
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val graphId: String,
    /**
     * What the user called this chat. Blank on sessions created before titles
     * existed, so the UI falls back to the graph name rather than showing an
     * empty row.
     */
    val title: String,
    /**
     * The revision this session was answered against. If the graph is later
     * updated, ChatEngine.turns() already skips nodes that no longer exist, so
     * an old session degrades rather than crashing — but this records that it
     * happened.
     */
    val graphRevision: Int,
    val stateJson: String,
    val startedAt: Long,
    val lastOpenedAt: Long
)
