package com.knasiotis.decisionwizard.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.editor.GraphEditor
import com.knasiotis.decisionwizard.layout.GraphLayout
import com.knasiotis.decisionwizard.layout.LayoutEngine
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.GraphValidator
import com.knasiotis.decisionwizard.model.Issue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
