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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.knasiotis.decisionwizard.data.LaunchBehaviour

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val launchBehaviour by viewModel.launchBehaviour.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) { Text("Settings") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("When the app opens…") },
                            onClick = {
                                menuOpen = false
                                settingsOpen = true
                            }
                        )
                    }
                }
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
                        onDelete = { viewModel.delete(chat.sessionId) }
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        LaunchBehaviourDialog(
            current = launchBehaviour,
            onChoose = {
                viewModel.setLaunchBehaviour(it)
                settingsOpen = false
            },
            onDismiss = { settingsOpen = false }
        )
    }
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
            "Open a graph to start one. Chats appear here once you answer the first question.",
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
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun LaunchBehaviourDialog(
    current: LaunchBehaviour,
    onChoose: (LaunchBehaviour) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When the app opens") },
        text = {
            Column {
                Option(
                    label = "Carry on with the last chat",
                    selected = current == LaunchBehaviour.RESUME_LAST,
                    onClick = { onChoose(LaunchBehaviour.RESUME_LAST) }
                )
                Option(
                    label = "Start a new chat",
                    selected = current == LaunchBehaviour.NEW_CHAT,
                    onClick = { onChoose(LaunchBehaviour.NEW_CHAT) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
