package com.knasiotis.decisionwizard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What opening the app should do. */
enum class LaunchBehaviour {
    /** Reopen the most recently used chat. Nothing to resume falls back to the list. */
    RESUME_LAST,

    /** Go to the graph list to pick what the new chat runs on. */
    NEW_CHAT
}

private val Context.preferences by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("launch_behaviour")

    val launchBehaviour: Flow<LaunchBehaviour> = context.preferences.data.map { prefs ->
        // An unrecognised value means a downgrade or a hand-edited file; fall
        // back rather than crashing on launch.
        runCatching { LaunchBehaviour.valueOf(prefs[key] ?: "") }
            .getOrDefault(LaunchBehaviour.RESUME_LAST)
    }

    suspend fun setLaunchBehaviour(value: LaunchBehaviour) {
        context.preferences.edit { it[key] = value.name }
    }
}
