package com.knasiotis.decisionwizard.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knasiotis.decisionwizard.data.ChatRetention
import com.knasiotis.decisionwizard.data.LaunchBehaviour

/**
 * A top-level destination rather than an overflow menu, and grouped by the area
 * a setting affects. There is only one setting today, but the grouping is what
 * makes room for the next one without another reshuffle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val launchBehaviour by viewModel.launchBehaviour.collectAsStateWithLifecycle()

    val retentionDays by viewModel.chatRetentionDays.collectAsStateWithLifecycle()
    val graphCount by viewModel.graphCount.collectAsStateWithLifecycle()
    val backupName by viewModel.backupName.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    var customOpen by remember { mutableStateOf(false) }

    val backupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.backupTo(uri) else viewModel.cancelBackup() }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restoreFrom) }

    LaunchedEffect(backupName) {
        backupName?.let { backupSaver.launch(it) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbars) }
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Chats")

            Setting(
                title = "When the app opens",
                description = "What you see when Decision Wizard starts."
            ) {
                Choice(
                    label = "Carry on with the last chat",
                    selected = launchBehaviour == LaunchBehaviour.RESUME_LAST,
                    onClick = { viewModel.setLaunchBehaviour(LaunchBehaviour.RESUME_LAST) }
                )
                Choice(
                    label = "Start a new chat",
                    selected = launchBehaviour == LaunchBehaviour.NEW_CHAT,
                    onClick = { viewModel.setLaunchBehaviour(LaunchBehaviour.NEW_CHAT) }
                )
            }

            Setting(
                title = "Delete old chats",
                description = "Counted from when a chat was last opened, so one you " +
                    "keep coming back to is never swept up because it began long ago."
            ) {
                ChatRetention.PRESETS.forEach { days ->
                    Choice(
                        label = ChatRetention.label(days),
                        selected = retentionDays == days,
                        onClick = { viewModel.setChatRetentionDays(days) }
                    )
                }
                Choice(
                    // Shows the current value when it is a custom one, so the
                    // row is not just "Custom…" with the number hidden inside.
                    label = if (ChatRetention.isPreset(retentionDays)) {
                        "After a set number of days…"
                    } else {
                        "${ChatRetention.label(retentionDays)} (custom)"
                    },
                    selected = !ChatRetention.isPreset(retentionDays),
                    onClick = { customOpen = true }
                )
            }

            SectionHeader("Graphs")

            Setting(
                title = "Back up every graph",
                description = "Writes a .zip holding one .dwiz per graph. " +
                    "An ordinary zip, so it opens on a computer and a single graph " +
                    "can be pulled out and sent on."
            ) {
                Action(
                    label = if (graphCount == 1) "Back up 1 graph" else "Back up $graphCount graphs",
                    enabled = graphCount > 0,
                    onClick = viewModel::askBackup
                )
            }

            Setting(
                title = "Restore from a backup",
                description = "Adds graphs the library does not have and updates any " +
                    "the backup holds a newer revision of. Nothing is overwritten " +
                    "with an older copy, and nothing is duplicated."
            ) {
                Action(
                    label = "Choose a backup",
                    enabled = true,
                    onClick = { restorePicker.launch(arrayOf("*/*")) }
                )
            }
        }
    }

    if (customOpen) {
        CustomRetentionDialog(
            current = retentionDays,
            onConfirm = {
                viewModel.setChatRetentionDays(it)
                customOpen = false
            },
            onDismiss = { customOpen = false }
        )
    }
}

@Composable
private fun CustomRetentionDialog(
    current: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember {
        mutableStateOf(if (ChatRetention.isPreset(current)) "" else current.toString())
    }
    val parsed = ChatRetention.parse(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete chats after") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
                    label = { Text("Days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && parsed == null
                )
                if (text.isNotBlank() && parsed == null) {
                    Text(
                        "Enter between 1 and ${ChatRetention.MAX_DAYS} days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                // Cannot confirm a value that would silently do nothing.
                enabled = parsed != null
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
    HorizontalDivider()
}

@Composable
private fun Setting(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label) }
}
