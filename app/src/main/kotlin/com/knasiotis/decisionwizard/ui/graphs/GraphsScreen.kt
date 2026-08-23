package com.knasiotis.decisionwizard.ui.graphs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knasiotis.decisionwizard.R
import com.knasiotis.decisionwizard.data.GraphEntity
import com.knasiotis.decisionwizard.ui.common.NameDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphsScreen(
    viewModel: GraphsViewModel,
    onOpenEditor: (String) -> Unit,
    /** Tells the host the user is driving, so startup navigation stands down. */
    onUserActed: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** A .dwiz the user tapped outside the app. Imported once, then released. */
    pendingImportUri: Uri? = null,
    onPendingImportHandled: () -> Unit = {}
) {
    val graphs by viewModel.graphs.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val exportRequest by viewModel.exportRequest.collectAsStateWithLifecycle()
    val duplicating by viewModel.duplicating.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var namingNew by rememberSaveable { mutableStateOf(false) }
    val created by viewModel.created.collectAsStateWithLifecycle()

    // A freshly created graph goes straight to the editor; there is nothing to
    // look at on the card until the user has built something.
    LaunchedEffect(created) {
        created?.let {
            viewModel.clearCreated()
            onOpenEditor(it)
        }
    }

    // .dwiz has no registered MIME type, so the picker cannot filter on one.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::import) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri) else viewModel.cancelExport()
    }

    LaunchedEffect(exportRequest) {
        exportRequest?.let { saver.launch(it.fileName) }
    }

    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let {
            viewModel.import(it)
            onPendingImportHandled()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Graphs") }) },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    onUserActed()
                    creating = true
                },
                text = { Text("Create") },
                icon = { Icon(painterResource(R.drawable.ic_plus), contentDescription = null) }
            )
        }
    ) { insets ->
        when {
            graphs == null -> Unit // first query still in flight

            graphs!!.isEmpty() -> EmptyLibrary(Modifier.padding(insets))

            else -> LazyColumn(
                modifier = Modifier.padding(insets).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(graphs!!, key = { it.graphId }) { graph ->
                    GraphCard(
                        graph = graph,
                        onOpen = { onOpenEditor(graph.graphId) },
                        onCopy = { viewModel.askDuplicate(graph) },
                        onExport = { viewModel.askExport(graph) },
                        onDelete = { viewModel.askDelete(graph) }
                    )
                }
            }
        }
    }

    if (creating) {
        // Import is one way of creating a graph, not a separate concept, so it
        // sits beside "start from scratch" rather than owning its own button.
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Add a graph") },
            text = {
                Column {
                    TextButton(onClick = {
                        creating = false
                        namingNew = true
                    }) { Text("Start a new graph") }
                    TextButton(onClick = {
                        creating = false
                        picker.launch(arrayOf("*/*"))
                    }) { Text("Import from a file") }
                }
            },
            confirmButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }

    duplicating?.let { (_, suggested) ->
        NameDialog(
            dialogTitle = "Copy graph",
            fieldLabel = "Graph name",
            initial = suggested,
            confirmLabel = "Copy",
            onConfirm = viewModel::duplicate,
            onDismiss = viewModel::cancelDuplicate
        )
    }

    if (namingNew) {
        NewGraphDialog(
            onConfirm = {
                namingNew = false
                viewModel.createGraph(it)
            },
            onDismiss = { namingNew = false }
        )
    }

    conflict?.let {
        ImportConflictDialog(
            existingName = it.existing.name,
            existingRevision = it.existing.revision,
            incomingRevision = it.incoming.revision,
            canUpdate = it.canUpdate,
            onUpdate = viewModel::resolveAsUpdate,
            onDuplicate = viewModel::resolveAsDuplicate,
            onDismiss = viewModel::dismissConflict
        )
    }

    pendingDelete?.let {
        DeleteGraphDialog(
            name = it.graph.name,
            sessionCount = it.sessionCount,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete
        )
    }
}

@Composable
private fun NewGraphDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New graph") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Graph name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No graphs yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap Create to start a new graph, or to import a .dwiz file someone sent you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun GraphCard(
    graph: GraphEntity,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    // Opens the editor, not a chat. Chats start on the Chats tab.
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(graph.name, style = MaterialTheme.typography.titleMedium)
            if (graph.description.isNotBlank()) {
                Text(
                    graph.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Revision ${graph.revision}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onExport) { Text("Export") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ImportConflictDialog(
    existingName: String,
    existingRevision: Int,
    incomingRevision: Int,
    canUpdate: Boolean,
    onUpdate: () -> Unit,
    onDuplicate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You already have this graph") },
        text = {
            Text(
                if (canUpdate) {
                    "\"$existingName\" is at revision $existingRevision and this file is " +
                        "revision $incomingRevision. Update it, or keep both?"
                } else {
                    // Replacing with an equal or older revision would silently
                    // discard work, so it is not offered at all.
                    "\"$existingName\" is at revision $existingRevision and this file is " +
                        "revision $incomingRevision, so updating would lose work. " +
                        "It can be imported as a separate copy."
                }
            )
        },
        confirmButton = {
            if (canUpdate) TextButton(onClick = onUpdate) { Text("Update") }
            else TextButton(onClick = onDuplicate) { Text("Keep both") }
        },
        dismissButton = {
            if (canUpdate) TextButton(onClick = onDuplicate) { Text("Keep both") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteGraphDialog(
    name: String,
    sessionCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$name\"?") },
        text = {
            Text(
                // Chats are kept: each carries a snapshot, so it survives as a
                // read-only record rather than disappearing with the graph.
                when (sessionCount) {
                    0 -> "This cannot be undone."
                    1 -> "1 chat used this graph. It will be kept as a read-only " +
                        "record. Deleting the graph cannot be undone."
                    else -> "$sessionCount chats used this graph. They will be kept as " +
                        "read-only records. Deleting the graph cannot be undone."
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
