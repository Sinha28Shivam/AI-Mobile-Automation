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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.commands.JarvisCommand
import com.jarvis.assistant.service.ServiceNoticeRepository
import java.io.File
import java.io.IOException

/** Why a reminder could not be scheduled; [message] is honest, user-facing and contains no title. */
enum class ReminderLimitation(val message: String) {
    InvalidRequest("Reminders need a short title and a delay between 1 minute and 24 hours."),
    NotificationsDisabled(
        "I can't set reminders because JARVIS notifications are turned off. " +
            "Allow notifications for JARVIS in Android settings.",
    ),
    ChannelDisabled(
        "I can't set reminders because the Reminders notification category is turned off. " +
            "Turn it on in JARVIS notification settings.",
    ),
    TooMany("You have too many pending reminders."),
    SchedulingFailed("Android did not accept the reminder."),
}

/**
 * Schedules reminders with inexact [AlarmManager.set] (no exact-alarm permission), so delivery
 * may be delayed by several minutes, more in battery saver / Doze. Pending reminders are saved
 * in app-private storage and rescheduled after reboot or app update by [ReminderBootReceiver]
 * and on app start via [rescheduleAll]. Overdue reminders (up to 24 h) are delivered as "missed".
 */
class AndroidReminderTool(context: Context) : ReminderTool {

    private val appContext = context.applicationContext

    /** Reason the last [schedule] call returned false, or null if it succeeded. */
    @Volatile
    var lastLimitation: ReminderLimitation? = null
        private set

    override fun schedule(title: String, delayMinutes: Int): Boolean {
        val limitation = scheduleOrLimitation(title, delayMinutes)
        lastLimitation = limitation
        if (limitation != null) {
            Log.i(TAG, "Reminder not scheduled: ${limitation.name}")
            if (limitation != ReminderLimitation.InvalidRequest) ServiceNoticeRepository.post(limitation.message)
        }
        return limitation == null
    }

    override fun lastFailureReason(): String? =
        lastLimitation?.takeIf { it != ReminderLimitation.InvalidRequest }?.message

    /** Schedules the reminder; returns null on success or the reason it could not be scheduled. */
    fun scheduleOrLimitation(title: String, delayMinutes: Int): ReminderLimitation? {
        if (!ReminderValidation.isValidDelay(delayMinutes)) return ReminderLimitation.InvalidRequest
        val valid = JarvisCommand.CreateReminder.create(title, delayMinutes) ?: return ReminderLimitation.InvalidRequest
        notificationLimitation(appContext)?.let { return it }
        val trigger = System.currentTimeMillis() + valid.delayMinutes * 60_000L
        val store = store(appContext)
        val reminder = try {
            store.add(valid.title, trigger)
        } catch (e: IOException) {
            Log.w(TAG, "Reminder store failed: ${e.javaClass.simpleName}")
            return ReminderLimitation.SchedulingFailed
        } ?: return ReminderLimitation.TooMany
        if (!setAlarm(appContext, reminder)) {
            store.remove(reminder.id)
            return ReminderLimitation.SchedulingFailed
        }
        return null
    }

    /** True if reminder notifications can actually be shown. */
    fun canPostNotifications(): Boolean = notificationLimitation(appContext) == null

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val TAG = "AndroidReminderTool"

        fun store(context: Context) = ReminderStore(File(context.applicationContext.filesDir, ReminderStore.FILE_NAME))

        internal fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        /** Null if a reminder notification would be visible; otherwise why not. */
        fun notificationLimitation(context: Context): ReminderLimitation? {
            if (!canNotify(context)) return ReminderLimitation.NotificationsDisabled
            ensureChannel(context)
            val channel = NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return ReminderLimitation.ChannelDisabled
            }
            return null
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

        internal fun reminderIntent(context: Context, reminder: ScheduledReminder): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderValidation.ACTION_REMINDER)
                .putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
                .putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            return PendingIntent.getBroadcast(
                context.applicationContext,
                reminder.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun setAlarm(context: Context, reminder: ScheduledReminder): Boolean {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            return try {
                am.set(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, reminderIntent(context, reminder))
                true
            } catch (_: SecurityException) {
                false
            }
        }

        /**
         * Re-registers all saved reminders (alarms are lost on reboot and force-stop). Overdue
         * reminders up to 24 h old are shown now as "missed"; older ones are dropped. Never
         * starts listening. Call on app start; also run by [ReminderBootReceiver].
         */
        fun rescheduleAll(context: Context) {
            val app = context.applicationContext
            val store = store(app)
            val plan = try {
                ReminderValidation.plan(store.all(), System.currentTimeMillis())
            } catch (e: IOException) {
                Log.w(TAG, "Reminder reschedule failed: ${e.javaClass.simpleName}")
                return
            }
            plan.schedule.forEach { setAlarm(app, it) }
            plan.deliverNow.forEach { ReminderReceiver.showNotification(app, it, missed = true) }
            try {
                store.removeAll((plan.deliverNow + plan.drop).map { it.id })
            } catch (e: IOException) {
                Log.w(TAG, "Reminder cleanup failed: ${e.javaClass.simpleName}")
            }
            Log.i(TAG, "Reminders rescheduled=${plan.schedule.size} missed=${plan.deliverNow.size} dropped=${plan.drop.size}")
        }
    }
}

/** Non-exported. Accepts only the exact reminder action with an id/title matching a saved reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val store = AndroidReminderTool.store(context)
        val stored = try {
            store.get(id)
        } catch (_: IOException) {
            null
        }
        if (ReminderValidation.validateIntent(intent.action, id, title, stored) == null || stored == null) {
            Log.w(TAG, "Ignored unexpected reminder intent")
            return
        }
        try {
            store.remove(id)
        } catch (e: IOException) {
            Log.w(TAG, "Reminder store failed: ${e.javaClass.simpleName}")
        }
        val late = ReminderValidation.isLate(stored.triggerAtMillis, System.currentTimeMillis())
        showNotification(context, stored, missed = late)
    }

    companion object {
        const val ACTION_REMINDER = ReminderValidation.ACTION_REMINDER
        const val EXTRA_TITLE = "title"
        const val EXTRA_ID = "id"
        private const val TAG = "ReminderReceiver"

        // Permission is verified by canNotify() and SecurityException is handled.
        @SuppressLint("MissingPermission")
        internal fun showNotification(context: Context, reminder: ScheduledReminder, missed: Boolean) {
            if (!AndroidReminderTool.canNotify(context)) {
                Log.i(TAG, "Reminder not shown: notifications disabled")
                return
            }
            AndroidReminderTool.ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, AndroidReminderTool.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (missed) "Missed reminder" else "Reminder")
                .setContentText(reminder.title.take(ReminderValidation.MAX_RECEIVER_TITLE_LENGTH))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setWhen(reminder.triggerAtMillis)
                .setShowWhen(true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(ReminderValidation.notificationId(reminder.id), notification)
            } catch (_: SecurityException) {
                // Notification permission revoked between check and post.
            }
        }
    }
}

/**
 * Re-registers saved reminders after reboot or app update. It only reschedules reminders;
 * it never starts listening or the microphone service.
 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACCEPTED_ACTIONS) return
        val pending = goAsync()
        Thread {
            try {
                AndroidReminderTool.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        val ACCEPTED_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
