package com.knasiotis.decisionwizard.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.knasiotis.decisionwizard.editor.DeleteOps
import com.knasiotis.decisionwizard.model.Graph
import com.knasiotis.decisionwizard.model.Node

/**
 * Actions live in a sheet rather than as buttons pinned to the bubble, because
 * pinned buttons become unhittable when zoomed out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSheet(
    graph: Graph,
    node: Node,
    viewModel: EditorViewModel,
    onDismiss: () -> Unit
) {
    var editing by remember(node.id) { mutableStateOf(false) }
    var linking by remember(node.id) { mutableStateOf<LinkIntent?>(null) }
    var deleting by remember(node.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(node.title, style = MaterialTheme.typography.titleMedium)
            Text(
                inboundSummary(graph, node.id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            HorizontalDivider()

            SheetAction("Edit") { editing = true }
            SheetAction("Add child") { linking = LinkIntent.AddChild }
            // Nothing to connect to on a graph that holds only this question —
            // offering it would open an empty list. It returns as soon as there
            // is a second question, including on the root.
            if (graph.nodes.any { it.id != node.id }) {
                SheetAction("Connect to existing") { linking = LinkIntent.Connect }
            }
            SheetAction("Delete") { deleting = true }
        }
    }

    if (editing) {
        NodeEditDialog(
            node = node,
            onChange = { title, body -> viewModel.stageNodeText(node.id, title, body) },
            onClose = {
                // One undo step for the whole typing session, not one per keystroke.
                viewModel.commitEdits(node.id)
                editing = false
            }
        )
    }

    linking?.let { intent ->
        LinkDialog(
            graph = graph,
            node = node,
            intent = intent,
            onAddChild = { answerId, label, title, details ->
                viewModel.addChild(node.id, answerId, label, title, details)
                linking = null
                onDismiss()
            },
            onConnect = { answerId, label, targetId ->
                viewModel.connectExisting(node.id, answerId, label, targetId)
                linking = null
                onDismiss()
            },
            onDismiss = { linking = null }
        )
    }

    if (deleting) {
        val preview = remember(node.id, graph) { viewModel.deletePreview(node.id) }
        if (preview == null) {
            deleting = false
        } else {
            DeleteDialog(
                graph = graph,
                node = node,
                preview = preview,
                onDelete = { mode, adoptive ->
                    viewModel.delete(node.id, mode, adoptive)
                    deleting = false
                    onDismiss()
                },
                onDismiss = { deleting = false }
            )
        }
    }
}

enum class LinkIntent { AddChild, Connect }

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

/** "reached from: 3 questions" — without it a node looks unreferenced. */
private fun inboundSummary(graph: Graph, nodeId: String): String {
    val inbound = DeleteOps.inboundEdges(graph, nodeId).size
    return when {
        nodeId == graph.rootNodeId -> "The starting question"
        inbound == 0 -> "Nothing points here"
        inbound == 1 -> "Reached from 1 answer"
        else -> "Reached from $inbound answers"
    }
}

@Composable
private fun NodeEditDialog(
    node: Node,
    onChange: (title: String, body: String) -> Unit,
    onClose: () -> Unit
) {
    var title by remember(node.id) { mutableStateOf(node.title) }
    var body by remember(node.id) { mutableStateOf(node.body) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit question") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        onChange(it, body)
                    },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        onChange(title, it)
                    },
                    label = { Text("Details") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

@Composable
private fun LinkDialog(
    graph: Graph,
    node: Node,
    intent: LinkIntent,
    onAddChild: (answerId: String?, newLabel: String?, title: String, details: String) -> Unit,
    onConnect: (answerId: String?, newLabel: String?, targetId: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Only answers with nowhere to go. An answer that already has a target is
    // not a place to hang something new.
    val free = node.answers.filter { it.targetNodeId == null }
    var chosenAnswer by remember(node.id) { mutableStateOf<String?>(null) }
    var newLabel by remember(node.id) { mutableStateOf("") }
    var pickingTarget by remember(node.id) { mutableStateOf(false) }
    var composing by remember(node.id) { mutableStateOf(false) }
    var query by remember(node.id) { mutableStateOf("") }

    fun proceed(answerId: String?, label: String?) {
        chosenAnswer = answerId
        if (label != null) newLabel = label
        if (intent == LinkIntent.AddChild) composing = true else pickingTarget = true
    }

    if (composing) {
        // The new question is configured before it exists. Nothing is added to
        // the graph until this is confirmed, so a cancelled add leaves no
        // orphan node and no half-drawn branch behind.
        NewChildDialog(
            onConfirm = { title, details ->
                onAddChild(chosenAnswer, newLabel.ifBlank { null }, title, details)
            },
            // Choosing the condition is one tap and easy to get wrong. Going
            // back must not mean starting the whole action again.
            onBack = { composing = false },
            onDismiss = onDismiss
        )
        return
    }

    if (pickingTarget) {
        val matches = graph.nodes
            .filter { it.id != node.id }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Connect to…") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        matches.forEach { target ->
                            Text(
                                target.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onConnect(
                                            chosenAnswer,
                                            newLabel.ifBlank { null },
                                            target.id
                                        )
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { pickingTarget = false }) { Text("Back") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (intent == LinkIntent.AddChild) "Add child under…" else "Which answer?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                free.forEach { answer ->
                    Text(
                        answer.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { proceed(answer.id, null) }
                            .padding(vertical = 12.dp)
                    )
                }
                if (free.isNotEmpty()) HorizontalDivider()
                Text(
                    "New answer",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { proceed(null, newLabel) },
                    enabled = newLabel.isNotBlank()
                ) { Text("Add option") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Title and details for a question that does not exist yet. */
@Composable
private fun NewChildDialog(
    onConfirm: (title: String, details: String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New question") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, details) },
                enabled = title.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun DeleteDialog(
    graph: Graph,
    node: Node,
    preview: DeleteOps.DeletePreview,
    onDelete: (DeleteMode, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var reparenting by remember(node.id) { mutableStateOf(false) }
    var query by remember(node.id) { mutableStateOf("") }

    if (reparenting) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Move children to…") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        graph.nodes
                            .filter { it.id != node.id }
                            .filter { query.isBlank() || it.title.contains(query.trim(), true) }
                            .forEach { target ->
                                Text(
                                    target.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onDelete(DeleteMode.REPARENT, target.id) }
                                        .padding(vertical = 12.dp)
                                )
                            }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${node.title}\"?") },
        text = {
            Column {
                // Splice first when available: it is the common case when
                // tidying a flow, and it loses nothing.
                if (preview.spliceAvailable) {
                    Option(
                        "Remove and join up",
                        "Answers pointing here will point at its only child instead."
                    ) { onDelete(DeleteMode.SPLICE, null) }
                }
                Option(
                    "Delete only",
                    "Answers pointing here will have nowhere to go" +
                        if (preview.orphanCount > 0) {
                            ", and ${preview.orphanCount} question(s) become unreachable."
                        } else "."
                ) { onDelete(DeleteMode.ONLY, null) }

                if (preview.orphanCount > 0) {
                    Option(
                        "Delete and clean up",
                        "Also deletes the ${preview.orphanCount} question(s) that " +
                            "nothing else can reach."
                    ) { onDelete(DeleteMode.PURGE, null) }
                }
                if (preview.childCount > 0) {
                    Option(
                        "Delete and move children",
                        "Its ${preview.childCount} child(ren) move to another question."
                    ) { reparenting = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Option(title: String, description: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
