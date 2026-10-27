package com.jarvis.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.permissions.PermissionManager

/**
 * Owns the lifecycle of active microphone listening. Started only from a
 * visible user action; shows a persistent notification with a Stop action.
 */
class JarvisForegroundService : Service() {

    private var audioCapture: AudioCaptureController? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }

        if (!PermissionManager.hasMicrophonePermission(this)) {
            ServiceStateRepository.update(ServiceState.PermissionRequired)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType())
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException or SecurityException on newer APIs.
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            ServiceStateRepository.update(ServiceState.Error("Could not start listening service"))
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture()
        return START_NOT_STICKY
    }

    private fun foregroundType(): Int =
        // FOREGROUND_SERVICE_TYPE_MICROPHONE exists from API 30; earlier versions need no type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private fun startCapture() {
        if (audioCapture?.isCapturing == true) return
        val controller = audioCapture ?: AudioCaptureController(onError = ::onCaptureError).also {
            audioCapture = it
        }
        val started = try {
            controller.start()
        } catch (e: SecurityException) {
            onCaptureError("Microphone permission denied")
            false
        }
        if (started) {
            ServiceStateRepository.update(ServiceState.Listening)
        } else if (ServiceStateRepository.state.value !is ServiceState.Error) {
            onCaptureError("Microphone unavailable")
        }
    }

    /** May be invoked from the capture thread; teardown is marshalled to the main thread. */
    private fun onCaptureError(message: String) {
        ServiceStateRepository.update(ServiceState.Error(message))
        mainHandler.post { releaseAndStop(resetState = false) }
    }

    private fun stopListening() {
        releaseAndStop(resetState = true)
    }

    private fun releaseAndStop(resetState: Boolean) {
        audioCapture?.release()
        audioCapture = null
        if (resetState) ServiceStateRepository.update(ServiceState.Stopped)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        audioCapture?.release()
        audioCapture = null
        if (ServiceStateRepository.state.value.isActive) {
            ServiceStateRepository.update(ServiceState.Stopped)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.assistant.action.START"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP"

        /** Must be called from a visible activity / user action. */
        fun start(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).setAction(ACTION_STOP)
            // Plain startService is fine here: the service is already running in the foreground.
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                context.stopService(Intent(context, JarvisForegroundService::class.java))
            }
        }
    }
}
