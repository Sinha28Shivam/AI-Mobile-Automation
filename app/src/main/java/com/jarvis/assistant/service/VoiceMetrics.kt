package com.jarvis.assistant.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Summary of a latency sample window in milliseconds. */
data class LatencyStats(val count: Int, val averageMs: Long, val medianMs: Long, val p95Ms: Long) {
    companion object {
        val Empty = LatencyStats(0, 0, 0, 0)

        /** Nearest-rank percentiles over [samples]. */
        fun of(samples: List<Long>): LatencyStats {
            if (samples.isEmpty()) return Empty
            val sorted = samples.sorted()
            fun rank(p: Double): Long = sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            return LatencyStats(sorted.size, sorted.average().toLong(), rank(0.5), rank(0.95))
        }
    }
}

/** Immutable view of [VoiceMetrics]. Contains counts and timings only, never speech content. */
data class VoiceMetricsSnapshot(
    val wakeDetections: Int = 0,
    val tapToTalkSessions: Int = 0,
    val commandSessions: Int = 0,
    val noSpeech: Int = 0,
    val lowConfidence: Int = 0,
    val handlerFailures: Int = 0,
    val speechOutputFailures: Int = 0,
    val captureErrors: Int = 0,
    val microphoneSilenced: Int = 0,
    val lastModelLoadMs: Long? = null,
    /** Capture of the frame that completed the wake word -> command capture started. */
    val wakeLatency: LatencyStats = LatencyStats.Empty,
    /** End of command utterance -> reply ready to speak (recognition flush + handler). */
    val responseLatency: LatencyStats = LatencyStats.Empty,
)

/**
 * Lightweight in-memory, on-device counters for diagnostics (Phase 3/12 measurements).
 * Nothing is persisted or sent anywhere, and no transcripts or audio are recorded.
 */
class VoiceMetrics(private val window: Int = DEFAULT_WINDOW) {
    private val lock = Any()
    private val wakeSamples = ArrayDeque<Long>()
    private val responseSamples = ArrayDeque<Long>()
    private val _snapshot = MutableStateFlow(VoiceMetricsSnapshot())
    val snapshot: StateFlow<VoiceMetricsSnapshot> = _snapshot.asStateFlow()

    fun recordWakeDetected(latencyMs: Long) = mutate {
        add(wakeSamples, latencyMs)
        it.copy(wakeDetections = it.wakeDetections + 1, wakeLatency = LatencyStats.of(wakeSamples.toList()))
    }

    fun recordTapToTalk() = mutate { it.copy(tapToTalkSessions = it.tapToTalkSessions + 1) }
    fun recordCommandSession() = mutate { it.copy(commandSessions = it.commandSessions + 1) }
    fun recordNoSpeech() = mutate { it.copy(noSpeech = it.noSpeech + 1) }
    fun recordLowConfidence() = mutate { it.copy(lowConfidence = it.lowConfidence + 1) }
    fun recordHandlerFailure() = mutate { it.copy(handlerFailures = it.handlerFailures + 1) }
    fun recordSpeechOutputFailure() = mutate { it.copy(speechOutputFailures = it.speechOutputFailures + 1) }
    fun recordCaptureError() = mutate { it.copy(captureErrors = it.captureErrors + 1) }
    fun recordMicrophoneSilenced() = mutate { it.copy(microphoneSilenced = it.microphoneSilenced + 1) }
    fun recordModelLoad(durationMs: Long) = mutate { it.copy(lastModelLoadMs = durationMs) }

    fun recordResponseLatency(latencyMs: Long) = mutate {
        add(responseSamples, latencyMs)
        it.copy(responseLatency = LatencyStats.of(responseSamples.toList()))
    }

    fun reset() = synchronized(lock) {
        wakeSamples.clear()
        responseSamples.clear()
        _snapshot.value = VoiceMetricsSnapshot()
    }

    private fun add(samples: ArrayDeque<Long>, value: Long) {
        samples.addLast(value.coerceAtLeast(0))
        while (samples.size > window) samples.removeFirst()
    }

    private inline fun mutate(block: (VoiceMetricsSnapshot) -> VoiceMetricsSnapshot) = synchronized(lock) {
        _snapshot.value = block(_snapshot.value)
    }

    companion object {
        const val DEFAULT_WINDOW = 100

        /** Process-wide instance shown on the home screen. */
        val shared = VoiceMetrics()
    }
}
