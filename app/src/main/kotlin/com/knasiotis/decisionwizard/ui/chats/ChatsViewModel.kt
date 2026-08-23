package com.knasiotis.decisionwizard.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.data.SessionListRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ChatSummary(
    val sessionId: String,
    val graphName: String,
    val answerCount: Int,
    val lastOpenedAt: Long,
    /** The graph has moved on since this session was answered. */
    val graphChanged: Boolean
)

class ChatsViewModel(
    private val repository: LibraryRepository
) : ViewModel() {

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
            graphName = graphName,
            answerCount = answers,
            lastOpenedAt = lastOpenedAt,
            graphChanged = graphRevision != sessionRevision
        )
    }
}
