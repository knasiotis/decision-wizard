package com.knasiotis.decisionwizard.ui.graphs

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.data.FileGateway
import com.knasiotis.decisionwizard.data.GraphEntity
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.library.DwizCodec
import com.knasiotis.decisionwizard.library.DwizFormatException
import com.knasiotis.decisionwizard.library.ImportPlan
import com.knasiotis.decisionwizard.library.ImportPlanner
import com.knasiotis.decisionwizard.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeleteRequest(val graph: GraphEntity, val sessionCount: Int)

class GraphsViewModel(
    private val repository: LibraryRepository,
    private val files: FileGateway
) : ViewModel() {

    val graphs: StateFlow<List<GraphEntity>?> = repository.allGraphs()
        // null means "not loaded yet", so the empty state does not flash on
        // launch before the first query returns.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _conflict = MutableStateFlow<ImportPlan.Conflict?>(null)
    val conflict: StateFlow<ImportPlan.Conflict?> = _conflict.asStateFlow()

    private val _pendingDelete = MutableStateFlow<DeleteRequest?>(null)
    val pendingDelete: StateFlow<DeleteRequest?> = _pendingDelete.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun import(uri: Uri) {
        viewModelScope.launch {
            val plan = try {
                val incoming = DwizCodec.decode(files.read(uri))
                ImportPlanner.plan(incoming, repository.summaries().first())
            } catch (e: DwizFormatException) {
                _message.value = e.message
                return@launch
            } catch (e: Exception) {
                _message.value = "Could not read that file."
                return@launch
            }

            when (plan) {
                is ImportPlan.Rejected ->
                    // The only case the app refuses outright: duplicate ids make
                    // the file impossible to load unambiguously.
                    _message.value = plan.fatal.firstOrNull()?.message
                        ?: "That file cannot be imported."

                is ImportPlan.AddNew -> {
                    repository.save(plan.incoming)
                    _message.value = imported(plan.incoming.name, plan.warnings.size)
                }

                // Needs a decision from the user, so hand it to the sheet.
                is ImportPlan.Conflict -> _conflict.value = plan
            }
        }
    }

    fun resolveAsUpdate() {
        val c = _conflict.value ?: return
        _conflict.value = null
        viewModelScope.launch {
            repository.save(c.incoming)
            _message.value = "Updated \"${c.incoming.name}\" to revision ${c.incoming.revision}."
        }
    }

    fun resolveAsDuplicate() {
        val c = _conflict.value ?: return
        _conflict.value = null
        viewModelScope.launch {
            val copy = ImportPlanner.duplicate(
                source = c.incoming,
                newGraphId = newId("g"),
                takenNames = repository.takenNames()
            )
            repository.save(copy)
            _message.value = "Imported as \"${copy.name}\"."
        }
    }

    fun dismissConflict() { _conflict.value = null }

    fun askDelete(graph: GraphEntity) {
        viewModelScope.launch {
            _pendingDelete.value = DeleteRequest(graph, repository.sessionCount(graph.graphId))
        }
    }

    fun confirmDelete() {
        val request = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            repository.deleteGraph(request.graph.graphId)
            _message.value = "Deleted \"${request.graph.name}\"."
        }
    }

    fun dismissDelete() { _pendingDelete.value = null }

    fun clearMessage() { _message.value = null }

    private fun imported(name: String, warnings: Int): String = when (warnings) {
        0 -> "Imported \"$name\"."
        1 -> "Imported \"$name\" with 1 warning."
        else -> "Imported \"$name\" with $warnings warnings."
    }
}
