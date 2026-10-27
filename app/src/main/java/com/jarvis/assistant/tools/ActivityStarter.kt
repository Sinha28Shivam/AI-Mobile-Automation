package com.jarvis.assistant.tools

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R

/** How an activity start request ended. Only [Started] means the screen was opened directly. */
enum class StartOutcome { Started, NotificationPosted, Failed }

/**
 * Starts activities on behalf of voice commands.
 *
 * Android 10+ blocks activity starts from the background, and a foreground service alone does
 * not lift that restriction. When no JARVIS activity is visible (and the app cannot draw
 * overlays), [startActivity] silently does nothing, so we don't call it. Instead we post a
 * high-priority "Tap to open" notification whose tap is a user action Android always allows.
 */
object ActivityStarter {

    const val CHANNEL_ID = "open_requests"
    private const val NOTIFICATION_ID = 2001

    fun start(context: Context, candidates: List<Intent>, label: String): StartOutcome {
        val app = context.applicationContext
        if (candidates.isEmpty()) return StartOutcome.Failed
        if (canStartDirectly(app)) {
            return if (candidates.any { tryStart(app, it) }) StartOutcome.Started else StartOutcome.Failed
        }
        val target = candidates.firstOrNull { isResolvable(app, it) } ?: return StartOutcome.Failed
        return if (postTapToOpen(app, target, label)) StartOutcome.NotificationPosted else StartOutcome.Failed
    }

    /** True if Android lets this process start an activity right now without user interaction. */
    fun canStartDirectly(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (Settings.canDrawOverlays(context)) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // Targets are launcher intents (visible via <queries>) or system Settings screens.
    @SuppressLint("QueryPermissionsNeeded")
    private fun isResolvable(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    @SuppressLint("MissingPermission")
    private fun postTapToOpen(context: Context, target: Intent, label: String): Boolean {
        if (!AndroidReminderTool.canNotify(context)) return false
        ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Tap to open $label")
            .setContentText("Android doesn't let JARVIS open screens while it's in the background.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Open requests", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Lets you open an app or screen JARVIS couldn't open directly" },
            )
        }
    }

    private const val TIMEOUT_MS = 60_000L
}
