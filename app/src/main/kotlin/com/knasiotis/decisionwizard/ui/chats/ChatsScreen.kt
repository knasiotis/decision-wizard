package com.knasiotis.decisionwizard.ui.chats

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knasiotis.decisionwizard.library.GraphSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onOpenChat: (String) -> Unit,
    onStartChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val graphs by viewModel.graphs.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ChatSummary?>(null) }
    var picking by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Chats") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                text = { Text("New chat") },
                icon = {}
            )
        }
    ) { insets ->
        when {
            chats == null -> Unit // first query still in flight

            chats!!.isEmpty() -> EmptyChats(Modifier.padding(insets))

            else -> LazyColumn(
                modifier = Modifier.padding(insets).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(chats!!, key = { it.sessionId }) { chat ->
                    ChatCard(
                        chat = chat,
                        onOpen = { onOpenChat(chat.sessionId) },
                        onDelete = { pendingDelete = chat }
                    )
                }
            }
        }
    }

    if (picking) {
        GraphPickerDialog(
            graphs = graphs,
            onPick = {
                picking = false
                onStartChat(it)
            },
            onDismiss = { picking = false }
        )
    }

    pendingDelete?.let { chat ->
        DeleteChatDialog(
            chat = chat,
            onConfirm = {
                viewModel.delete(chat.sessionId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Which graph should the new chat run on. */
@Composable
private fun GraphPickerDialog(
    graphs: List<GraphSummary>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    // Substring rather than prefix: an agent looking for "Internet down" is as
    // likely to type "down" as "inter". Trimmed so a stray space matches nothing.
    val matches = remember(graphs, query) {
        val q = query.trim()
        if (q.isEmpty()) graphs else graphs.filter { it.name.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (graphs.isEmpty()) "No graphs yet" else "Start a chat on…") },
        text = {
            if (graphs.isEmpty()) {
                Text("Import a graph on the Graphs tab first, then a chat can run on it.")
            } else {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (matches.isEmpty()) {
                        Text(
                            "No graphs match \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            matches.forEach { graph ->
                                Text(
                                    text = graph.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(graph.graphId) }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteChatDialog(
    chat: ChatSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this chat?") },
        text = {
            // Spells out that the graph stays, because the card is named after
            // the graph and the two are easy to conflate.
            Text(
                "The chat on \"${chat.graphName}\" will be deleted. " +
                    "The graph itself is kept."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete chat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyChats(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap New chat to pick a graph. Chats appear here once you answer the first question.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ChatCard(chat: ChatSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Labelled as a chat, not just named after its graph. Titling the
            // card with the graph name alone made Delete read as deleting the
            // graph itself.
            Text(
                "Chat",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(chat.graphName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${chat.answerCount} answered · ${relativeTime(chat.lastOpenedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chat.graphChanged) {
                // The chat still opens — turns() skips nodes that no longer
                // exist — but say so rather than letting steps quietly vanish.
                Text(
                    "The graph has changed since this chat",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) { Text("Delete chat") }
            }
        }
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
