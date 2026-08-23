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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.knasiotis.decisionwizard.data.LaunchBehaviour
import com.knasiotis.decisionwizard.ui.chat.ChatViewModel
import com.knasiotis.decisionwizard.ui.chats.ChatsScreen
import com.knasiotis.decisionwizard.ui.chats.ChatsViewModel
import com.knasiotis.decisionwizard.ui.graphs.GraphsScreen
import com.knasiotis.decisionwizard.ui.graphs.GraphsViewModel
import kotlinx.coroutines.flow.first

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
    const val NEW_CHAT = "chat/new/{graphId}"
    const val RESUME_CHAT = "chat/session/{sessionId}"

    fun newChat(graphId: String) = "chat/new/$graphId"
    fun resumeChat(sessionId: String) = "chat/session/$sessionId"
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

    // Runs once. A tapped file always wins over the launch preference — the user
    // asked for that file, not for whatever they were doing last.
    var launchHandled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launchHandled || pendingImportUri != null) {
            launchHandled = true
            return@LaunchedEffect
        }
        launchHandled = true

        if (app.settings.launchBehaviour.first() == LaunchBehaviour.NEW_CHAT) {
            // "Start a new chat" means choosing what it runs on.
            navController.navigate(Routes.GRAPHS) { launchSingleTop = true }
        } else {
            app.repository.mostRecentSession()?.let {
                navController.navigate(Routes.resumeChat(it.sessionId))
            }
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
                val vm: ChatsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ChatsViewModel(app.repository, app.settings) }
                    }
                )
                ChatsScreen(
                    viewModel = vm,
                    onOpenChat = { navController.navigate(Routes.resumeChat(it)) }
                )
            }

            composable(Routes.GRAPHS) {
                val vm: GraphsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { GraphsViewModel(app.repository, app.files) }
                    }
                )
                GraphsScreen(
                    viewModel = vm,
                    onOpenGraph = { navController.navigate(Routes.newChat(it)) },
                    pendingImportUri = pendingImportUri,
                    onPendingImportHandled = onPendingImportHandled
                )
            }

            composable(Routes.NEW_CHAT) { entry ->
                val graphId = entry.arguments?.getString("graphId")
                ChatRoute(app, key = "new:$graphId", sessionId = null, graphId = graphId)
            }

            composable(Routes.RESUME_CHAT) { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                ChatRoute(app, key = "session:$sessionId", sessionId = sessionId, graphId = null)
            }
        }
    }
}

@Composable
private fun ChatRoute(
    app: DecisionWizardApplication,
    key: String,
    sessionId: String?,
    graphId: String?
) {
    // Keyed, or navigating between two chats would reuse the first one's ViewModel.
    val vm: ChatViewModel = viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { ChatViewModel(app.repository, sessionId, graphId) }
        }
    )

    val ui by vm.state.collectAsStateWithLifecycle()
    val graph = ui.graph

    when {
        ui.loading -> Unit
        graph == null -> MissingGraph()
        else -> ChatScreen(
            graph = graph,
            state = ui.session,
            onAnswer = vm::answer,
            onRewindAndAnswer = vm::rewindAndAnswer,
            onRestart = vm::restart
        )
    }
}

/**
 * A session whose graph has been deleted, or a graph id that no longer resolves.
 * The cascade should make the first impossible, but a stale back stack entry can
 * still land here.
 */
@Composable
private fun MissingGraph() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("That graph is gone", style = MaterialTheme.typography.titleMedium)
        Text(
            "It was deleted, so this chat can no longer be shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
