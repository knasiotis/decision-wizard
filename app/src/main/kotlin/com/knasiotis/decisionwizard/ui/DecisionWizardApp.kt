package com.knasiotis.decisionwizard.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import com.knasiotis.decisionwizard.R
import com.knasiotis.decisionwizard.data.LaunchBehaviour
import com.knasiotis.decisionwizard.ui.chat.ChatViewModel
import com.knasiotis.decisionwizard.ui.chats.ChatsScreen
import com.knasiotis.decisionwizard.ui.chats.ChatsViewModel
import com.knasiotis.decisionwizard.ui.graphs.GraphsScreen
import com.knasiotis.decisionwizard.ui.editor.EditorScreen
import com.knasiotis.decisionwizard.ui.editor.EditorViewModel
import com.knasiotis.decisionwizard.ui.graphs.GraphsViewModel
import com.knasiotis.decisionwizard.ui.settings.SettingsScreen
import com.knasiotis.decisionwizard.ui.settings.SettingsViewModel
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
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{graphId}"

    fun editor(graphId: String) = "editor/$graphId"
    const val NEW_CHAT = "chat/new/{graphId}/{title}"
    const val RESUME_CHAT = "chat/session/{sessionId}"

    // The title is user text and can hold slashes or spaces, so it is encoded
    // into the path rather than concatenated raw.
    fun newChat(graphId: String, title: String) = "chat/new/$graphId/${Uri.encode(title)}"
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
    val showBottomBar = route.isOn(Routes.CHATS) || route.isOn(Routes.GRAPHS) ||
        route.isOn(Routes.SETTINGS)

    // A tapped .dwiz is handled on the Graphs screen, so go there first. The
    // import itself is triggered inside that screen, once it is composed.
    LaunchedEffect(pendingImportUri) {
        if (pendingImportUri != null && !route.isOn(Routes.GRAPHS)) {
            navController.navigate(Routes.GRAPHS) { launchSingleTop = true }
        }
    }

    // Anything the user does themselves outranks the launch preference. Reading
    // the setting, pruning and finding the last session all suspend, and acting
    // inside that window used to be interrupted by this navigating away
    // underneath an open dialog.
    //
    // Set from the navigation bar, which every deliberate move passes through,
    // as well as from the screens that start something.
    var userActed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // A tapped file always wins too — the user asked for that file, not for
        // whatever they were doing last.
        if (pendingImportUri != null) return@LaunchedEffect

        // Apply the retention setting before deciding what to open, or a chat
        // that is about to be pruned could be the one we resume into.
        app.repository.pruneSessions(app.settings.chatRetentionDays.first())
        val behaviour = app.settings.launchBehaviour.first()

        if (userActed) return@LaunchedEffect

        when (behaviour) {
            // "Start a new chat" means choosing what it runs on.
            LaunchBehaviour.NEW_CHAT ->
                navController.navigate(Routes.GRAPHS) { launchSingleTop = true }

            // Already the start destination, so there is nothing to navigate to.
            LaunchBehaviour.CHAT_LIST -> Unit

            LaunchBehaviour.RESUME_LAST ->
                app.repository.mostRecentSession()?.let {
                    navController.navigate(Routes.resumeChat(it.sessionId))
                }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    listOf(
                        Triple(Routes.CHATS, "Chats", R.drawable.ic_chats),
                        Triple(Routes.GRAPHS, "Graphs", R.drawable.ic_graphs),
                        Triple(Routes.SETTINGS, "Settings", R.drawable.ic_settings)
                    ).forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route.isOn(r),
                            onClick = {
                                userActed = true
                                navController.navigate(r) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(painterResource(icon), contentDescription = null)
                            },
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
                        initializer { ChatsViewModel(app.repository) }
                    }
                )
                ChatsScreen(
                    viewModel = vm,
                    onUserActed = { userActed = true },
                    onOpenChat = {
                        userActed = true
                        navController.navigate(Routes.resumeChat(it))
                    },
                    onStartChat = { graphId, title ->
                        navController.navigate(Routes.newChat(graphId, title))
                    }
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
                    onUserActed = { userActed = true },
                    onOpenEditor = {
                        userActed = true
                        navController.navigate(Routes.editor(it))
                    },
                    pendingImportUri = pendingImportUri,
                    onPendingImportHandled = onPendingImportHandled
                )
            }

            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SettingsViewModel(app.settings, app.repository, app.files) }
                    }
                )
                SettingsScreen(viewModel = vm)
            }

            composable(Routes.EDITOR) { entry ->
                val graphId = entry.arguments?.getString("graphId").orEmpty()
                val vm: EditorViewModel = viewModel(
                    key = "editor:$graphId",
                    factory = viewModelFactory {
                        initializer { EditorViewModel(app.repository, graphId, app.appScope) }
                    }
                )
                EditorScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.NEW_CHAT) { entry ->
                val graphId = entry.arguments?.getString("graphId")
                val title = entry.arguments?.getString("title").orEmpty()
                ChatRoute(
                    app,
                    key = "new:$graphId:$title",
                    sessionId = null,
                    graphId = graphId,
                    initialTitle = Uri.decode(title)
                )
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
    graphId: String?,
    initialTitle: String = ""
) {
    // Keyed, or navigating between two chats would reuse the first one's ViewModel.
    val vm: ChatViewModel = viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { ChatViewModel(app.repository, sessionId, graphId, initialTitle) }
        }
    )

    val ui by vm.state.collectAsStateWithLifecycle()

    when {
        ui.loading -> Unit
        // Only a session that is genuinely gone shows nothing. A deleted graph
        // still leaves a readable record.
        ui.missing -> MissingGraph()
        else -> ChatScreen(
            graph = ui.graph,
            graphName = ui.graphName,
            state = ui.session,
            title = ui.title,
            readOnly = ui.readOnly,
            onAnswer = vm::answer,
            onRewindAndAnswer = vm::rewindAndAnswer,
            onRename = vm::rename,
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
