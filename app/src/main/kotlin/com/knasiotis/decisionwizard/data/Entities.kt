package com.knasiotis.decisionwizard.data

import androidx.room.Entity
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
 * A chat session: a record of a conversation that happened.
 *
 * Deliberately **no** foreign key to graphs. A chat outlives its graph: when the
 * graph is deleted the chat becomes a read-only record rather than disappearing.
 * Every answered turn stores its own wording, so the record stands on its own.
 */
@Entity(
    tableName = "sessions",
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
     * The revision this session last ran against. Answered turns are unaffected
     * by later edits — they carry their own wording — so this exists only to
     * tell the user the flow has moved on since.
     */
    val graphRevision: Int,
    val stateJson: String,
    /**
     * The graph's name, copied here so a chat can still say what it ran on after
     * the graph is deleted. No snapshot of the graph itself is needed: every
     * answered turn already carries its own wording.
     */
    val graphName: String,
    val startedAt: Long,
    val lastOpenedAt: Long
)
