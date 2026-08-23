package com.knasiotis.decisionwizard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.parseGraph
import com.knasiotis.decisionwizard.ui.ChatScreen
import com.knasiotis.decisionwizard.ui.DecisionWizardTheme

/**
 * v0.1 is chat and traversal over one bundled graph. No editor, no Room, no
 * import. The point of this milestone is to get the build, signing and release
 * pipeline working while the app is still too small to hide problems.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = loadBundledGraph()

        setContent {
            DecisionWizardTheme {
                ChatScreen(graph)
            }
        }
    }

    /** v0.2 replaces this with the Room-backed library and the import flow. */
    private fun loadBundledGraph(): Graph =
        assets.open(SAMPLE_ASSET).bufferedReader().use { parseGraph(it.readText()) }

    private companion object {
        const val SAMPLE_ASSET = "graph-schema-example.json"
    }
}
