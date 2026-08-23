package com.knasiotis.decisionwizard

import android.app.Application
import com.knasiotis.decisionwizard.data.DecisionWizardDatabase
import com.knasiotis.decisionwizard.data.FileGateway
import com.knasiotis.decisionwizard.data.LibraryRepository

/**
 * Hand-rolled wiring instead of a DI framework. There are two dependencies and
 * one graph of them; Hilt would be more machinery than the whole app.
 */
class DecisionWizardApplication : Application() {

    private val database by lazy { DecisionWizardDatabase.get(this) }

    val repository by lazy { LibraryRepository(database.graphs(), database.sessions()) }

    val files by lazy { FileGateway(contentResolver) }
}
