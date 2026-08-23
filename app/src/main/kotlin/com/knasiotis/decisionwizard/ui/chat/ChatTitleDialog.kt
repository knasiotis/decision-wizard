package com.knasiotis.decisionwizard.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Naming a chat, used both when starting one and when renaming it from the chat
 * screen. One dialog for both, so the two never drift apart.
 */
@Composable
fun ChatTitleDialog(
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Selected, not just prefilled: the default is a suggestion, and typing
    // should replace it rather than append to it.
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val trimmed = value.text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                // A blank title would leave the list showing nothing useful.
                enabled = trimmed.isNotEmpty()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
