package com.knasiotis.decisionwizard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.knasiotis.decisionwizard.chat.ChatEngine
import com.knasiotis.decisionwizard.chat.ChatState
import com.knasiotis.decisionwizard.chat.ChatTurn
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Snippet
import kotlinx.serialization.json.Json

/** Survives rotation. The session holds no graph reference, so a string is enough. */
private val ChatStateSaver = Saver<ChatState, String>(
    save = { Json.encodeToString(ChatState.serializer(), it) },
    restore = { Json.decodeFromString(ChatState.serializer(), it) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(graph: Graph, modifier: Modifier = Modifier) {
    var state by rememberSaveable(graph.graphId, stateSaver = ChatStateSaver) {
        mutableStateOf(ChatEngine.start(graph))
    }

    // Derived from the session every recomposition, never cached — the same rule
    // the canvas layout follows.
    val turns = ChatEngine.turns(graph, state)
    val finished = ChatEngine.isFinished(graph, state)
    val deadEnd = ChatEngine.isDeadEnd(graph, state)

    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, finished, deadEnd) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(graph.name) }) }
    ) { insets ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(insets).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // stepIndex is unique per answered turn and -1 for the live one, so
            // this stays stable even when a cycle revisits the same node.
            items(turns, key = { "${it.stepIndex}:${it.node.id}" }) { turn ->
                Turn(
                    turn = turn,
                    onAnswer = { answerId ->
                        val next = if (turn.isLive) {
                            ChatEngine.answer(graph, state, answerId)
                        } else {
                            // Tapping any answer on an earlier question switches
                            // that branch outright rather than only rewinding.
                            ChatEngine.rewindAndAnswer(graph, state, turn.stepIndex, answerId)
                        }
                        if (next != null) state = next
                    }
                )
            }

            if (finished || deadEnd) {
                item {
                    SessionEnd(
                        deadEnd = deadEnd,
                        onRestart = { state = ChatEngine.start(graph) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Turn(turn: ChatTurn, onAnswer: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(turn.node.title, style = MaterialTheme.typography.titleMedium)
                if (turn.node.body.isNotBlank()) {
                    Text(turn.node.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        turn.node.snippets.forEach { SnippetCard(it) }

        if (turn.node.answers.isNotEmpty()) {
            // Wrapping, so two options and five options both look right.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                turn.node.answers.forEach { answer ->
                    // Past answers stay tappable: tapping one rewinds the session
                    // to that question. Disabling them would strand the user.
                    FilterChip(
                        selected = answer.id == turn.chosenAnswerId,
                        onClick = { onAnswer(answer.id) },
                        label = { Text(answer.label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnippetCard(snippet: Snippet) {
    val clipboard = LocalClipboardManager.current

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
            Text(snippet.text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { clipboard.setText(AnnotatedString(snippet.text)) }) {
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
