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
 * sensitivity threshold has a limited effect; the cooldown and "[unk]" filler
 * are the main false-positive defences. Everything runs on-device.
 *
 * The [model] is owned by the caller and must outlive this detector.
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
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun onFrame(frame: ShortArray, length: Int) {
        if (!running || released) return
        val now = clock()
        if (recognizer.acceptWaveForm(frame, length)) {
            val confidence = decider.evaluateFinal(VoskJson.parseWords(recognizer.result), sensitivity, now)
            if (confidence != null) fire(confidence)
        } else if (decider.evaluatePartial(VoskJson.parsePartial(recognizer.partialResult), sensitivity, now)) {
            fire(confidence = 0f)
        }
    }

    private fun fire(confidence: Float) {
        // Drop the rest of the current hypothesis so the same utterance cannot fire twice.
        recognizer.reset()
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
