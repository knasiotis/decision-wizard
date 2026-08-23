package com.knasiotis.decisionwizard.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.chat.ChatEngine
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    /** Null once the graph has been deleted; answered turns still render. */
    val graph: Graph? = null,
    val graphName: String = "",
    val session: ChatState = ChatState(),
    val title: String = "",
    /** The graph was deleted; this chat is a record and cannot be answered. */
    val readOnly: Boolean = false,
    val loading: Boolean = true,
    val missing: Boolean = false
)

/**
 * Owns one chat session. Either resumes [sessionId] or starts a fresh session on
 * [graphId] — exactly one of the two is given.
 */
class ChatViewModel(
    private val repository: LibraryRepository,
    private val sessionId: String?,
    private val graphId: String?,
    private val initialTitle: String = ""
) : ViewModel() {

    private val id = sessionId ?: newId("s")
    private var startedAt = System.currentTimeMillis()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (sessionId != null) {
            val resumed = repository.loadSession(sessionId)
            if (resumed == null) {
                _state.value = ChatUiState(loading = false, missing = true)
                return
            }
            startedAt = resumed.startedAt
            // Sessions predating titles have none; fall back to the graph name
            // rather than showing an empty top bar.
            val title = resumed.title.ifBlank { resumed.graphName }
            _state.value = ChatUiState(
                graph = resumed.graph,
                graphName = resumed.graphName,
                session = resumed.state,
                title = title,
                readOnly = resumed.readOnly,
                loading = false
            )
            // A record cannot be reopened in any meaningful sense, and touching
            // lastOpenedAt would reorder the list for no reason.
            val graph = resumed.graph ?: return
            // Resuming counts as opening, so it moves to the top of the list.
            persist(graph, resumed.state, title)
            return
        }

        val graph = graphId?.let { repository.load(it) }
        if (graph == null) {
            _state.value = ChatUiState(loading = false, missing = true)
            return
        }
        val started = ChatEngine.start(graph)
        val title = initialTitle.ifBlank { graph.name }
        _state.value = ChatUiState(
            graph = graph,
            graphName = graph.name,
            session = started,
            title = title,
            loading = false
        )
        persist(graph, started, title)
    }

    fun answer(answerId: String) {
        val current = _state.value
        val graph = current.graph ?: return
        val next = ChatEngine.answer(graph, current.session, answerId) ?: return
        apply(graph, next)
    }

    fun rewindAndAnswer(stepIndex: Int, answerId: String) {
        val current = _state.value
        val graph = current.graph ?: return
        val next = ChatEngine.rewindAndAnswer(graph, current.session, stepIndex, answerId) ?: return
        apply(graph, next)
    }

    fun restart() {
        val graph = _state.value.graph ?: return
        apply(graph, ChatEngine.start(graph))
    }

    private fun apply(graph: Graph, next: ChatState) {
        _state.value = _state.value.copy(session = next)
        viewModelScope.launch { persist(graph, next, _state.value.title) }
    }

    fun rename(title: String) {
        val clean = title.trim().ifBlank { return }
        _state.value = _state.value.copy(title = clean)
        // The row exists from creation, so a rename always has something to
        // write to — including on a chat nothing has been answered in yet.
        viewModelScope.launch { repository.renameSession(id, clean) }
    }

    /**
     * Written from the moment the chat is created, not from its first answer.
     * Naming a chat and starting it is a deliberate act, so it should appear in
     * the list straight away and be there to resume — an empty chat the user
     * asked for is not clutter.
     */
    private suspend fun persist(graph: Graph, session: ChatState, title: String) {
        repository.saveSession(id, graph, session, title, startedAt)
    }
}
