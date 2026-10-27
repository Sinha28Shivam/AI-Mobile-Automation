package com.jarvis.assistant.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps only the latest few notifications in memory (never persisted, never logged), each for
 * at most [NotificationStore.TTL_MS]. Apps the user excluded, and (by default) messaging,
 * banking and OTP notifications, are never kept. Works only after the user enables
 * notification access in system settings; everything is cleared when access goes away.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    private var scope: CoroutineScope? = null

    override fun onListenerConnected() {
        connected = true
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope?.cancel()
        scope = newScope
        newScope.launch {
            PreferencesRepository(applicationContext).settings.collect { s ->
                val updated = NotificationPrivacySettings(s.notificationBlockSensitiveApps, s.notificationExcludedPackages)
                privacy = updated
                val sms = defaultSmsPackage()
                NotificationStore.retainOnly { pkg, _ ->
                    pkg !in updated.excludedPackages &&
                        !(updated.blockSensitiveApps && NotificationPrivacy.isSensitiveApp(pkg, null, sms))
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        scope?.cancel()
        scope = null
        NotificationStore.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName || n.isOngoing) return
        val notification = n.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(MAX_FIELD)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(MAX_FIELD)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        if (!NotificationPrivacy.mayRetain(privacy, n.packageName, notification.category, title, text, defaultSmsPackage())) {
            NotificationStore.remove(n.key)
            return
        }
        NotificationStore.add(
            n.key,
            NotificationSummary(appLabel(n.packageName), title, text),
            SystemClock.elapsedRealtime(),
            n.packageName,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationStore.remove(it) }
    }

    override fun onDestroy() {
        connected = false
        scope?.cancel()
        scope = null
        NotificationStore.clear()
        super.onDestroy()
    }

    private fun defaultSmsPackage(): String? = try {
        Telephony.Sms.getDefaultSmsPackage(this)
    } catch (_: SecurityException) {
        null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        "an app"
    }

    companion object {
        private const val MAX_FIELD = 300

        @Volatile
        var connected: Boolean = false
            private set

        /** Safe default (sensitive apps blocked) until the user's settings are loaded. */
        @Volatile
        private var privacy = NotificationPrivacySettings()

        fun componentName(context: Context) =
            ComponentName(context, JarvisNotificationListenerService::class.java)
    }
}

/**
 * Reads retained notifications. Access is re-checked on every call (the user can revoke it in
 * system settings at any time); if it is gone, retained content is cleared immediately.
 */
class AndroidNotificationReader internal constructor(
    private val accessCheck: () -> Boolean,
    private val clock: () -> Long,
) : NotificationReader {

    constructor(context: Context) : this(
        accessCheck = context.applicationContext.let { app ->
            { NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName) }
        },
        clock = SystemClock::elapsedRealtime,
    )

    override fun isAccessEnabled(): Boolean {
        val enabled = accessCheck()
        if (!enabled) NotificationStore.clear()
        return enabled
    }

    override fun latest(): NotificationSummary? {
        if (!isAccessEnabled()) return null
        return NotificationStore.latest(clock())
    }

    companion object {
        /**
         * True if [text] contains any notification title/body currently held in memory.
         * Use before persisting anything (e.g. command history) so notification content is
         * never written to disk.
         */
        fun containsRetainedContent(text: String): Boolean =
            NotificationStore.sensitiveFragments().any { it.length >= 3 && text.contains(it) }

        /** Clears everything held in memory (e.g. when the user turns the feature off). */
        fun clearRetained() = NotificationStore.clear()
    }
}
