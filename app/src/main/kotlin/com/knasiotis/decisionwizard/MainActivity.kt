package com.knasiotis.decisionwizard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.knasiotis.decisionwizard.ui.DecisionWizardApp
import com.knasiotis.decisionwizard.ui.DecisionWizardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DecisionWizardApplication

        setContent {
            DecisionWizardTheme {
                DecisionWizardApp(app)
            }
        }
    }
}
