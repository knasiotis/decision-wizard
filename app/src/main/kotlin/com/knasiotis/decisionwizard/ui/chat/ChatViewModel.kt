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
    val graph: Graph? = null,
    val session: ChatState = ChatState(),
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
    private val graphId: String?
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
            val (graph, session) = resumed
            _state.value = ChatUiState(graph, session, loading = false)
            // Resuming counts as opening, so it moves to the top of the list.
            persist(graph, session)
            return
        }

        val graph = graphId?.let { repository.load(it) }
        if (graph == null) {
            _state.value = ChatUiState(loading = false, missing = true)
            return
        }
        _state.value = ChatUiState(graph, ChatEngine.start(graph), loading = false)
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
        viewModelScope.launch { persist(graph, next) }
    }

    /**
     * Only written once something has actually been answered. Saving on open
     * would fill the Chats list with empty sessions every time a graph is
     * tapped, and an unanswered session has nothing to resume anyway.
     */
    private suspend fun persist(graph: Graph, session: ChatState) {
        if (session.answered.isEmpty()) return
        repository.saveSession(id, graph, session, startedAt)
    }
}
