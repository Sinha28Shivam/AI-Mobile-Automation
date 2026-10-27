package com.jarvis.assistant.wakeword

/** Emitted when the wake phrase is recognized. [confidence] is in 0..1 (engine specific). */
data class WakeWordEvent(val phrase: String, val confidence: Float)

/**
 * Provider-neutral, fully local wake-word detector.
 *
 * Audio is pushed in through [onFrame] by the single capture owner; a detector
 * never opens the microphone itself. While stopped, frames are ignored. All
 * calls for one instance are expected from a single thread (the pipeline
 * coroutine); [sensitivity] may be changed from any thread.
 *
 * Current implementation: [VoskKeywordWakeWordDetector]. A dedicated wake-word
 * engine such as Picovoice Porcupine (custom "Hey Jarvis" keyword file trained
 * in the Picovoice console, requires an AccessKey and has its own license) could
 * implement this interface later for lower CPU use and fewer false positives.
 */
interface WakeWordDetector {
    /** 0 = strict (fewer false triggers), 1 = lenient (catches quieter/less clear speech). */
    var sensitivity: Float

    /** Minimum time between two detections. */
    val cooldownMs: Long

    /** Invoked on the thread calling [onFrame]. */
    var onDetected: ((WakeWordEvent) -> Unit)?

    /** Starts (or resumes) detection with fresh internal state. */
    fun start()

    /** Pauses detection; subsequent frames are ignored until [start]. */
    fun stop()

    /** Feeds [length] PCM16 16 kHz mono samples of [frame]. The array is not retained. */
    fun onFrame(frame: ShortArray, length: Int)

    /** Frees engine resources; the detector is unusable afterwards. */
    fun release()
}
