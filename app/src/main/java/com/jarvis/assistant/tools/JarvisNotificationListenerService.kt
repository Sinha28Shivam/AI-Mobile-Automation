package com.jarvis.assistant.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps only the latest few notifications in memory (never persisted, never logged).
 * Works only after the user enables notification access in system settings.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        NotificationStore.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName || n.isOngoing) return
        val notification = n.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(MAX_FIELD)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(MAX_FIELD)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        NotificationStore.add(n.key, NotificationSummary(appLabel(n.packageName), title, text))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationStore.remove(it) }
    }

    override fun onDestroy() {
        connected = false
        NotificationStore.clear()
        super.onDestroy()
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

        fun componentName(context: Context) =
            ComponentName(context, JarvisNotificationListenerService::class.java)
    }
}

/** In-memory ring of recent notifications. */
internal object NotificationStore {
    private const val CAPACITY = 5
    private val items = ArrayDeque<Pair<String, NotificationSummary>>()

    @Synchronized
    fun add(key: String, summary: NotificationSummary) {
        items.removeAll { it.first == key }
        items.addLast(key to summary)
        while (items.size > CAPACITY) items.removeFirst()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.first == key }
    }

    @Synchronized
    fun latest(): NotificationSummary? = items.lastOrNull()?.second

    @Synchronized
    fun clear() = items.clear()
}

class AndroidNotificationReader(context: Context) : NotificationReader {
    private val appContext = context.applicationContext

    override fun isAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    override fun latest(): NotificationSummary? {
        if (!isAccessEnabled()) {
            NotificationStore.clear()
            return null
        }
        return NotificationStore.latest()
    }
}
