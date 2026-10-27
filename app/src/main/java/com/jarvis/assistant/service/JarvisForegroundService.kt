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
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.speech.LocalSpeechEngineAdapter
import com.jarvis.assistant.wakeword.VoskKeywordWakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/**
 * Owns the lifecycle of active microphone listening. Started only from a
 * visible user action; shows a persistent notification with a Stop action.
 *
 * On start it loads the local Vosk model (off the main thread), then wires
 * AudioCaptureController (the single mic owner) -> [VoicePipeline]. Without an
 * installed model it reports an error instead of listening.
 */
class JarvisForegroundService : Service() {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences by lazy { PreferencesRepository(this) }

    private var audioCapture: AudioCaptureController? = null
    private var pipeline: VoicePipeline? = null
    private var startupJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingTapToTalk = false

    @Volatile
    private var historyEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }

        val tapToTalk = action == ACTION_TAP_TO_TALK
        if (pipeline != null || startupJob?.isActive == true) {
            // Already running in the foreground. Re-post the notification in case this arrived via
            // startForegroundService, which requires a startForeground call for every start.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType())
            } catch (e: Exception) {
                Log.w(TAG, "startForeground refresh failed: ${e.javaClass.simpleName}")
            }
            if (tapToTalk) {
                val running = pipeline
                if (running != null) running.requestTapToTalk() else pendingTapToTalk = true
            }
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

        pendingTapToTalk = tapToTalk
        ServiceNoticeRepository.clear()
        ServiceStateRepository.update(ServiceState.Starting)
        startupJob = serviceScope.launch { startPipeline() }
        return START_NOT_STICKY
    }

    private fun foregroundType(): Int =
        // FOREGROUND_SERVICE_TYPE_MICROPHONE exists from API 30; earlier versions need no type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private suspend fun startPipeline() {
        val settings = preferences.settings.first()
        historyEnabled = settings.historyEnabled

        // withContext rethrows cancellation on return even for NonCancellable blocks, so keep a
        // handle to close the native model if Stop arrives while it is loading.
        var loadedModel: Model? = null
        val load = try {
            withContext(Dispatchers.IO + NonCancellable) {
                loadModel().also { if (it is ModelLoad.Loaded) loadedModel = it.model }
            }
        } catch (e: CancellationException) {
            loadedModel?.close()
            throw e
        }
        val model = when (load) {
            ModelLoad.Missing -> {
                failAndStop(MODEL_MISSING_MESSAGE)
                return
            }
            is ModelLoad.Failed -> {
                failAndStop(load.message)
                return
            }
            is ModelLoad.Loaded -> load.model
        }
        if (!currentCoroutineContext().isActive || !ServiceStateRepository.state.value.isActive) {
            model.close()
            return
        }

        val pipeline = try {
            buildPipeline(model, settings.wakeSensitivity)
        } catch (e: Exception) {
            Log.w(TAG, "Recognizer init failed: ${e.javaClass.simpleName}")
            model.close()
            failAndStop("Speech recognizer could not be started")
            return
        }
        this.pipeline = pipeline
        pipeline.start()
        observeSettings(pipeline)

        if (!startCapture(pipeline)) return
        ServiceStateRepository.updateIfActive(ServiceState.Listening)
        if (pendingTapToTalk) {
            pendingTapToTalk = false
            pipeline.requestTapToTalk()
        }
    }

    private sealed interface ModelLoad {
        data object Missing : ModelLoad
        data class Failed(val message: String) : ModelLoad
        class Loaded(val model: Model) : ModelLoad
    }

    private fun loadModel(): ModelLoad {
        val dir = LocalModelManager.get(this).installedModelDir() ?: return ModelLoad.Missing
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            ModelLoad.Loaded(Model(dir.absolutePath))
        } catch (e: Exception) {
            Log.w(TAG, "Speech model load failed: ${e.javaClass.simpleName}")
            ModelLoad.Failed("Speech model could not be loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Speech engine native library unavailable")
            ModelLoad.Failed("Speech engine is not supported on this device")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory while loading speech model")
            ModelLoad.Failed("Not enough memory to load the speech model")
        }
    }

    private fun buildPipeline(model: Model, sensitivity: Float): VoicePipeline {
        val sampleRate = AudioCaptureController.SAMPLE_RATE
        val detector = VoskKeywordWakeWordDetector(model, sampleRate, initialSensitivity = sensitivity)
        val engine = try {
            LocalSpeechEngineAdapter(model, sampleRate)
        } catch (e: Exception) {
            detector.release()
            throw e
        }
        val handler = VoicePipelineConfig.commandHandlerProvider(applicationContext)
        val speaker = VoicePipelineConfig.speakerProvider(applicationContext)
        return VoicePipeline(
            wakeWord = detector,
            speechToText = engine,
            commandHandler = handler,
            speaker = speaker,
            onStopRequested = { mainHandler.post { stopListening() } },
            onExchange = { command, reply ->
                if (historyEnabled) LastExchangeRepository.set(LastExchange(command, reply))
            },
            onReleased = {
                // Recognizers are freed first by the pipeline; only then is the shared model freed.
                model.close()
                closeQuietly(handler)
                closeQuietly(speaker)
            },
        )
    }

    private fun observeSettings(pipeline: VoicePipeline) {
        settingsJob?.cancel()
        settingsJob = serviceScope.launch {
            preferences.settings.collect { settings ->
                historyEnabled = settings.historyEnabled
                if (!settings.historyEnabled) LastExchangeRepository.clear()
                pipeline.setWakeSensitivity(settings.wakeSensitivity)
            }
        }
    }

    private fun startCapture(pipeline: VoicePipeline): Boolean {
        val controller = AudioCaptureController(onError = ::onCaptureError)
        audioCapture = controller
        controller.addFrameListener(pipeline::offerFrame)
        val started = try {
            controller.start()
        } catch (e: SecurityException) {
            onCaptureError("Microphone permission denied")
            false
        }
        if (!started && ServiceStateRepository.state.value !is ServiceState.Error) {
            onCaptureError("Microphone unavailable")
        }
        return started
    }

    private fun failAndStop(message: String) {
        ServiceStateRepository.update(ServiceState.Error(message))
        releaseAndStop(resetState = false)
    }

    /** May be invoked from the capture thread; teardown is marshalled to the main thread. */
    private fun onCaptureError(message: String) {
        ServiceStateRepository.update(ServiceState.Error(message))
        mainHandler.post { releaseAndStop(resetState = false) }
    }

    private fun stopListening() {
        releaseAndStop(resetState = true)
    }

    private fun releaseResources() {
        startupJob?.cancel()
        startupJob = null
        settingsJob?.cancel()
        settingsJob = null
        pendingTapToTalk = false
        audioCapture?.release()
        audioCapture = null
        pipeline?.stop()
        pipeline = null
    }

    private fun releaseAndStop(resetState: Boolean) {
        if (resetState) ServiceStateRepository.update(ServiceState.Stopped)
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (ServiceStateRepository.state.value.isActive) {
            ServiceStateRepository.update(ServiceState.Stopped)
        }
        releaseResources()
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
        val talk = PendingIntent.getService(
            this,
            2,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_TAP_TO_TALK),
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
            .addAction(0, getString(R.string.notification_action_talk), talk)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.assistant.action.START"
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP"
        const val ACTION_TAP_TO_TALK = "com.jarvis.assistant.action.TAP_TO_TALK"
        const val MODEL_MISSING_MESSAGE = "Speech model not installed"

        /** Must be called from a visible activity / user action. */
        fun start(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Captures one command immediately, starting the service first if needed. User action only. */
        fun tapToTalk(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).setAction(ACTION_TAP_TO_TALK)
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
