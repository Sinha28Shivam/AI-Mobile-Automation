package com.jarvis.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.commands.JarvisCommand

/**
 * Schedules reminders with inexact [AlarmManager.set] (no exact-alarm permission).
 * Reminders are not persisted and do not survive a reboot.
 */
class AndroidReminderTool(context: Context) : ReminderTool {

    private val appContext = context.applicationContext

    override fun schedule(title: String, delayMinutes: Int): Boolean {
        val valid = JarvisCommand.CreateReminder.create(title, delayMinutes) ?: return false
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val requestCode = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val intent = Intent(appContext, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMINDER)
            .putExtra(ReminderReceiver.EXTRA_TITLE, valid.title)
            .putExtra(ReminderReceiver.EXTRA_ID, requestCode)
        val pi = PendingIntent.getBroadcast(
            appContext, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + valid.delayMinutes * 60_000L,
                pi,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** True if reminder notifications can actually be shown. */
    fun canPostNotifications(): Boolean = canNotify(appContext)

    companion object {
        const val CHANNEL_ID = "reminders"

        internal fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        internal fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Reminders you asked JARVIS to set" },
                )
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    // Permission is verified by canNotify() and SecurityException is handled.
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER) return
        val title = intent.getStringExtra(EXTRA_TITLE)?.take(JarvisCommand.CreateReminder.MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: return
        if (!AndroidReminderTool.canNotify(context)) return
        AndroidReminderTool.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AndroidReminderTool.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(intent.getIntExtra(EXTRA_ID, 1), notification)
        } catch (_: SecurityException) {
            // Notification permission revoked between check and post.
        }
    }

    companion object {
        const val ACTION_REMINDER = "com.jarvis.assistant.action.REMINDER"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
    }
}
