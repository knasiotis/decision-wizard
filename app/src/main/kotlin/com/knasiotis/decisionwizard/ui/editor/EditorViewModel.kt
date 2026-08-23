package com.knasiotis.decisionwizard.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.editor.DeleteOps
import com.knasiotis.decisionwizard.editor.GraphEditor
import com.knasiotis.decisionwizard.layout.GraphLayout
import com.knasiotis.decisionwizard.layout.LayoutEngine
import com.knasiotis.decisionwizard.model.Answer
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator
import com.knasiotis.decisionwizard.model.Issue
import com.knasiotis.decisionwizard.model.Node
import com.knasiotis.decisionwizard.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of DeleteOps' four behaviours the sheet asked for. */
enum class DeleteMode { SPLICE, ONLY, PURGE, REPARENT }

data class EditorUiState(
    val graph: Graph? = null,
    val layout: GraphLayout? = null,
    val issuesByNode: Map<String, List<Issue>> = emptyMap(),
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

/**
 * Owns the [GraphEditor] and republishes a snapshot after every operation.
 *
 * `GraphEditor.graph` is a plain `var` on purpose: making it observable would
 * mean putting `androidx.compose.runtime` into `:graphcore`, which is supposed to
 * stay framework-free and JVM-testable. Recomposition is driven from here
 * instead, so the core needs to know nothing about Compose.
 */
class EditorViewModel(
    private val repository: LibraryRepository,
    private val graphId: String
) : ViewModel() {

    private var editor: GraphEditor? = null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val graph = repository.load(graphId)
            if (graph == null) {
                _state.value = EditorUiState(loading = false)
                return@launch
            }
            editor = GraphEditor(graph)
            publish()
        }
    }

    fun undo() {
        editor?.undo() ?: return
        publish(dirty = true)
    }

    fun redo() {
        editor?.redo() ?: return
        publish(dirty = true)
    }

    /** Bumps the revision, which is what makes the other person's import offer an update. */
    fun save() {
        val graph = editor?.graph ?: return
        viewModelScope.launch {
            repository.save(graph.copy(revision = graph.revision + 1))
            _state.value = _state.value.copy(dirty = false)
        }
    }

    // --- structural edits: one undo step each, immediately ---

    fun renameGraph(name: String) {
        val editor = editor ?: return
        val clean = name.trim().ifBlank { return }
        editor.applyStructural(editor.graph.copy(name = clean), "Renamed graph", null)
        publish(dirty = true)
    }

    /**
     * The condition comes from the parent's answer list, never free text:
     * either an existing answer with no target yet, or a new answer added here.
     */
    fun addChild(parentId: String, answerId: String?, newLabel: String?) {
        val editor = editor ?: return
        val parent = editor.graph.byId[parentId] ?: return

        val childId = newId("n")
        val child = Node(
            id = childId,
            title = "New question",
            answers = listOf(Answer(newId("e"), "Yes"), Answer(newId("e"), "No"))
        )

        val linked = if (answerId != null) {
            parent.retarget(answerId, childId)
        } else {
            parent.withAnswer(Answer(newId("e"), newLabel?.trim().orEmpty().ifBlank { "Next" }, childId))
        }

        editor.applyStructural(
            editor.graph.addNode(child).replaceNode(linked),
            "Added question",
            childId
        )
        publish(dirty = true)
    }

    fun connectExisting(parentId: String, answerId: String?, newLabel: String?, targetId: String) {
        val editor = editor ?: return
        val parent = editor.graph.byId[parentId] ?: return

        val linked = if (answerId != null) {
            parent.retarget(answerId, targetId)
        } else {
            parent.withAnswer(Answer(newId("e"), newLabel?.trim().orEmpty().ifBlank { "Next" }, targetId))
        }

        editor.applyStructural(editor.graph.replaceNode(linked), "Connected", targetId)
        publish(dirty = true)
    }

    fun deletePreview(nodeId: String): DeleteOps.DeletePreview? =
        editor?.let { DeleteOps.preview(it.graph, nodeId) }

    fun delete(nodeId: String, mode: DeleteMode, adoptiveId: String? = null) {
        val editor = editor ?: return
        val graph = editor.graph

        val next = when (mode) {
            DeleteMode.SPLICE -> DeleteOps.splice(graph, nodeId)
            DeleteMode.ONLY -> DeleteOps.deleteOnly(graph, nodeId)
            DeleteMode.PURGE -> DeleteOps.deleteAndPurge(graph, nodeId)
            DeleteMode.REPARENT ->
                adoptiveId?.let { DeleteOps.deleteAndReparent(graph, nodeId, it) }
        } ?: return

        editor.applyStructural(next, "Deleted question", null)
        publish(dirty = true)
    }

    // --- text editing: staged per keystroke, one undo step when the sheet closes ---

    fun stageNodeText(nodeId: String, title: String, body: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        editor.stageDraft(editor.graph.replaceNode(node.copy(title = title, body = body)))
        publish(dirty = true)
    }

    fun stageAnswerLabel(nodeId: String, answerId: String, label: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        val updated = node.copy(
            answers = node.answers.map { if (it.id == answerId) it.copy(label = label) else it }
        )
        editor.stageDraft(editor.graph.replaceNode(updated))
        publish(dirty = true)
    }

    /** Called once when the editing sheet closes, collapsing the typing session. */
    fun commitEdits(nodeId: String) {
        editor?.commitDraft("Edited question", nodeId)
        publish(dirty = true)
    }

    private fun publish(dirty: Boolean = _state.value.dirty) {
        val editor = editor ?: return
        val graph = editor.graph

        _state.value = EditorUiState(
            graph = graph,
            // Derived from the graph every time — never cached, never persisted.
            layout = LayoutEngine.layout(graph),
            issuesByNode = GraphValidator.validate(graph)
                .filter { it.nodeId != null }
                .groupBy { it.nodeId!! },
            loading = false,
            dirty = dirty,
            canUndo = editor.canUndo,
            canRedo = editor.canRedo
        )
    }
}
