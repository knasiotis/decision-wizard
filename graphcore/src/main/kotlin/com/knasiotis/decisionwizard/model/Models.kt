@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.knasiotis.decisionwizard.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val CURRENT_SCHEMA_VERSION = 1

@Serializable
data class Graph(
    // Written even when they equal the default. `encodeDefaults = false` keeps the
    // file small and hand-editable, but these two drive the whole import/update
    // decision — a v1 export that omits them is indistinguishable from a file with
    // no version at all.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val graphId: String,
    val name: String,
    val description: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val revision: Int = 1,
    val updatedAt: String = "",
    val rootNodeId: String? = null,
    val nodes: List<Node> = emptyList()
) {
    /** Built on load, never persisted. */
    val byId: Map<String, Node> by lazy { nodes.associateBy { it.id } }

    fun node(id: String?): Node? = id?.let { byId[it] }

    fun replaceNode(updated: Node): Graph =
        copy(nodes = nodes.map { if (it.id == updated.id) updated else it })

    fun addNode(node: Node): Graph = copy(nodes = nodes + node)

    fun removeNode(nodeId: String): Graph = copy(nodes = nodes.filterNot { it.id == nodeId })
}

@Serializable
data class Node(
    val id: String,
    val title: String,
    val body: String = "",
    val snippets: List<Snippet> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val answers: List<Answer> = emptyList()
) {
    /** A node with no answers is an endpoint. There is no separate flag. */
    val isEndpoint: Boolean get() = answers.isEmpty()

    fun withAnswer(answer: Answer): Node = copy(answers = answers + answer)

    fun retarget(answerId: String, targetNodeId: String?): Node =
        copy(answers = answers.map {
            if (it.id == answerId) it.copy(targetNodeId = targetNodeId) else it
        })
}

@Serializable
data class Answer(
    val id: String,
    val label: String,
    /** null means the branch exists but has no child yet. Legal, not an error. */
    val targetNodeId: String? = null
)

@Serializable
data class Snippet(
    val id: String,
    val label: String,
    val text: String
)

@Serializable
data class Attachment(
    val id: String,
    val kind: String,
    /** Relative path inside the export bundle, for kind == "image". */
    val path: String? = null,
    /** Absolute URL, for kind == "link". */
    val url: String? = null,
    val caption: String = ""
) {
    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_LINK = "link"
    }
}

/**
 * One traversed edge in a chat session. Records the answer id, not just the
 * node pair, because two answers on the same node may point at the same child.
 */
@Serializable
data class TranscriptStep(
    val nodeId: String,
    @SerialName("answer_id") val answerId: String?,
    val answerLabel: String?,
    val timestamp: String
)

val GraphJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

fun parseGraph(text: String): Graph = GraphJson.decodeFromString(Graph.serializer(), text)

fun Graph.toJson(): String = GraphJson.encodeToString(Graph.serializer(), this)

fun newId(prefix: String): String =
    prefix + "-" + java.util.UUID.randomUUID().toString().take(8)
