package com.jarvis.assistant.wakeword

import com.jarvis.assistant.speech.RecognizedWord

/**
 * Pure decision logic for keyword spotting on top of an ASR result:
 * phrase matching, sensitivity-to-confidence threshold and cooldown debounce.
 */
class WakeWordDecider(
    keyword: String = DEFAULT_KEYWORD,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val keyword = keyword.trim().lowercase()
    private var lastDetectionAtMs: Long? = null

    init {
        require(this.keyword.isNotEmpty()) { "keyword must not be blank" }
    }

    /**
     * Evaluates a final recognizer segment. Returns the keyword confidence when it
     * should trigger, or null. Only the keyword words' confidences are considered.
     */
    fun evaluateFinal(words: List<RecognizedWord>, sensitivity: Float, nowMs: Long): Float? {
        val match = words.filter { it.word.lowercase() == keyword }
        if (match.isEmpty()) return null
        val confidence = match.maxOf { it.confidence }
        if (confidence < requiredConfidence(sensitivity)) return null
        return if (tryConsumeCooldown(nowMs)) confidence else null
    }

    /**
     * Partial hypotheses carry no confidence, so they may only trigger early when
     * the user selected a high sensitivity; otherwise we wait for the final segment.
     */
    fun evaluatePartial(partial: String, sensitivity: Float, nowMs: Long): Boolean {
        if (sensitivity < PARTIAL_TRIGGER_MIN_SENSITIVITY) return false
        if (partial.lowercase().split(' ').none { it == keyword }) return false
        return tryConsumeCooldown(nowMs)
    }

    fun isCoolingDown(nowMs: Long): Boolean {
        val last = lastDetectionAtMs ?: return false
        return nowMs - last < cooldownMs
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
        const val DEFAULT_COOLDOWN_MS = 2_000L
        const val PARTIAL_TRIGGER_MIN_SENSITIVITY = 0.8f
        private const val STRICT_CONFIDENCE = 0.95f
        private const val LENIENT_CONFIDENCE = 0.35f

        /** Linear map: sensitivity 0 -> 0.95 required word confidence, 1 -> 0.35. */
        fun requiredConfidence(sensitivity: Float): Float {
            val s = sensitivity.coerceIn(0f, 1f)
            return STRICT_CONFIDENCE - (STRICT_CONFIDENCE - LENIENT_CONFIDENCE) * s
        }
    }
}
