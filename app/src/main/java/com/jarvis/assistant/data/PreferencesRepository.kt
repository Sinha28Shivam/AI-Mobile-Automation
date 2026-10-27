package com.jarvis.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

data class JarvisSettings(
    val onboardingDone: Boolean = false,
    val historyEnabled: Boolean = false,
    val wakeSensitivity: Float = DEFAULT_WAKE_SENSITIVITY,
) {
    companion object {
        const val DEFAULT_WAKE_SENSITIVITY = 0.5f
    }
}

/** App-private settings stored with DataStore. */
class PreferencesRepository(context: Context) {

    private val dataStore = context.applicationContext.jarvisDataStore

    val settings: Flow<JarvisSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            JarvisSettings(
                onboardingDone = prefs[KEY_ONBOARDING_DONE] ?: false,
                historyEnabled = prefs[KEY_HISTORY_ENABLED] ?: false,
                wakeSensitivity = prefs[KEY_WAKE_SENSITIVITY] ?: JarvisSettings.DEFAULT_WAKE_SENSITIVITY,
            )
        }

    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_HISTORY_ENABLED] = enabled }
    }

    suspend fun setWakeSensitivity(value: Float) {
        dataStore.edit { it[KEY_WAKE_SENSITIVITY] = value.coerceIn(0f, 1f) }
    }

    private companion object {
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val KEY_WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
    }
}
