package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.commands.SettingsDestination

/** Opens official settings screens. It never changes a setting. */
class AndroidSettingsNavigationTool(context: Context) : SettingsNavigationTool {

    private val appContext = context.applicationContext

    override fun open(destination: SettingsDestination): Boolean =
        openDetailed(destination) == SettingsOpenOutcome.Opened

    override fun openDetailed(destination: SettingsDestination): SettingsOpenOutcome =
        // No silent fallback to the main settings page, so the spoken confirmation stays truthful.
        when (ActivityStarter.start(appContext, intentsFor(destination), destination.spokenName)) {
            StartOutcome.Started -> SettingsOpenOutcome.Opened
            StartOutcome.NotificationPosted -> SettingsOpenOutcome.NeedsUserTap
            StartOutcome.Failed -> SettingsOpenOutcome.Failed
        }

    private fun intentsFor(destination: SettingsDestination): List<Intent> {
        val pkg = appContext.packageName
        return when (destination) {
            SettingsDestination.WIFI -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
            SettingsDestination.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            SettingsDestination.SOUND -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
            SettingsDestination.DISPLAY -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            SettingsDestination.BATTERY -> listOf(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            )
            SettingsDestination.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            )
            SettingsDestination.APP_SETTINGS -> listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
            )
            SettingsDestination.NOTIFICATION_ACCESS -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            SettingsDestination.MAIN -> listOf(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
