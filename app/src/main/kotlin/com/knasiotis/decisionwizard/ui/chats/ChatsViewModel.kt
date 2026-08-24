package com.knasiotis.decisionwizard.ui.chats

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.chat.Transcript
import com.knasiotis.decisionwizard.data.FileGateway
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.data.SessionListRow
import com.knasiotis.decisionwizard.library.GraphSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate

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

/** Held while the system save dialog is open, so the result knows what to write. */
data class TranscriptRequest(val sessionId: String, val fileName: String)

class ChatsViewModel(
    private val repository: LibraryRepository,
    private val files: FileGateway
) : ViewModel() {

    private val _exportRequest = MutableStateFlow<TranscriptRequest?>(null)
    val exportRequest: StateFlow<TranscriptRequest?> = _exportRequest.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun askExport(chat: ChatSummary) {
        _exportRequest.value = TranscriptRequest(
            chat.sessionId,
            Transcript.fileName(chat.title, LocalDate.now().toString())
        )
    }

    fun cancelExport() { _exportRequest.value = null }

    /**
     * Built from the record alone, so a chat whose graph has been deleted still
     * exports — which is exactly when someone needs the transcript.
     */
    fun exportTo(uri: Uri) {
        val request = _exportRequest.value ?: return
        _exportRequest.value = null
        viewModelScope.launch {
            val session = repository.loadSession(request.sessionId) ?: run {
                _message.value = "That chat could not be read."
                return@launch
            }
            _message.value = try {
                files.write(
                    uri,
                    Transcript.format(
                        state = session.state,
                        title = session.title.ifBlank { session.graphName },
                        graphName = session.graphName,
                        exportedAt = LocalDate.now().toString()
                    ).toByteArray()
                )
                "Transcript saved."
            } catch (e: Exception) {
                "Could not write the transcript."
            }
        }
    }

    fun clearMessage() { _message.value = null }

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
