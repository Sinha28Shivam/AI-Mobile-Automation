package com.jarvis.assistant.service

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** Receives a PCM16 frame buffer and the number of valid samples in it. */
typealias AudioFrameListener = (frame: ShortArray, length: Int) -> Unit

/**
 * Sole owner of the microphone ([AudioSource]) for one listening session.
 *
 * Single use: [start] opens the source once; [stopAndJoin] (or [stop]) ends the
 * session and the instance cannot be restarted (create a new one per session).
 * Frames are read on [dispatcher] and delivered to listeners on that thread;
 * the buffer is reused and never persisted. The source is released exactly once,
 * after the read loop has exited, so release never races an in-flight read.
 *
 * [onError] fires at most once, only for unexpected failures (not after Stop).
 */
class AudioCaptureController(
    private val source: AudioSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onError: (message: String, recovery: ErrorRecovery) -> Unit = { _, _ -> },
    private val onSilencedChanged: (Boolean) -> Unit = {},
) {
    private enum class Phase { New, Running, Stopped }

    private val listeners = CopyOnWriteArrayList<AudioFrameListener>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var phase = Phase.New
    private var captureJob: Job? = null
    private var released = false

    @Volatile
    private var errorReported = false

    val isCapturing: Boolean
        get() = synchronized(lock) { phase == Phase.Running && captureJob?.isActive == true }

    fun addFrameListener(listener: AudioFrameListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeFrameListener(listener: AudioFrameListener) {
        listeners.remove(listener)
    }

    /**
     * Opens the microphone and starts the read loop. Returns true if capturing. A second call
     * while running is a no-op returning true; calling after stop returns false.
     */
    fun start(): Boolean {
        synchronized(lock) {
            when (phase) {
                Phase.Running -> return true
                Phase.Stopped -> return false
                Phase.New -> phase = Phase.Running
            }
        }
        source.onSilencedChanged = { silenced -> if (synchronized(lock) { phase == Phase.Running }) onSilencedChanged(silenced) }
        val result = try {
            source.start()
        } catch (e: SecurityException) {
            AudioStartResult.Failed(CaptureErrors.PERMISSION_DENIED, ErrorRecovery.OpenAppSettings)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio source start failed: ${e.javaClass.simpleName}")
            AudioStartResult.Failed(CaptureErrors.INIT_FAILED)
        }
        if (result is AudioStartResult.Failed) {
            synchronized(lock) { phase = Phase.Stopped }
            releaseSourceOnce()
            scope.cancel()
            reportError(result.message, result.recovery)
            return false
        }
        synchronized(lock) {
            if (phase != Phase.Running) {
                // Stopped concurrently while the source was opening.
                releaseSourceOnce()
                return false
            }
            val job = scope.launch { readLoop() }
            job.invokeOnCompletion { releaseSourceOnce() }
            captureJob = job
        }
        return true
    }

    private fun CoroutineScope.readLoop() {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (isActive) {
            val read = try {
                source.read(buffer)
            } catch (e: SecurityException) {
                failFromLoop(CaptureErrors.PERMISSION_DENIED, ErrorRecovery.OpenAppSettings)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "Audio read threw ${e.javaClass.simpleName}")
                failFromLoop(CaptureErrors.GENERIC_READ, ErrorRecovery.Retry)
                return
            }
            if (!isActive) return
            if (read < 0) {
                Log.w(TAG, "Audio read error code=$read")
                failFromLoop(CaptureErrors.forReadCode(read), ErrorRecovery.Retry)
                return
            }
            if (read == 0) continue
            for (listener in listeners) {
                try {
                    listener(buffer, read)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame listener failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun failFromLoop(message: String, recovery: ErrorRecovery) {
        val wasRunning = synchronized(lock) {
            val running = phase == Phase.Running
            phase = Phase.Stopped
            running
        }
        if (wasRunning) reportError(message, recovery)
    }

    private fun reportError(message: String, recovery: ErrorRecovery) {
        if (errorReported) return
        errorReported = true
        onError(message, recovery)
    }

    /** Requests stop without waiting. The microphone is released once the read loop exits. */
    fun stop() {
        val job = synchronized(lock) {
            val wasNew = phase == Phase.New
            phase = Phase.Stopped
            if (wasNew) {
                scope.cancel()
                return
            }
            captureJob
        }
        listeners.clear()
        job?.cancel()
        try {
            // Unblocks a pending read(); the job's completion handler then releases the source.
            source.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio source stop failed: ${e.javaClass.simpleName}")
        }
        scope.cancel()
        if (job == null) releaseSourceOnce()
    }

    /** Stops and suspends until the microphone has actually been released. */
    suspend fun stopAndJoin() {
        stop()
        synchronized(lock) { captureJob }?.join()
        // The completion handler normally released it already; this is a no-op then.
        releaseSourceOnce()
    }

    private fun releaseSourceOnce() {
        synchronized(lock) {
            if (released) return
            released = true
        }
        try {
            source.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Audio source release failed: ${e.javaClass.simpleName}")
        }
    }

    /** Test hook: true once the source has been released. */
    internal val isReleased: Boolean get() = synchronized(lock) { released }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = AndroidAudioRecordSource.SAMPLE_RATE
        const val FRAME_SAMPLES = 512
    }
}
