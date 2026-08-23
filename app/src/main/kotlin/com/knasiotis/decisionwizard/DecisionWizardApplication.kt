package com.knasiotis.decisionwizard

import android.app.Application
import com.knasiotis.decisionwizard.data.DecisionWizardDatabase
import com.knasiotis.decisionwizard.data.FileGateway
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled wiring instead of a DI framework. There are two dependencies and
 * one graph of them; Hilt would be more machinery than the whole app.
 */
class DecisionWizardApplication : Application() {

    private val database by lazy { DecisionWizardDatabase.get(this) }

    val repository by lazy { LibraryRepository(database.graphs(), database.sessions()) }

    val files by lazy { FileGateway(contentResolver) }

    val settings by lazy { SettingsStore(this) }

    /**
     * For writes that must finish even though the screen that started them is
     * going away. A ViewModel scope is cancelled when its nav entry is popped,
     * which would abort a save triggered by leaving the editor.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
