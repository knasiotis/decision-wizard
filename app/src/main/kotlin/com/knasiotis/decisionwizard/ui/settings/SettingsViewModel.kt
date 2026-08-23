package com.knasiotis.decisionwizard.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knasiotis.decisionwizard.data.ChatRetention
import com.knasiotis.decisionwizard.data.FileGateway
import com.knasiotis.decisionwizard.data.LaunchBehaviour
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.data.SettingsStore
import com.knasiotis.decisionwizard.library.BackupArchive
import com.knasiotis.decisionwizard.library.DwizFormatException
import com.knasiotis.decisionwizard.library.ImportPlan
import com.knasiotis.decisionwizard.library.ImportPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SettingsViewModel(
    private val settings: SettingsStore,
    private val repository: LibraryRepository,
    private val files: FileGateway
) : ViewModel() {

    val launchBehaviour: StateFlow<LaunchBehaviour> = settings.launchBehaviour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaunchBehaviour.RESUME_LAST)

    val chatRetentionDays: StateFlow<Int> = settings.chatRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatRetention.FOREVER)

    val graphCount: StateFlow<Int> = repository.summaries()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _backupName = MutableStateFlow<String?>(null)
    val backupName: StateFlow<String?> = _backupName.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setLaunchBehaviour(value: LaunchBehaviour) {
        viewModelScope.launch { settings.setLaunchBehaviour(value) }
    }

    /**
     * Applies the new limit immediately rather than waiting for the next launch,
     * so the effect of the choice is visible while the user is still looking at
     * it — and reports what it took, because silently deleting history is not on.
     */
    fun setChatRetentionDays(days: Int) {
        viewModelScope.launch {
            settings.setChatRetentionDays(days)
            val removed = repository.pruneSessions(days)
            if (removed > 0) {
                _message.value =
                    "Deleted $removed ${if (removed == 1) "old chat" else "old chats"}."
            }
        }
    }

    fun askBackup() {
        _backupName.value = BackupArchive.fileName(LocalDate.now().toString())
    }

    fun cancelBackup() { _backupName.value = null }

    fun backupTo(uri: Uri) {
        _backupName.value = null
        viewModelScope.launch {
            try {
                val graphs = repository.summaries().first().mapNotNull { repository.load(it.graphId) }
                if (graphs.isEmpty()) {
                    _message.value = "There is nothing to back up yet."
                    return@launch
                }
                files.write(uri, BackupArchive.write(graphs))
                _message.value = "Backed up ${graphs.size} ${if (graphs.size == 1) "graph" else "graphs"}."
            } catch (e: Exception) {
                _message.value = "Could not write the backup."
            }
        }
    }

    /**
     * Restores every graph in the archive, resolving each one silently rather
     * than asking about them one at a time.
     *
     * A newer revision updates in place; anything else is left alone. That makes
     * restoring your own backup onto a live library idempotent, which is the
     * whole point — a restore that duplicated everything would be worse than
     * useless.
     */
    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            val graphs = try {
                BackupArchive.read(files.read(uri))
            } catch (e: DwizFormatException) {
                _message.value = e.message
                return@launch
            } catch (e: Exception) {
                _message.value = "Could not read that backup."
                return@launch
            }

            var added = 0
            var updated = 0
            var skipped = 0
            var rejected = 0

            graphs.forEach { graph ->
                when (val plan = ImportPlanner.plan(graph, repository.summaries().first())) {
                    is ImportPlan.Rejected -> rejected++
                    is ImportPlan.AddNew -> {
                        repository.save(plan.incoming)
                        added++
                    }
                    is ImportPlan.Conflict ->
                        if (plan.canUpdate) {
                            repository.save(plan.incoming)
                            updated++
                        } else {
                            skipped++
                        }
                }
            }

            _message.value = buildString {
                append("Restored: $added added")
                if (updated > 0) append(", $updated updated")
                if (skipped > 0) append(", $skipped already current")
                if (rejected > 0) append(", $rejected unreadable")
                append(".")
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
