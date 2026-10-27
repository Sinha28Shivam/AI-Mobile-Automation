package com.jarvis.assistant.speech

/**
 * Pure state machine bounding one command utterance using audio time
 * (derived from sample counts, not the wall clock).
 *
 * - Ends with [UtteranceEnd.NoSpeech] if nothing is recognized within noSpeechTimeoutMs.
 * - Ends with [UtteranceEnd.EndOfSpeech] after trailingSilenceMs without new recognized words.
 * - Ends with [UtteranceEnd.MaxDuration] once maxUtteranceMs elapsed.
 *
 * "Activity" is a change in the recognizer's partial hypothesis (or a final
 * segment with words), a proxy for ongoing speech that needs no separate VAD.
 */
class CommandEndpointer(
    private val maxUtteranceMs: Long = DEFAULT_MAX_UTTERANCE_MS,
    private val trailingSilenceMs: Long = DEFAULT_TRAILING_SILENCE_MS,
    private val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS,
) {
    private var elapsedMs = 0L
    private var lastActivityMs = -1L
    private var lastPartial = ""
    private var ended: UtteranceEnd? = null

    val speechStarted: Boolean get() = lastActivityMs >= 0

    fun reset() {
        elapsedMs = 0L
        lastActivityMs = -1L
        lastPartial = ""
        ended = null
    }

    /**
     * Advances by one frame of [frameMs] audio. [partial] is the current partial
     * hypothesis (null when the recognizer emitted a final segment instead);
     * [finalHadText] is true when a final segment containing words was produced.
     */
    fun onFrame(frameMs: Long, partial: String?, finalHadText: Boolean = false): UtteranceStatus {
        ended?.let { return UtteranceStatus.Ended(it) }
        elapsedMs += frameMs

        val trimmed = partial?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != lastPartial) {
            lastActivityMs = elapsedMs
            lastPartial = trimmed
        }
        if (finalHadText) {
            // A final segment means the recognizer already saw silence after the words, so it
            // marks speech as started but does not restart the trailing-silence timer.
            if (lastActivityMs < 0) lastActivityMs = elapsedMs
            lastPartial = ""
        }

        val decision = when {
            elapsedMs >= maxUtteranceMs -> UtteranceEnd.MaxDuration
            !speechStarted && elapsedMs >= noSpeechTimeoutMs -> UtteranceEnd.NoSpeech
            speechStarted && elapsedMs - lastActivityMs >= trailingSilenceMs -> UtteranceEnd.EndOfSpeech
            else -> null
        }
        ended = decision
        return if (decision == null) UtteranceStatus.InProgress else UtteranceStatus.Ended(decision)
    }

    companion object {
        const val DEFAULT_MAX_UTTERANCE_MS = 6_000L
        const val DEFAULT_TRAILING_SILENCE_MS = 1_200L
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 4_000L

        fun frameDurationMs(samples: Int, sampleRate: Int): Long = samples * 1_000L / sampleRate
    }
}
