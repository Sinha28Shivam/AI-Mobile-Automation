package com.jarvis.assistant.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.speech.LocalSpeechEngineAdapter
import com.jarvis.assistant.wakeword.VoskKeywordWakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/**
 * Foreground service hosting active microphone listening. Started only from a visible
 * user action (Start / Tap to talk in the app, or the Talk/Stop actions of its own
 * notification). There is no BOOT_COMPLETED receiver: listening never auto-starts after
 * reboot, and `START_NOT_STICKY` means Android does not restart it after the process is
 * killed or the app is force-stopped.
 *
 * Resource ownership (each resource has exactly one owner and is released exactly once):
 *
 * | Resource                          | Owner                                   | Released                                                    |
 * |-----------------------------------|-----------------------------------------|-------------------------------------------------------------|
 * | Session lifecycle / generation    | [ListeningSessionController]            | `stop()` / `stopAndJoin()`; Stopped only after full release |
 * | `AudioRecord` + read coroutine    | [AudioCaptureController] (one/session)  | after the read loop exits (`stopAndJoin`)                   |
 * | [VoicePipeline] + its coroutine   | the session (new instance per session)  | `VoicePipeline.stopAndJoin()`                               |
 * | Vosk wake + command `Recognizer`s | the pipeline's consumer coroutine       | its completion handler, after the last use                  |
 * | Shared Vosk `Model`               | the session's `LoadResult.Ready`        | `onClose`, after recognizers were freed                     |
 * | TTS speaker, command handler      | the session (fresh from [VoicePipelineConfig]) | `onClose` (if `AutoCloseable`)                        |
 * | Foreground notification           | this service                            | `onIdle` -> stopForeground + stopSelf, or onDestroy         |
 *
 * onTaskRemoved: swiping the app from Recents does not stop listening (the foreground
 * notification keeps it visible and stoppable); the default behaviour is kept on purpose.
 * onDestroy stops any session; teardown runs to completion on a background scope.
 */
class JarvisForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { PreferencesRepository(this) }
    private var observerJob: Job? = null
    private var inForeground = false

    @Volatile
    private var historyEnabled = false

    @Volatile
    private var talkUnavailableNotice = false

    private val controller by lazy {
        ListeningSessionController(
            scope = serviceScope,
            backgroundDispatcher = Dispatchers.IO,
            store = ServiceStateRepository,
            hasMicrophonePermission = { PermissionManager.hasMicrophonePermission(this) },
            loader = { loadPipeline(it) },
            captureFactory = { onError, onSilenced ->
                AudioCaptureController(
                    source = AndroidAudioRecordSource(this),
                    onError = onError,
                    onSilencedChanged = onSilenced,
                )
            },
            onIdle = ::stopForegroundAndSelf,
            clock = SystemClock::elapsedRealtime,
            metrics = VoiceMetrics.shared,
            priorTeardown = lastServiceTeardown,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> controller.stop()
            ACTION_NOTIFICATION_TALK -> onNotificationTalk()
            else -> onStartRequest(tapToTalk = intent?.action == ACTION_TAP_TO_TALK)
        }
        return START_NOT_STICKY
    }

    private fun onStartRequest(tapToTalk: Boolean) {
        // Started via startForegroundService: startForeground must be called for every start.
        if (!enterForeground()) return
        val outcome = controller.start(tapToTalk)
        if (outcome == ListeningSessionController.StartOutcome.PermissionMissing) {
            stopForegroundAndSelf()
            return
        }
        if (outcome == ListeningSessionController.StartOutcome.Started) {
            ServiceNoticeRepository.clear()
            observeWhileRunning()
        }
    }

    private fun onNotificationTalk() {
        // Delivered with plain startService from our own notification: only valid while listening.
        if (controller.isIdle) {
            stopForegroundAndSelf()
            return
        }
        talkUnavailableNotice = !controller.requestTapToTalk(fromNotification = true)
        updateNotification(ServiceStateRepository.state.value)
    }

    /** Returns false (with an Error state) if Android refuses foreground start. */
    private fun enterForeground(): Boolean {
        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(ServiceStateRepository.state.value),
                foregroundType(),
            )
            inForeground = true
            return true
        } catch (e: Exception) {
            val message = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException ->
                    MSG_FGS_NOT_ALLOWED
                e is SecurityException -> MSG_FGS_SECURITY
                else -> MSG_FGS_FAILED
            }
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            if (controller.isIdle) {
                ServiceStateRepository.update(
                    ServiceState.Error(
                        message,
                        if (e is SecurityException) ErrorRecovery.OpenAppSettings else ErrorRecovery.Retry,
                    ),
                )
                stopSelf()
            }
            return false
        }
    }

    private fun foregroundType(): Int =
        // FOREGROUND_SERVICE_TYPE_MICROPHONE exists from API 30; earlier versions need no type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private fun observeWhileRunning() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            launch {
                preferences.settings.collect { settings ->
                    historyEnabled = settings.historyEnabled
                    if (!settings.historyEnabled) LastExchangeRepository.clear()
                    controller.setWakeSensitivity(settings.wakeSensitivity)
                }
            }
            launch {
                ServiceStateRepository.state.collect { state ->
                    // A "Talk unavailable" hint lasts until the next real state change.
                    talkUnavailableNotice = false
                    updateNotification(state)
                }
            }
        }
    }

    private suspend fun loadPipeline(callbacks: PipelineCallbacks): LoadResult {
        val settings = preferences.settings.first()
        historyEnabled = settings.historyEnabled
        val dir = LocalModelManager.get(this).installedModelDir()
            ?: return LoadResult.Failed(MODEL_MISSING_MESSAGE, ErrorRecovery.OpenModelSetup)
        val model = try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Speech model load failed: ${e.javaClass.simpleName}")
            return LoadResult.Failed(MSG_MODEL_LOAD_FAILED, ErrorRecovery.OpenModelSetup)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Speech engine native library unavailable")
            return LoadResult.Failed("Speech engine is not supported on this device.", ErrorRecovery.OpenModelSetup)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory while loading speech model")
            return LoadResult.Failed("Not enough memory to load the speech model. Close other apps and tap Start.")
        }
        return try {
            buildPipeline(model, settings.wakeSensitivity, callbacks)
        } catch (e: Exception) {
            Log.w(TAG, "Recognizer init failed: ${e.javaClass.simpleName}")
            model.close()
            LoadResult.Failed(ListeningSessionController.MSG_LOAD_FAILED)
        }
    }

    private suspend fun buildPipeline(model: Model, sensitivity: Float, callbacks: PipelineCallbacks): LoadResult.Ready {
        val sampleRate = AudioCaptureController.SAMPLE_RATE
        val detector = VoskKeywordWakeWordDetector(model, sampleRate, initialSensitivity = sensitivity)
        val engine = try {
            LocalSpeechEngineAdapter(model, sampleRate)
        } catch (e: Exception) {
            detector.release()
            throw e
        }
        // Providers (e.g. TextToSpeech) expect the main thread, as before.
        val (handler, speaker) = withContext(Dispatchers.Main) {
            VoicePipelineConfig.commandHandlerProvider(applicationContext) to
                VoicePipelineConfig.speakerProvider(applicationContext)
        }
        val pipeline = VoicePipeline(
            wakeWord = detector,
            speechToText = engine,
            commandHandler = handler,
            speaker = speaker,
            onStopRequested = callbacks.onStopRequested,
            onExchange = { command, reply ->
                if (historyEnabled) LastExchangeRepository.set(LastExchange(command, reply))
            },
            setState = callbacks.setState,
            metrics = callbacks.metrics,
        )
        // Runs after the pipeline has released both recognizers, so the shared model is freed last.
        return LoadResult.Ready(pipeline) {
            model.close()
            closeQuietly(handler)
            closeQuietly(speaker)
        }
    }

    private fun stopForegroundAndSelf() {
        observerJob?.cancel()
        observerJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        // Teardown continues on the controller's background scope and ends in Stopped.
        controller.stop()
        // A new service instance must not open the mic before this one has released it.
        lastServiceTeardown = controller.pendingTeardown
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun closeQuietly(candidate: Any) {
        if (candidate !is AutoCloseable) return
        try {
            candidate.close()
        } catch (e: Exception) {
            Log.w(TAG, "Close failed: ${e.javaClass.simpleName}")
        }
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

    @SuppressLint("MissingPermission") // Checked via PermissionManager.hasNotificationPermission below.
    private fun updateNotification(state: ServiceState) {
        if (!inForeground || !state.isActive) return
        if (!PermissionManager.hasNotificationPermission(this)) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification update not permitted")
        }
    }

    private fun buildNotification(state: ServiceState): Notification {
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
        val talk = PendingIntent.getService(
            this,
            2,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_NOTIFICATION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            talkUnavailableNotice -> "Talk is available only while JARVIS is listening for the wake word."
            state.isActive && state != ServiceState.Starting -> "${state.label}. Audio stays on this device and is never saved."
            else -> getString(R.string.notification_text)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == ServiceState.Listening) builder.addAction(0, getString(R.string.notification_action_talk), talk)
        return builder.addAction(0, getString(R.string.notification_action_stop), stop).build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.assistant.action.START"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP"
        const val ACTION_TAP_TO_TALK = "com.jarvis.assistant.action.TAP_TO_TALK"
        private const val ACTION_NOTIFICATION_TALK = "com.jarvis.assistant.action.NOTIFICATION_TALK"
        const val MODEL_MISSING_MESSAGE = "Speech model not installed"
        const val MSG_MODEL_LOAD_FAILED = "Speech model could not be loaded. Reinstall it from Speech model setup."
        const val MSG_FGS_NOT_ALLOWED =
            "Android blocked listening from starting in the background. Open JARVIS and tap Start."
        const val MSG_FGS_SECURITY = "Android denied microphone access for the listening service. Check permissions."
        const val MSG_FGS_FAILED = "Could not start the listening service. Tap Start to try again."

        @Volatile
        private var lastServiceTeardown: Job? = null

        /** Must be called from a visible activity / user action. */
        fun start(context: Context) {
            startFromUi(context, ACTION_START)
        }

        /** Captures one command immediately, starting the service first if needed. User action only. */
        fun tapToTalk(context: Context) {
            startFromUi(context, ACTION_TAP_TO_TALK)
        }

        private fun startFromUi(context: Context, action: String) {
            val intent = Intent(context, JarvisForegroundService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (API 31+) when not in the foreground.
                Log.w(TAG, "startForegroundService refused: ${e.javaClass.simpleName}")
                ServiceStateRepository.update(ServiceState.Error(MSG_FGS_NOT_ALLOWED))
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService denied")
                ServiceStateRepository.update(ServiceState.Error(MSG_FGS_SECURITY, ErrorRecovery.OpenAppSettings))
            }
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
