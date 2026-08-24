package com.knasiotis.decisionwizard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What opening the app should do. */
enum class LaunchBehaviour {
    /** Reopen the most recently used chat. Nothing to resume falls back to the list. */
    RESUME_LAST,

    /** Go to the graph list to pick what the new chat runs on. */
    NEW_CHAT,

    /** Land on the chat list and decide from there. */
    CHAT_LIST
}

/** How long a chat is kept after it was last opened. Zero means forever. */
object ChatRetention {
    const val FOREVER = 0

    /** The shortcuts. Anything else is a custom number of days. */
    val PRESETS = listOf(FOREVER, 7, 30)

    const val MAX_DAYS = 3650

    fun label(days: Int): String = when (days) {
        FOREVER -> "Keep them"
        1 -> "After a day"
        else -> "After $days days"
    }

    fun isPreset(days: Int): Boolean = days in PRESETS

    /** Null for anything that is not a usable number of days. */
    fun parse(input: String): Int? =
        input.trim().toIntOrNull()?.takeIf { it in 1..MAX_DAYS }
}

/** How far a link may reach before the canvas draws chips instead of a line. */
object DrawnSpan {
    const val ONE_LAYER = 1
    const val TWO_LAYERS = 2
    const val DEFAULT = TWO_LAYERS

    fun label(span: Int): String = when (span) {
        ONE_LAYER -> "Only to the next question"
        else -> "Up to two questions ahead"
    }

    fun detail(span: Int): String = when (span) {
        ONE_LAYER -> "Anything further becomes a pair of chips. Fewer lines, and none of them cross a row of questions."
        else -> "A link two questions ahead is drawn as a line that threads between the questions in between."
    }

    val CHOICES = listOf(ONE_LAYER, TWO_LAYERS)

    fun sanitise(span: Int): Int = if (span == ONE_LAYER) ONE_LAYER else TWO_LAYERS
}

private val Context.preferences by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val launchKey = stringPreferencesKey("launch_behaviour")
    private val retentionKey = intPreferencesKey("chat_retention_days")
    private val drawnSpanKey = intPreferencesKey("editor_drawn_span")

    val launchBehaviour: Flow<LaunchBehaviour> = context.preferences.data.map { prefs ->
        // An unrecognised value means a downgrade or a hand-edited file; fall
        // back rather than crashing on launch.
        runCatching { LaunchBehaviour.valueOf(prefs[launchKey] ?: "") }
            .getOrDefault(LaunchBehaviour.RESUME_LAST)
    }

    /** Defaults to keeping chats. Deleting the user's history is never the default. */
    val chatRetentionDays: Flow<Int> = context.preferences.data.map { prefs ->
        prefs[retentionKey]?.takeIf { it >= 0 } ?: ChatRetention.FOREVER
    }

    /**
     * Defaults to drawing two-layer links as lines, which is what every release
     * before the setting existed did.
     */
    val drawnSpan: Flow<Int> = context.preferences.data.map { prefs ->
        DrawnSpan.sanitise(prefs[drawnSpanKey] ?: DrawnSpan.DEFAULT)
    }

    suspend fun setDrawnSpan(span: Int) {
        context.preferences.edit { it[drawnSpanKey] = DrawnSpan.sanitise(span) }
    }

    suspend fun setLaunchBehaviour(value: LaunchBehaviour) {
        context.preferences.edit { it[launchKey] = value.name }
    }

    suspend fun setChatRetentionDays(days: Int) {
        context.preferences.edit { it[retentionKey] = days.coerceAtLeast(0) }
    }
}
