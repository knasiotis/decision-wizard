package com.knasiotis.decisionwizard.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.knasiotis.decisionwizard.DecisionWizardApplication
import com.knasiotis.decisionwizard.data.LibraryRepository
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.ui.graphs.GraphsScreen
import com.knasiotis.decisionwizard.ui.graphs.GraphsViewModel

/**
 * Checks the whole hierarchy, not just the leaf, so a nested destination still
 * highlights its parent tab. Declared on the nullable type so callers need no
 * null handling of their own.
 */
private fun NavDestination?.isOn(route: String): Boolean =
    this?.hierarchy?.any { it.route == route } == true

private object Routes {
    const val CHATS = "chats"
    const val GRAPHS = "graphs"
    const val CHAT = "chat/{graphId}"
    fun chat(graphId: String) = "chat/$graphId"
}

@Composable
fun DecisionWizardApp(
    app: DecisionWizardApplication,
    pendingImportUri: Uri? = null,
    onPendingImportHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination

    // The bottom bar belongs to the two top-level destinations only; a chat is
    // a full-screen task, not a tab.
    val showBottomBar = route.isOn(Routes.CHATS) || route.isOn(Routes.GRAPHS)

    // A tapped .dwiz is handled on the Graphs screen, so go there first. The
    // import itself is triggered inside that screen, once it is composed.
    LaunchedEffect(pendingImportUri) {
        if (pendingImportUri != null && !route.isOn(Routes.GRAPHS)) {
            navController.navigate(Routes.GRAPHS) { launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    listOf(Routes.CHATS to "Chats", Routes.GRAPHS to "Graphs").forEach { (r, label) ->
                        NavigationBarItem(
                            selected = route.isOn(r),
                            onClick = {
                                navController.navigate(r) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {},
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHATS,
            modifier = Modifier.padding(insets)
        ) {
            composable(Routes.CHATS) {
                // Sessions are not persisted yet; this becomes the real list in
                // step 5 of the v0.2 plan.
                ChatsPlaceholder()
            }

            composable(Routes.GRAPHS) {
                val vm: GraphsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { GraphsViewModel(app.repository, app.files) }
                    }
                )
                GraphsScreen(
                    viewModel = vm,
                    onOpenGraph = { navController.navigate(Routes.chat(it)) },
                    pendingImportUri = pendingImportUri,
                    onPendingImportHandled = onPendingImportHandled
                )
            }

            composable(Routes.CHAT) { entry ->
                val graphId = entry.arguments?.getString("graphId")
                ChatRoute(graphId, app.repository)
            }
        }
    }
}

/** Loads the graph a chat runs over. Replaced by a session-backed VM in step 5. */
@Composable
private fun ChatRoute(graphId: String?, repository: LibraryRepository) {
    var graph by remember(graphId) { mutableStateOf<Graph?>(null) }

    LaunchedEffect(graphId) {
        graph = graphId?.let { repository.load(it) }
    }

    graph?.let { ChatScreen(it) }
}

@Composable
private fun ChatsPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open a graph to start one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
