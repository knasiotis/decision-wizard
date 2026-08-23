package com.knasiotis.decisionwizard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.knasiotis.decisionwizard.ui.DecisionWizardApp
import com.knasiotis.decisionwizard.ui.DecisionWizardTheme

class MainActivity : ComponentActivity() {

    /**
     * A .dwiz the user tapped in a file manager or mail client. Held as state
     * rather than read from `intent` during composition, so that a second tap
     * while the app is already open is noticed too.
     */
    private var tappedFile by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tappedFile = intent.dwizUri()
        val app = application as DecisionWizardApplication

        setContent {
            DecisionWizardTheme {
                DecisionWizardApp(
                    app = app,
                    pendingImportUri = tappedFile,
                    onPendingImportHandled = { tappedFile = null }
                )
            }
        }
    }

    /** The activity is singleTop, so a second tap arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tappedFile = intent.dwizUri()
    }
}

private fun Intent.dwizUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null
