package com.jarvis.assistant.wakeword

import com.jarvis.assistant.speech.RecognizedWord

/**
 * Pure decision logic for keyword spotting on top of an ASR result:
 * phrase matching, sensitivity gates and cooldown debounce.
 *
 * Grammar-mode Vosk word confidences are usually ~1.0, so the confidence
 * threshold alone barely changes behaviour. Sensitivity therefore controls
 * three independent gates:
 *
 * | Sensitivity          | Accepted phrase           | Partial (early) trigger | Loudness floor (peak RMS) |
 * |----------------------|---------------------------|-------------------------|---------------------------|
 * | < 0.35 (strict)      | "hey jarvis" only         | no                      | high                      |
 * | 0.35 ..< 0.8         | "hey jarvis" or "jarvis"  | no                      | medium                    |
 * | >= 0.8 (lenient)     | "hey jarvis" or "jarvis"  | yes                     | low / none at 1.0         |
 *
 * plus the word-confidence threshold from [requiredConfidence].
 */
class WakeWordDecider(
    keyword: String = DEFAULT_KEYWORD,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    prefix: String = DEFAULT_PREFIX,
) {
    private val keyword = keyword.trim().lowercase()
    private val prefix = prefix.trim().lowercase()
    private var lastDetectionAtMs: Long? = null

    init {
        require(this.keyword.isNotEmpty()) { "keyword must not be blank" }
    }

    /**
     * Evaluates a final recognizer segment. Returns the keyword confidence when it
     * should trigger, or null. Only the keyword words' confidences are considered.
     * [peakRms] is the loudest frame RMS (PCM16 units) of the segment.
     */
    fun evaluateFinal(
        words: List<RecognizedWord>,
        sensitivity: Float,
        nowMs: Long,
        peakRms: Double = Double.MAX_VALUE,
    ): Float? {
        val lowered = words.map { it.word.lowercase() }
        val indices = lowered.indices.filter { lowered[it] == keyword }
        if (indices.isEmpty()) return null
        val accepted = if (requiresFullPhrase(sensitivity)) {
            indices.filter { it > 0 && lowered[it - 1] == prefix }
        } else {
            indices
        }
        if (accepted.isEmpty()) return null
        if (peakRms < minimumRms(sensitivity)) return null
        val confidence = accepted.maxOf { words[it].confidence }
        if (confidence < requiredConfidence(sensitivity)) return null
        return if (tryConsumeCooldown(nowMs)) confidence else null
    }

    /**
     * Partial hypotheses carry no confidence, so they may only trigger early when
     * the user selected a high sensitivity; otherwise we wait for the final segment.
     */
    fun evaluatePartial(partial: String, sensitivity: Float, nowMs: Long, peakRms: Double = Double.MAX_VALUE): Boolean {
        if (sensitivity < PARTIAL_TRIGGER_MIN_SENSITIVITY) return false
        if (partial.lowercase().split(' ').none { it == keyword }) return false
        if (peakRms < minimumRms(sensitivity)) return false
        return tryConsumeCooldown(nowMs)
    }

    fun isCoolingDown(nowMs: Long): Boolean {
        val last = lastDetectionAtMs ?: return false
        return nowMs - last < cooldownMs
    }

    /** Restarts the cooldown window from [nowMs], e.g. when a command session ends. */
    fun restartCooldown(nowMs: Long) {
        lastDetectionAtMs = nowMs
    }

    fun resetCooldown() {
        lastDetectionAtMs = null
    }

    private fun tryConsumeCooldown(nowMs: Long): Boolean {
        if (isCoolingDown(nowMs)) return false
        lastDetectionAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_KEYWORD = "jarvis"
        const val DEFAULT_PREFIX = "hey"
        const val DEFAULT_COOLDOWN_MS = 2_000L
        const val PARTIAL_TRIGGER_MIN_SENSITIVITY = 0.8f
        const val FULL_PHRASE_MAX_SENSITIVITY = 0.35f
        private const val STRICT_CONFIDENCE = 0.95f
        private const val LENIENT_CONFIDENCE = 0.35f

        /** Peak frame RMS (PCM16) required at sensitivity 0; scales linearly down to 0 at sensitivity 1. */
        const val STRICT_MIN_RMS = 300.0

        /** Linear map: sensitivity 0 -> 0.95 required word confidence, 1 -> 0.35. */
        fun requiredConfidence(sensitivity: Float): Float {
            val s = sensitivity.coerceIn(0f, 1f)
            return STRICT_CONFIDENCE - (STRICT_CONFIDENCE - LENIENT_CONFIDENCE) * s
        }

        fun requiresFullPhrase(sensitivity: Float): Boolean = sensitivity.coerceIn(0f, 1f) < FULL_PHRASE_MAX_SENSITIVITY

        fun minimumRms(sensitivity: Float): Double = STRICT_MIN_RMS * (1.0 - sensitivity.coerceIn(0f, 1f))

        /** Root-mean-square of the first [length] PCM16 samples. */
        fun rms(frame: ShortArray, length: Int): Double {
            val n = length.coerceAtMost(frame.size)
            if (n <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = frame[i].toDouble()
                sum += s * s
            }
            return Math.sqrt(sum / n)
        }
    }
}
