package com.jarvis.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
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
 * Sole owner of [AudioRecord] while JARVIS is listening.
 *
 * Captures 16 kHz mono PCM16 on a background dispatcher and delivers frames
 * to registered listeners via [addFrameListener]. Frames are only held in
 * memory and the buffer is reused; nothing is ever persisted. Listeners are
 * invoked on the capture thread and must return quickly (copy the data if it
 * must outlive the callback). Later pipeline stages (wake word, STT) plug in
 * by registering a listener.
 */
class AudioCaptureController(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onError: (String) -> Unit = {},
) {
    private val listeners = CopyOnWriteArrayList<AudioFrameListener>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null

    val isCapturing: Boolean
        @Synchronized get() = captureJob?.isActive == true

    fun addFrameListener(listener: AudioFrameListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeFrameListener(listener: AudioFrameListener) {
        listeners.remove(listener)
    }

    /** Starts capture. Returns false (and reports via onError) if the mic cannot be opened. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized
    fun start(): Boolean {
        if (captureJob?.isActive == true) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            onError("Microphone configuration not supported")
            return false
        }
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)

        val record = try {
            createAudioRecord(bufferBytes)
        } catch (e: SecurityException) {
            onError("Microphone permission denied")
            return false
        } catch (e: IllegalArgumentException) {
            onError("Microphone unavailable")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("Microphone could not be initialized (in use by another app?)")
            return false
        }

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            onError("Microphone could not start recording")
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            onError("Microphone is in use by another app")
            return false
        }

        audioRecord = record
        val job = scope.launch { readLoop(record) }
        // Release only once the read loop has exited so release() never races an in-flight read().
        job.invokeOnCompletion { record.release() }
        captureJob = job
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord =
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes)

    private fun CoroutineScope.readLoop(record: AudioRecord) {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.w(TAG, "AudioRecord read error code=$read")
                onError("Microphone read failed")
                break
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

    /** Stops capture and releases the microphone. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        val job = captureJob
        captureJob = null
        job?.cancel()
        audioRecord?.let { record ->
            try {
                // Stopping unblocks a pending read(); the job completion handler then releases.
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed")
            }
            if (job == null) record.release()
        }
        audioRecord = null
    }

    /** Stops capture and tears down the coroutine scope; the instance is unusable afterwards. */
    fun release() {
        stop()
        listeners.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
