package com.jarvis.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** When true (default), notifications from messaging/banking/OTP apps are never kept or read. */
    val notificationBlockSensitiveApps: Boolean = true,
    /** Package names whose notifications are never kept or read. */
    val notificationExcludedPackages: Set<String> = emptySet(),
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
                notificationBlockSensitiveApps = prefs[KEY_NOTIFICATION_BLOCK_SENSITIVE] ?: true,
                notificationExcludedPackages = prefs[KEY_NOTIFICATION_EXCLUDED] ?: emptySet(),
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

    suspend fun setNotificationBlockSensitiveApps(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATION_BLOCK_SENSITIVE] = enabled }
    }

    /** Replaces the excluded package list. Invalid names are dropped; capped at [MAX_EXCLUDED_PACKAGES]. */
    suspend fun setNotificationExcludedPackages(packages: Set<String>) {
        val clean = sanitizePackages(packages)
        dataStore.edit { it[KEY_NOTIFICATION_EXCLUDED] = clean }
    }

    companion object {
        const val MAX_EXCLUDED_PACKAGES = 100
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        internal fun sanitizePackages(packages: Set<String>): Set<String> =
            packages.map { it.trim() }
                .filter { it.length <= 255 && PACKAGE_PATTERN.matches(it) }
                .take(MAX_EXCLUDED_PACKAGES)
                .toSet()

        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val KEY_WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
        private val KEY_NOTIFICATION_BLOCK_SENSITIVE = booleanPreferencesKey("notification_block_sensitive")
        private val KEY_NOTIFICATION_EXCLUDED = stringSetPreferencesKey("notification_excluded_packages")
    }
}
