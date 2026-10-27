package com.jarvis.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Result of opening an [AudioSource]. */
sealed interface AudioStartResult {
    data object Started : AudioStartResult
    data class Failed(val message: String, val recovery: ErrorRecovery = ErrorRecovery.Retry) : AudioStartResult
}

/**
 * Abstraction over the platform microphone so capture lifecycle logic is JVM-testable.
 * One instance is opened at most once; after [release] it is unusable.
 */
interface AudioSource {
    /** Opens and starts recording. */
    fun start(): AudioStartResult

    /** Blocking read of up to buffer.size samples. Returns the count, or a negative AudioRecord error code. */
    fun read(buffer: ShortArray): Int

    /** Stops recording; unblocks a pending [read]. Safe to call repeatedly. */
    fun stop()

    /** Frees the microphone. Called exactly once by [AudioCaptureController], after the read loop exited. */
    fun release()

    /**
     * Invoked with true when Android silences this client because another app took microphone
     * priority (Android 10+), and false when audio is delivered again.
     */
    var onSilencedChanged: ((Boolean) -> Unit)?
}

/** User-facing (actionable, transcript-free) messages for capture failures. */
object CaptureErrors {
    const val PERMISSION_DENIED =
        "Microphone permission was removed. Grant it in app settings, then tap Start."
    const val CONFIG_UNSUPPORTED = "This device does not support the required microphone format."
    const val INIT_FAILED =
        "Microphone could not be opened. Another app may be using it. Close that app and tap Start."
    const val START_FAILED = "Microphone could not start recording. Close other recording apps and tap Start."
    const val DEAD_OBJECT =
        "Microphone connection was lost (the audio system restarted or another app took over). Tap Start to try again."
    const val INVALID_OPERATION = "Microphone stopped unexpectedly. Tap Start to try again."
    const val BAD_VALUE = "Microphone rejected the audio settings. Tap Start to try again."
    const val GENERIC_READ = "Microphone read failed. Tap Start to try again."

    /** Maps a negative AudioRecord.read return code to a message. */
    fun forReadCode(code: Int): String = when (code) {
        AudioRecord.ERROR_DEAD_OBJECT -> DEAD_OBJECT
        AudioRecord.ERROR_INVALID_OPERATION -> INVALID_OPERATION
        AudioRecord.ERROR_BAD_VALUE -> BAD_VALUE
        else -> GENERIC_READ
    }
}

/** Production [AudioSource]: 16 kHz mono PCM16 from VOICE_RECOGNITION. */
class AndroidAudioRecordSource(context: Context) : AudioSource {
    private val appContext = context.applicationContext
    private var record: AudioRecord? = null
    private var recordingCallback: Any? = null

    @Volatile
    override var onSilencedChanged: ((Boolean) -> Unit)? = null

    override fun start(): AudioStartResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AudioStartResult.Failed(CaptureErrors.PERMISSION_DENIED, ErrorRecovery.OpenAppSettings)
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return AudioStartResult.Failed(CaptureErrors.CONFIG_UNSUPPORTED)
        val bufferBytes = maxOf(minBuffer, AudioCaptureController.FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)

        val created = try {
            createAudioRecord(bufferBytes)
        } catch (e: SecurityException) {
            return AudioStartResult.Failed(CaptureErrors.PERMISSION_DENIED, ErrorRecovery.OpenAppSettings)
        } catch (e: IllegalArgumentException) {
            return AudioStartResult.Failed(CaptureErrors.INIT_FAILED)
        } catch (e: UnsupportedOperationException) {
            return AudioStartResult.Failed(CaptureErrors.INIT_FAILED)
        }
        record = created
        if (created.state != AudioRecord.STATE_INITIALIZED) return AudioStartResult.Failed(CaptureErrors.INIT_FAILED)
        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            return AudioStartResult.Failed(CaptureErrors.START_FAILED)
        } catch (e: SecurityException) {
            return AudioStartResult.Failed(CaptureErrors.PERMISSION_DENIED, ErrorRecovery.OpenAppSettings)
        }
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return AudioStartResult.Failed(CaptureErrors.START_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerSilenceCallback(created)
        return AudioStartResult.Started
    }

    @SuppressLint("MissingPermission") // Checked at the top of start(); SecurityException is also caught.
    private fun createAudioRecord(bufferBytes: Int): AudioRecord =
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerSilenceCallback(target: AudioRecord) {
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        val sessionId = target.audioSessionId
        var lastSilenced = false
        fun evaluate(configs: List<AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
            val silenced = mine.isClientSilenced
            if (silenced != lastSilenced) {
                lastSilenced = silenced
                onSilencedChanged?.invoke(silenced)
            }
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = evaluate(configs)
        }
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        recordingCallback = callback
        evaluate(audioManager.activeRecordingConfigurations)
    }

    override fun read(buffer: ShortArray): Int {
        val r = record ?: return AudioRecord.ERROR_INVALID_OPERATION
        return r.read(buffer, 0, buffer.size)
    }

    override fun stop() {
        val r = record ?: return
        try {
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or never started; nothing to undo.
        }
    }

    override fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unregisterSilenceCallback()
        onSilencedChanged = null
        record?.release()
        record = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterSilenceCallback() {
        val callback = recordingCallback as? AudioManager.AudioRecordingCallback ?: return
        recordingCallback = null
        appContext.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(callback)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
