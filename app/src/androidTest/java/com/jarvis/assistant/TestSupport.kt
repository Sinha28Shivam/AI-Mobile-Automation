package com.jarvis.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Shared helpers for instrumentation tests. Only stable, public app entry points are used
 * (service actions, channel ids, repositories), so internal refactors do not break the suite.
 *
 * Tests never install or delete the speech model and restore any preference they change, so
 * the suite can run on a personal device without losing the user's setup.
 */
object TestSupport {

    const val PACKAGE = "com.jarvis.assistant"
    const val LISTENING_CHANNEL_ID = "jarvis_listening"
    const val REMINDERS_CHANNEL_ID = "reminders"
    const val OPEN_REQUESTS_CHANNEL_ID = "open_requests"

    val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    val notificationManager: NotificationManager
        get() = targetContext.getSystemService(NotificationManager::class.java)

    /** Permissions the listening flow needs on this API level (POST_NOTIFICATIONS is runtime from 33). */
    fun listeningPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(targetContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the Vosk model is fully installed on this device. */
    fun isModelInstalled(): Boolean = LocalModelManager.get(targetContext).installedModelDir() != null

    fun onboardingDone(): Boolean = runBlocking { PreferencesRepository(targetContext).settings.first().onboardingDone }

    fun setOnboardingDone(done: Boolean) = runBlocking { PreferencesRepository(targetContext).setOnboardingDone(done) }

    /** Best-effort wake and unlock (insecure keyguard only) so activities resume, incl. on MIUI. */
    fun wakeAndUnlock() {
        val d = device
        try {
            d.wakeUp()
        } catch (_: Exception) {
            // Some OEM builds throw if the screen is already on.
        }
        d.executeShellCommand("wm dismiss-keyguard")
        d.waitForIdle(1_000)
    }

    fun channelNotifications(channelId: String): List<StatusBarNotification> =
        notificationManager.activeNotifications.filter { it.notification.channelId == channelId }

    fun isForegroundServiceNotification(sbn: StatusBarNotification): Boolean =
        sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0

    /** Polls [condition] until it is true or [timeoutMs] elapses; returns the final result. */
    fun waitUntil(timeoutMs: Long, pollMs: Long = 100, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(pollMs)
        }
        return condition()
    }
}
