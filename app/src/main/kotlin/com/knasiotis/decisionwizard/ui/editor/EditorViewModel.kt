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
import com.knasiotis.decisionwizard.model.Snippet
import com.knasiotis.decisionwizard.model.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which of DeleteOps' four behaviours the sheet asked for. */
enum class DeleteMode { SPLICE, ONLY, PURGE, REPARENT }

/**
 * Something to say and somewhere to look, after an edit or an undo.
 *
 * [id] increments so two identical edits in a row still announce twice —
 * without it the second would look like nothing happened.
 */
data class Announcement(
    val id: Long,
    val message: String,
    val focusNodeId: String?,
    /** Whether the announcement can be taken back, i.e. offers UNDO. */
    val undoable: Boolean
)

data class EditorUiState(
    val graph: Graph? = null,
    val layout: GraphLayout? = null,
    val issuesByNode: Map<String, List<Issue>> = emptyMap(),
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val announcement: Announcement? = null
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
    private val graphId: String,
    private val appScope: CoroutineScope
) : ViewModel() {

    private var editor: GraphEditor? = null
    private var announcements = 0L

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

    /**
     * An undo that changes something off-screen looks like a broken button, so
     * it says what it took back and points at where it happened.
     */
    fun undo() {
        val snapshot = editor?.undo() ?: return
        publish(dirty = true, announcement = announce(
            message = "Undone: ${snapshot.description}",
            focusNodeId = snapshot.focusNodeId,
            undoable = false
        ))
    }

    fun redo() {
        val snapshot = editor?.redo() ?: return
        publish(dirty = true, announcement = announce(
            message = snapshot.description,
            focusNodeId = snapshot.focusNodeId,
            undoable = false
        ))
    }

    fun clearAnnouncement() {
        _state.value = _state.value.copy(announcement = null)
    }

    private fun announce(message: String, focusNodeId: String?, undoable: Boolean) =
        Announcement(++announcements, message, focusNodeId, undoable)

    /** Bumps the revision, which is what makes the other person's import offer an update. */
    /**
     * Runs on the application scope, not the ViewModel's: leaving the editor
     * pops the nav entry and cancels the ViewModel, which would abort a save
     * started by that very departure.
     */
    fun save() {
        val editor = editor ?: return
        if (!_state.value.dirty) return
        val graph = editor.graph
        _state.value = _state.value.copy(dirty = false)
        appScope.launch {
            // The revision bump is what makes the other person's import offer an
            // update rather than a duplicate.
            repository.save(graph.copy(revision = graph.revision + 1))
        }
    }

    // --- structural edits: one undo step each, immediately ---

    fun renameGraph(name: String) {
        val editor = editor ?: return
        val clean = name.trim().ifBlank { return }
        editor.applyStructural(editor.graph.copy(name = clean), "Renamed graph", null)
        publish(dirty = true, announcement = announce("Renamed graph", null, undoable = true))
    }

    /**
     * The condition comes from the parent's answer list, never free text:
     * either an existing answer with no target yet, or a new answer added here.
     */
    fun addChild(
        parentId: String,
        answerId: String?,
        newLabel: String?,
        title: String,
        body: String
    ) {
        val editor = editor ?: return
        val parent = editor.graph.byId[parentId] ?: return

        val childId = newId("n")
        // Seeded with Yes/No because a question with no answers is an endpoint,
        // and a freshly added question usually is not meant to be one. Both are
        // removable, and neither is drawn until it has a child.
        val child = Node(
            id = childId,
            title = title.trim().ifBlank { "New question" },
            body = body.trim(),
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
        publish(
            dirty = true,
            announcement = announce("Added \"${child.title}\"", childId, undoable = true)
        )
    }

    /**
     * A resolution is a question with no answers, which is what makes it an
     * endpoint — there is no separate flag. The chat shows "Start again" when it
     * reaches one. Its snippet is usually the point of the whole flow: the
     * wording to paste into the ticket.
     */
    fun addResolution(
        parentId: String,
        answerId: String?,
        newLabel: String?,
        title: String,
        body: String,
        snippetLabel: String,
        snippetText: String
    ) {
        val editor = editor ?: return
        val parent = editor.graph.byId[parentId] ?: return

        val childId = newId("n")
        val child = Node(
            id = childId,
            title = title.trim().ifBlank { "Resolution" },
            body = body.trim(),
            snippets = if (snippetText.isBlank()) {
                emptyList()
            } else {
                listOf(
                    Snippet(
                        id = newId("s"),
                        label = snippetLabel.trim().ifBlank { "Note" },
                        text = snippetText.trim()
                    )
                )
            },
            answers = emptyList()
        )

        val linked = if (answerId != null) {
            parent.retarget(answerId, childId)
        } else {
            parent.withAnswer(Answer(newId("e"), newLabel?.trim().orEmpty().ifBlank { "Next" }, childId))
        }

        editor.applyStructural(
            editor.graph.addNode(child).replaceNode(linked),
            "Added resolution",
            childId
        )
        publish(
            dirty = true,
            announcement = announce("Added \"${child.title}\"", childId, undoable = true)
        )
    }

    // --- snippets, staged like any other text editing ---

    fun addSnippet(nodeId: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        val updated = node.copy(
            snippets = node.snippets + Snippet(newId("s"), "Note", "")
        )
        editor.stageDraft(editor.graph.replaceNode(updated))
        publish(dirty = true)
    }

    fun stageSnippet(nodeId: String, snippetId: String, label: String, text: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        val updated = node.copy(
            snippets = node.snippets.map {
                if (it.id == snippetId) it.copy(label = label, text = text) else it
            }
        )
        editor.stageDraft(editor.graph.replaceNode(updated))
        publish(dirty = true)
    }

    fun removeSnippet(nodeId: String, snippetId: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        editor.stageDraft(
            editor.graph.replaceNode(node.copy(snippets = node.snippets.filterNot { it.id == snippetId }))
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
        publish(
            dirty = true,
            announcement = announce("Connected", targetId, undoable = true)
        )
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

        val title = graph.byId[nodeId]?.title ?: "question"
        editor.applyStructural(next, "Deleted question", null)
        publish(
            dirty = true,
            announcement = announce("Deleted \"$title\"", null, undoable = true)
        )
    }

    // --- text editing: staged per keystroke, one undo step when the sheet closes ---

    fun stageNodeText(nodeId: String, title: String, body: String) {
        val editor = editor ?: return
        val node = editor.graph.byId[nodeId] ?: return
        editor.stageDraft(editor.graph.replaceNode(node.copy(title = title, body = body)))
        publish(dirty = true)
    }

    /** Called once when the editing sheet closes, collapsing the typing session. */
    fun commitEdits(nodeId: String) {
        val before = editor?.canUndo
        editor?.commitDraft("Edited question", nodeId)
        // commitDraft is a no-op when nothing actually changed, and announcing a
        // change that did not happen is worse than saying nothing.
        val changed = editor?.canUndo != before || _state.value.dirty
        publish(
            dirty = true,
            announcement = if (changed) {
                announce("Edited question", nodeId, undoable = true)
            } else null
        )
    }

    private fun publish(
        dirty: Boolean = _state.value.dirty,
        announcement: Announcement? = _state.value.announcement
    ) {
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
            canRedo = editor.canRedo,
            announcement = announcement
        )
    }
}
