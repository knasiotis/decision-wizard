package com.knasiotis.decisionwizard.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.data.SessionListRow
import com.knasiotis.decisionwizard.library.GraphSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ChatSummary(
    val sessionId: String,
    val title: String,
    /** Always shown: a chat's title need not say which flow it runs on. */
    val graphName: String,
    val answerCount: Int,
    val lastOpenedAt: Long,
    /** The graph has moved on since this session was answered. */
    val graphChanged: Boolean,
    /** The graph was deleted; the chat is a record now and cannot be continued. */
    val readOnly: Boolean
)

class ChatsViewModel(
    private val repository: LibraryRepository
) : ViewModel() {

    /** Backs the new-chat picker: which graph should this chat run on. */
    // Null until the first query returns, so the picker does not flash
    // "No graphs yet" at someone who has plenty.
    val graphs: StateFlow<List<GraphSummary>?> = repository.summaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chats: StateFlow<List<ChatSummary>?> = repository.sessionList()
        .map { rows -> rows.map { it.toSummary() } }
        // null distinguishes "still loading" from "genuinely empty", so the
        // empty state does not flash on launch.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(sessionId: String) {
        viewModelScope.launch { repository.deleteSession(sessionId) }
    }

    private fun SessionListRow.toSummary(): ChatSummary {
        val answers = runCatching {
            Json.decodeFromString(ChatState.serializer(), stateJson).answered.size
        }.getOrDefault(0)

        return ChatSummary(
            sessionId = sessionId,
            // Sessions from before titles existed fall back to the graph name.
            title = title.ifBlank { graphName ?: "Chat" },
            graphName = graphName ?: "Deleted graph",
            answerCount = answers,
            lastOpenedAt = lastOpenedAt,
            // Only meaningful while the graph still exists.
            graphChanged = graphName != null && graphRevision != sessionRevision,
            readOnly = graphName == null
        )
    }
}
