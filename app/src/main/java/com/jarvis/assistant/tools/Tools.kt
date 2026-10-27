package com.jarvis.assistant.tools

import com.jarvis.assistant.commands.SettingsDestination

/** Outcome of an app launch attempt. Only [Launched] means startActivity returned without error. */
sealed interface AppLaunchOutcome {
    data class Launched(val label: String) : AppLaunchOutcome
    data object NotFound : AppLaunchOutcome
    data class Ambiguous(val labels: List<String>) : AppLaunchOutcome
    data class Failed(val label: String) : AppLaunchOutcome

    /** Android blocked a background start; a "Tap to open" notification was posted instead. */
    data class NeedsUserTap(val label: String) : AppLaunchOutcome
}

enum class SettingsOpenOutcome { Opened, NeedsUserTap, Failed }

interface AppLauncher {
    suspend fun launch(appName: String): AppLaunchOutcome
}

data class BatteryStatus(val percent: Int, val charging: Boolean)

interface DeviceInfoTool {
    /** Null if the battery level cannot be read. */
    fun batteryStatus(): BatteryStatus?

    /** Locale-formatted current time, e.g. "3:45 PM". */
    fun currentTimeText(): String

    /** Locale-formatted current date, e.g. "Tuesday, October 27, 2026"; null if unavailable. */
    fun currentDateText(): String? = null
}

interface SettingsNavigationTool {
    /** Opens a settings screen (never toggles). Returns true only if startActivity succeeded. */
    fun open(destination: SettingsDestination): Boolean

    /** Like [open], but distinguishes "user must tap a notification" (background start blocked). */
    fun openDetailed(destination: SettingsDestination): SettingsOpenOutcome =
        if (open(destination)) SettingsOpenOutcome.Opened else SettingsOpenOutcome.Failed
}

/** Notification content is sensitive: keep in memory only and never log it. */
data class NotificationSummary(val appLabel: String, val title: String?, val text: String?)

interface NotificationReader {
    fun isAccessEnabled(): Boolean
    fun latest(): NotificationSummary?
}

interface ReminderTool {
    /** Returns true only if the reminder was scheduled. */
    fun schedule(title: String, delayMinutes: Int): Boolean

    /** User-facing reason the last [schedule] call returned false, if known. */
    fun lastFailureReason(): String? = null
}
