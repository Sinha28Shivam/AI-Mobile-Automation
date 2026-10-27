package com.jarvis.assistant.wakeword

import android.os.SystemClock
import com.jarvis.assistant.speech.VoskJson
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Local keyword spotter for "Hey Jarvis" built on a grammar-constrained Vosk recognizer.
 *
 * Honest limitations: this is a small ASR decoder restricted to the phrases
 * ["hey jarvis", "jarvis", "[unk]"], not a dedicated wake-word neural network.
 * Every utterance is decoded continuously, so CPU and battery use are higher than
 * a purpose-built engine, and words that sound like "jarvis" can trigger it.
 * Vosk's word confidences are often close to 1.0 in grammar mode, so the
 * confidence threshold alone has a limited effect; sensitivity therefore also
 * decides whether "jarvis" alone is accepted and sets a loudness floor (see
 * [WakeWordDecider]). Cooldown and the "[unk]" filler are further
 * false-positive defences. Everything runs on-device.
 *
 * After a detection the recognizer is reset and the detector stops itself, so
 * the rest of the same utterance can never produce a second detection; the
 * cooldown restarts when the command session ends ([onSessionEnded]).
 *
 * The [model] is owned by the caller and must outlive this detector. Not
 * thread-safe: all calls except [sensitivity] must come from one coroutine.
 */
class VoskKeywordWakeWordDetector(
    model: Model,
    sampleRate: Int,
    keyword: String = WakeWordDecider.DEFAULT_KEYWORD,
    phrases: List<String> = listOf("hey $keyword", keyword),
    cooldownMs: Long = WakeWordDecider.DEFAULT_COOLDOWN_MS,
    initialSensitivity: Float = 0.5f,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : WakeWordDetector {

    private val decider = WakeWordDecider(keyword, cooldownMs)
    private val recognizer = Recognizer(model, sampleRate.toFloat(), VoskJson.grammar(phrases)).apply {
        setWords(true)
    }
    private var running = false
    private var released = false
    private var peakRms = 0.0

    @Volatile
    override var sensitivity: Float = initialSensitivity.coerceIn(0f, 1f)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    override val cooldownMs: Long get() = decider.cooldownMs

    override var onDetected: ((WakeWordEvent) -> Unit)? = null

    private val displayPhrase = phrases.firstOrNull() ?: keyword

    override fun start() {
        check(!released) { "Detector released" }
        recognizer.reset()
        peakRms = 0.0
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun onSessionEnded() {
        if (!released) decider.restartCooldown(clock())
    }

    override fun onFrame(frame: ShortArray, length: Int) {
        if (!running || released) return
        val now = clock()
        peakRms = maxOf(peakRms, WakeWordDecider.rms(frame, length))
        val sensitivity = sensitivity
        if (recognizer.acceptWaveForm(frame, length)) {
            val confidence = decider.evaluateFinal(VoskJson.parseWords(recognizer.result), sensitivity, now, peakRms)
            // A final segment closes the hypothesis; loudness is tracked per segment.
            peakRms = 0.0
            if (confidence != null) fire(confidence)
        } else if (decider.evaluatePartial(VoskJson.parsePartial(recognizer.partialResult), sensitivity, now, peakRms)) {
            fire(confidence = 0f)
        }
    }

    private fun fire(confidence: Float) {
        // Drop the rest of the current hypothesis and ignore further frames until start(), so the
        // same utterance cannot fire twice.
        recognizer.reset()
        peakRms = 0.0
        running = false
        onDetected?.invoke(WakeWordEvent(displayPhrase, confidence))
    }

    override fun release() {
        if (released) return
        released = true
        running = false
        onDetected = null
        recognizer.close()
    }
}
