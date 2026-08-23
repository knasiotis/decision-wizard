package com.knasiotis.decisionwizard.chat

/**
 * Renders a chat as plain text for pasting into a ticket.
 *
 * Works from the record alone — no graph — because that is what a chat is. A
 * transcript of a conversation must say what was actually asked and answered,
 * even if the flow has since been rewritten or deleted.
 */
object Transcript {

    fun format(
        state: ChatState,
        title: String,
        graphName: String,
        exportedAt: String
    ): String = buildString {
        appendLine(title)
        appendLine("Flow: $graphName")
        appendLine("Exported: $exportedAt")
        appendLine()

        val turns = ChatEngine.turns(state)
        if (turns.isEmpty()) {
            appendLine("Nothing was answered.")
            return@buildString
        }

        turns.forEach { turn ->
            appendLine(turn.question)
            if (turn.detail.isNotBlank()) appendLine(turn.detail)

            turn.snippets.forEach { snippet ->
                appendLine()
                appendLine("${snippet.label}:")
                // Indented so a multi-line note stays distinguishable from the
                // questions around it when pasted somewhere plain.
                snippet.text.lines().forEach { appendLine("  $it") }
            }

            val chosen = turn.chosenAnswerId
                ?.let { id -> turn.options.firstOrNull { it.id == id } }

            appendLine()
            if (chosen != null) {
                appendLine("> ${chosen.label}")
            } else {
                // Says why it stops, rather than trailing off as though the
                // export were truncated.
                appendLine(
                    if (turn.options.isEmpty()) "> (end of the flow)" else "> (not yet answered)"
                )
            }
            appendLine()
        }

        if (ChatEngine.isDeadEnd(state)) {
            appendLine("> (that branch has no next step yet)")
        }
    }

    /** What to pre-fill in the save dialog. */
    fun fileName(title: String, date: String): String {
        val slug = title
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(60)
            .trim('-')
            .ifEmpty { "chat" }

        return "$slug-$date.txt"
    }
}
