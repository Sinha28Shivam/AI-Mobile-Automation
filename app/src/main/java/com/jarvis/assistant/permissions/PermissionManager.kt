package com.jarvis.assistant.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Central place for runtime-permission checks. Requests are launched from the UI only after a user action. */
object PermissionManager {

    const val MICROPHONE = Manifest.permission.RECORD_AUDIO

    fun hasMicrophonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, MICROPHONE) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS is a runtime permission only on Android 13+. */
    fun needsNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasNotificationPermission(context: Context): Boolean =
        if (needsNotificationPermission()) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** Permissions to request when the user taps Start: microphone plus notifications on 13+. */
    fun listeningPermissions(): Array<String> =
        if (needsNotificationPermission()) {
            arrayOf(MICROPHONE, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(MICROPHONE)
        }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
