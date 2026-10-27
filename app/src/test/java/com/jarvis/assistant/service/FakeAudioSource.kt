package com.jarvis.assistant.service

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scriptable [AudioSource]. By default every read returns a short silent frame (after a tiny
 * pause so the loop does not spin). Queue negative codes via [failNextRead] to simulate errors.
 */
class FakeAudioSource(
    private val startResult: AudioStartResult = AudioStartResult.Started,
    private val throwOnStart: RuntimeException? = null,
) : AudioSource {
    val starts = AtomicInteger(0)
    val stops = AtomicInteger(0)
    val releases = AtomicInteger(0)
    val reads = AtomicInteger(0)
    private val scripted = LinkedBlockingQueue<Int>()

    @Volatile var readThrows: RuntimeException? = null

    @Volatile var stopped = false

    @Volatile var firstSample: Short = 0

    /** Monotonic event order shared across fakes, to assert release-before-reopen. */
    @Volatile var startedAt = -1L

    @Volatile var releasedAt = -1L

    override var onSilencedChanged: ((Boolean) -> Unit)? = null

    fun failNextRead(code: Int) {
        scripted.put(code)
    }

    override fun start(): AudioStartResult {
        starts.incrementAndGet()
        startedAt = SEQ.incrementAndGet()
        throwOnStart?.let { throw it }
        return startResult
    }

    override fun read(buffer: ShortArray): Int {
        reads.incrementAndGet()
        readThrows?.let { throw it }
        val code = scripted.poll(2, TimeUnit.MILLISECONDS)
        if (code != null) return code
        if (stopped) return AUDIO_ERROR_INVALID_OPERATION
        buffer.fill(0)
        buffer[0] = firstSample
        return 16
    }

    override fun stop() {
        stops.incrementAndGet()
        stopped = true
    }

    override fun release() {
        releases.incrementAndGet()
        releasedAt = SEQ.incrementAndGet()
    }

    companion object {
        val SEQ = java.util.concurrent.atomic.AtomicLong(0)

        // android.media.AudioRecord constants (inlined so the fake does not depend on android.jar values at runtime).
        const val AUDIO_ERROR = -1
        const val AUDIO_ERROR_BAD_VALUE = -2
        const val AUDIO_ERROR_INVALID_OPERATION = -3
        const val AUDIO_ERROR_DEAD_OBJECT = -6
    }
}
