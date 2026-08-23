package com.knasiotis.decisionwizard.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.knasiotis.decisionwizard.R
import com.knasiotis.decisionwizard.chat.ChatEngine
import com.knasiotis.decisionwizard.ui.common.NameDialog
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.chat.ChatTurn
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Snippet

/**
 * Stateless: the session lives in ChatViewModel and is persisted to Room on
 * every answer, which is also what makes it survive rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    /** Null once the graph has been deleted; the record still renders. */
    graph: Graph?,
    graphName: String,
    state: ChatState,
    title: String,
    readOnly: Boolean,
    onAnswer: (answerId: String) -> Unit,
    onRewindAndAnswer: (stepIndex: Int, answerId: String) -> Unit,
    onRename: (String) -> Unit,
    onRestart: () -> Unit,
    exportName: String? = null,
    onAskExport: () -> Unit = {},
    onExportTo: (android.net.Uri) -> Unit = {},
    onCancelExport: () -> Unit = {},
    message: String? = null,
    onClearMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onExportTo(uri) else onCancelExport() }

    LaunchedEffect(exportName) { exportName?.let { saver.launch(it) } }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            onClearMessage()
        }
    }
    // Derived from the session every recomposition, never cached — the same rule
    // the canvas layout follows.
    // Takes no graph: everything on screen was recorded when it was asked.
    val turns = ChatEngine.turns(state)
    val finished = ChatEngine.isFinished(state)
    val deadEnd = ChatEngine.isDeadEnd(state)

    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, finished, deadEnd) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Tapping the title renames the chat, using the same
                        // dialog that named it in the first place. The pencil is
                        // what makes that discoverable at all.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable(enabled = !readOnly) {
                                renaming = true
                            }
                        ) {
                            Text(text = title)
                            if (!readOnly) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pencil),
                                    contentDescription = "Rename this chat",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // Which flow this chat runs on. A chat named "Tuesday
                        // callout" does not say, and that is what you need.
                        Text(
                            text = if (readOnly) "$graphName — deleted" else graphName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (readOnly) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                actions = {
                    // Works from the record, so it is offered even on a chat
                    // whose graph is gone — which is when it matters most.
                    TextButton(onClick = onAskExport) { Text("Export") }
                }
            )
        }
    ) { insets ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(insets).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // stepIndex is unique per answered turn and -1 for the live one, so
            // this stays stable even when a cycle revisits the same node.
            if (readOnly) {
                item {
                    Text(
                        "This graph was deleted, so the chat is kept as a record " +
                            "and cannot be continued.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            items(turns, key = { "${it.stepIndex}:${it.nodeId}" }) { turn ->
                Turn(
                    turn = turn,
                    readOnly = readOnly,
                    onAnswer = { answerId ->
                        if (turn.isLive) {
                            onAnswer(answerId)
                        } else {
                            // Tapping any answer on an earlier question switches
                            // that branch outright rather than only rewinding.
                            onRewindAndAnswer(turn.stepIndex, answerId)
                        }
                    }
                )
            }

            if (!readOnly && (finished || deadEnd)) {
                item { SessionEnd(deadEnd = deadEnd, onRestart = onRestart) }
            }
        }
    }

    if (renaming) {
        NameDialog(
            dialogTitle = "Rename",
            fieldLabel = "Chat name",
            initial = title,
            confirmLabel = "Rename",
            onConfirm = {
                onRename(it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Turn(turn: ChatTurn, readOnly: Boolean, onAnswer: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.large
        ) {
            // Selectable so an agent can lift a phrase out of a question, not
            // just the whole snippet. Wraps only the text — the answer chips stay
            // outside, or long-press selection would fight with tapping them.
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(turn.question, style = MaterialTheme.typography.titleMedium)
                    if (turn.detail.isNotBlank()) {
                        Text(turn.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        turn.snippets.forEach { SnippetCard(it) }

        if (turn.options.isNotEmpty()) {
            // Wrapping, so two options and five options both look right.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                turn.options.forEach { answer ->
                    // Past answers stay tappable: tapping one rewinds the session
                    // to that question. Disabling them would strand the user.
                    FilterChip(
                        selected = answer.id == turn.chosenAnswerId,
                        onClick = { onAnswer(answer.id) },
                        // A record cannot be re-answered, so the chips are inert
                        // rather than misleadingly tappable.
                        enabled = !readOnly,
                        label = { Text(answer.label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnippetCard(snippet: Snippet) {
    // LocalClipboard rather than the deprecated LocalClipboardManager. The new
    // API is suspend-based, hence the scope.
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                snippet.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            // Partial selection for pulling one line out of a long ticket note;
            // the Copy button below still takes the whole thing in one tap.
            SelectionContainer {
                Text(snippet.text, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(snippet.label, snippet.text))
                        )
                    }
                }
            ) {
                Text("Copy")
            }
        }
    }
}

@Composable
private fun SessionEnd(deadEnd: Boolean, onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (deadEnd) {
            // A legal graph state, not an error: the branch just is not built yet.
            Text(
                "That branch has no next step yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(onClick = onRestart) {
            Text("Start again")
        }
    }
}
