package com.jarvis.assistant.service

import com.jarvis.assistant.speech.SpeechResult
import com.jarvis.assistant.speech.SpeechToTextEngine
import com.jarvis.assistant.speech.UtteranceEnd
import com.jarvis.assistant.speech.UtteranceStatus
import com.jarvis.assistant.wakeword.WakeWordDetector
import com.jarvis.assistant.wakeword.WakeWordEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoicePipelineTest {

    /** Detects the wake word when it sees a frame whose first sample is [WAKE]. */
    private class FakeWakeWord : WakeWordDetector {
        override var sensitivity = 0.5f
        override val cooldownMs = 0L
        override var onDetected: ((WakeWordEvent) -> Unit)? = null
        @Volatile var running = false
        @Volatile var framesSeen = 0
        @Volatile var released = false
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun onFrame(frame: ShortArray, length: Int) {
            if (!running) return
            framesSeen++
            if (frame[0] == WAKE) onDetected?.invoke(WakeWordEvent("hey jarvis", 1f))
        }
        override fun release() { released = true }
    }

    /** Ends the utterance after [framesPerUtterance] frames and returns [result]. */
    private class FakeStt(var result: SpeechResult, private val framesPerUtterance: Int = 2) : SpeechToTextEngine {
        private var frames = 0
        @Volatile var utterances = 0
        @Volatile var released = false
        override fun startUtterance() { frames = 0; utterances++ }
        override fun acceptFrame(frame: ShortArray, length: Int): UtteranceStatus =
            if (++frames >= framesPerUtterance) UtteranceStatus.Ended(result.endReason) else UtteranceStatus.InProgress
        override fun finish(reason: UtteranceEnd) = result
        override fun release() { released = true }
    }

    private val states = Collections.synchronizedList(mutableListOf<ServiceState>())
    private val record: (ServiceState) -> Boolean = { states += it; true }

    private fun frame(first: Short = 0) = ShortArray(4).also { it[0] = first }

    /** Feeds frames until [done] completes, as the capture thread would. */
    private fun feedUntil(pipeline: VoicePipeline, done: CompletableDeferred<*>, firstFrame: Short = WAKE) {
        pipeline.offerFrame(frame(firstFrame), 4)
        runBlocking {
            withTimeout(5_000) {
                while (!done.isCompleted) {
                    pipeline.offerFrame(frame(), 4)
                    kotlinx.coroutines.delay(5)
                }
            }
        }
    }

    @Test
    fun wakeWordThenCommandIsHandledAndSpoken() {
        val wake = FakeWakeWord()
        val spoken = CompletableDeferred<String>()
        var handled: Pair<String, Float>? = null
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = FakeStt(SpeechResult("what time is it", 0.9f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { text, conf -> handled = text to conf; HandlerResult("It is noon") },
            speaker = { spoken.complete(it) },
            setState = record,
            dispatcher = Dispatchers.Default,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()

        assertEquals("It is noon", runBlocking { spoken.await() })
        assertEquals("what time is it" to 0.9f, handled)
        assertEquals(
            listOf(ServiceState.HeardWakeWord, ServiceState.Processing, ServiceState.Speaking),
            states.take(3),
        )
    }

    @Test
    fun returnsToListeningAndResumesWakeWordAfterReply() {
        val wake = FakeWakeWord()
        val backToListening = CompletableDeferred<Unit>()
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = FakeStt(SpeechResult("hello", 1f, UtteranceEnd.EndOfSpeech)),
            commandHandler = EchoCommandHandler(),
            setState = { states += it; if (it == ServiceState.Listening) backToListening.complete(Unit); true },
        )
        pipeline.start()
        feedUntil(pipeline, backToListening)
        assertTrue(wake.running)
        pipeline.stop()
    }

    @Test
    fun lowConfidenceSkipsHandler() {
        val spoken = CompletableDeferred<String>()
        var handlerCalled = false
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("mumble", 0.2f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> handlerCalled = true; HandlerResult("x") },
            speaker = { spoken.complete(it) },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertFalse(handlerCalled)
        assertEquals(VoicePipeline.LOW_CONFIDENCE_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun noSpeechGivesPoliteReply() {
        val spoken = CompletableDeferred<String>()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("", 0f, UtteranceEnd.NoSpeech)),
            speaker = { spoken.complete(it) },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertEquals(VoicePipeline.NO_SPEECH_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun tapToTalkSkipsWakeWord() {
        val wake = FakeWakeWord()
        val handled = CompletableDeferred<String>()
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = FakeStt(SpeechResult("open youtube", 0.8f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { text, _ -> handled.complete(text); HandlerResult(null) },
            setState = record,
        )
        pipeline.start()
        pipeline.requestTapToTalk()
        feedUntil(pipeline, handled, firstFrame = 0)
        pipeline.stop()
        assertEquals("open youtube", runBlocking { handled.await() })
        assertEquals(ServiceState.HeardWakeWord, states.first())
    }

    @Test
    fun framesIgnoredByWakeWordWhileSpeaking() {
        val wake = FakeWakeWord()
        val speaking = CompletableDeferred<Unit>()
        val finishSpeaking = CompletableDeferred<Unit>()
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = FakeStt(SpeechResult("hi", 1f, UtteranceEnd.EndOfSpeech)),
            speaker = { speaking.complete(Unit); finishSpeaking.await() },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, speaking)
        val seen = wake.framesSeen
        repeat(20) { pipeline.offerFrame(frame(WAKE), 4) }
        Thread.sleep(100)
        assertEquals(seen, wake.framesSeen)
        assertFalse(wake.running)
        finishSpeaking.complete(Unit)
        pipeline.stop()
    }

    @Test
    fun stopRequestedInvokesCallbackInsteadOfResuming() {
        val stopped = CountDownLatch(1)
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("stop listening", 1f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> HandlerResult("Stopping", stopRequested = true) },
            onStopRequested = { stopped.countDown() },
            setState = record,
        )
        pipeline.start()
        val done = CompletableDeferred<Unit>()
        Thread { stopped.await(5, TimeUnit.SECONDS); done.complete(Unit) }.start()
        feedUntil(pipeline, done)
        pipeline.stop()
        assertEquals(0L, stopped.count)
        assertFalse(states.contains(ServiceState.Listening))
    }

    @Test
    fun handlerExceptionProducesErrorReplyAndRecovers() {
        val spoken = CompletableDeferred<String>()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("crash", 1f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> throw IllegalStateException("boom") },
            speaker = { spoken.complete(it) },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertEquals(VoicePipeline.ERROR_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun stopReleasesEngines() {
        val wake = FakeWakeWord()
        val stt = FakeStt(SpeechResult("", 0f, UtteranceEnd.NoSpeech))
        val released = CountDownLatch(1)
        val pipeline = VoicePipeline(wake, stt, setState = record, onReleased = { released.countDown() })
        pipeline.start()
        pipeline.stop()
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertTrue(wake.released)
        assertTrue(stt.released)
    }

    @Test
    fun stopBeforeStartStillReleases() {
        val wake = FakeWakeWord()
        val stt = FakeStt(SpeechResult("", 0f, UtteranceEnd.NoSpeech))
        VoicePipeline(wake, stt, setState = record).stop()
        assertTrue(wake.released)
        assertTrue(stt.released)
    }

    private fun awaitTrue(block: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!block()) kotlinx.coroutines.delay(2) }
    }

    @Test
    fun wakePhraseIsStrippedFromCommand() {
        val handled = CompletableDeferred<String>()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("hey jarvis open youtube", 0.9f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { text, _ -> handled.complete(text); HandlerResult(null) },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, handled)
        pipeline.stop()
        assertEquals("open youtube", runBlocking { handled.await() })
    }

    @Test
    fun wakePhraseOnlyNeverReachesHandler() {
        val spoken = CompletableDeferred<String>()
        var called = false
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("jarvis", 1f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> called = true; HandlerResult("x") },
            speaker = { spoken.complete(it) },
            setState = record,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertFalse(called)
        assertEquals(VoicePipeline.NO_COMMAND_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun lowConfidenceGateUsesConfiguredThresholdAndNeverCallsHandler() {
        val spoken = CompletableDeferred<String>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val metrics = VoiceMetrics()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("delete everything", 0.59f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> calls.incrementAndGet(); HandlerResult("done") },
            speaker = { spoken.complete(it) },
            setState = record,
            minCommandConfidence = 0.6f,
            metrics = metrics,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertEquals(0, calls.get())
        assertEquals(VoicePipeline.LOW_CONFIDENCE_REPLY, runBlocking { spoken.await() })
        assertEquals(1, metrics.snapshot.value.lowConfidence)
    }

    @Test
    fun ttsFailureReturnsToListening() {
        val listening = CompletableDeferred<Unit>()
        val metrics = VoiceMetrics()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("hello", 1f, UtteranceEnd.EndOfSpeech)),
            speaker = { throw IllegalStateException("tts dead") },
            setState = { states += it; if (it == ServiceState.Listening) listening.complete(Unit); true },
            metrics = metrics,
        )
        pipeline.start()
        feedUntil(pipeline, listening)
        pipeline.stop()
        assertTrue(states.contains(ServiceState.Speaking))
        assertEquals(1, metrics.snapshot.value.speechOutputFailures)
    }

    @Test
    fun ttsHangTimesOutAndReturnsToListening() {
        val listening = CompletableDeferred<Unit>()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("hello", 1f, UtteranceEnd.EndOfSpeech)),
            speaker = { CompletableDeferred<Unit>().await() },
            setState = { states += it; if (it == ServiceState.Listening) listening.complete(Unit); true },
            speakTimeoutMs = 50,
        )
        pipeline.start()
        feedUntil(pipeline, listening)
        pipeline.stop()
    }

    @Test
    fun handlerTimeoutGivesErrorReply() {
        val spoken = CompletableDeferred<String>()
        val pipeline = VoicePipeline(
            wakeWord = FakeWakeWord(),
            speechToText = FakeStt(SpeechResult("slow", 1f, UtteranceEnd.EndOfSpeech)),
            commandHandler = { _, _ -> CompletableDeferred<Unit>().await(); HandlerResult("never") },
            speaker = { spoken.complete(it) },
            setState = record,
            handlerTimeoutMs = 50,
        )
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertEquals(VoicePipeline.ERROR_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun recognizerFinishFailureGivesRetryPromptNotStuck() {
        val spoken = CompletableDeferred<String>()
        val stt = object : SpeechToTextEngine {
            private var n = 0
            override fun startUtterance() { n = 0 }
            override fun acceptFrame(frame: ShortArray, length: Int): UtteranceStatus =
                if (++n >= 2) UtteranceStatus.Ended(UtteranceEnd.EndOfSpeech) else UtteranceStatus.InProgress
            override fun finish(reason: UtteranceEnd): SpeechResult = throw IllegalStateException("bad json")
            override fun release() {}
        }
        val pipeline = VoicePipeline(FakeWakeWord(), stt, speaker = { spoken.complete(it) }, setState = record)
        pipeline.start()
        feedUntil(pipeline, spoken)
        pipeline.stop()
        assertEquals(VoicePipeline.LOW_CONFIDENCE_REPLY, runBlocking { spoken.await() })
    }

    @Test
    fun duplicateWakeTriggerInOneUtteranceStartsOneSession() {
        val wake = FakeWakeWord()
        val stt = FakeStt(SpeechResult("hi", 1f, UtteranceEnd.EndOfSpeech), framesPerUtterance = 1_000_000)
        val pipeline = VoicePipeline(wake, stt, setState = record)
        pipeline.start()
        // Many wake frames in a row (same utterance): only the first one starts a session.
        repeat(20) { pipeline.offerFrame(frame(WAKE), 4) }
        awaitTrue { stt.utterances >= 1 }
        Thread.sleep(50)
        assertEquals(1, stt.utterances)
        assertEquals(1, states.count { it == ServiceState.HeardWakeWord })
        pipeline.stop()
    }

    @Test
    fun cooldownRestartsWhenSessionEnds() {
        val ended = java.util.concurrent.atomic.AtomicInteger()
        val wake = object : WakeWordDetector by FakeWakeWord() {
            override fun onSessionEnded() { ended.incrementAndGet() }
        }
        val listening = CompletableDeferred<Unit>()
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = FakeStt(SpeechResult("hello", 1f, UtteranceEnd.EndOfSpeech)),
            setState = { if (it == ServiceState.Listening) listening.complete(Unit); true },
        )
        pipeline.start()
        feedUntil(pipeline, listening)
        pipeline.stop()
        assertEquals(1, ended.get())
    }

    @Test
    fun rejectedStateWriteStopsPipelineInsteadOfContinuing() {
        val wake = FakeWakeWord()
        val stt = FakeStt(SpeechResult("hello", 1f, UtteranceEnd.EndOfSpeech))
        var called = false
        val pipeline = VoicePipeline(
            wakeWord = wake,
            speechToText = stt,
            commandHandler = { _, _ -> called = true; HandlerResult("x") },
            setState = { false }, // session is stale
        )
        pipeline.start()
        pipeline.offerFrame(frame(WAKE), 4)
        awaitTrue { pipeline.isStopped && wake.released && stt.released }
        assertFalse(called)
    }

    @Test
    fun stopAndJoinWaitsForRelease() = runBlocking {
        val wake = FakeWakeWord()
        val stt = FakeStt(SpeechResult("", 0f, UtteranceEnd.NoSpeech))
        var releasedCallbacks = 0
        val pipeline = VoicePipeline(wake, stt, setState = record, onReleased = { releasedCallbacks++ })
        assertTrue(pipeline.start())
        withTimeout(5_000) { pipeline.stopAndJoin() }
        assertTrue(wake.released && stt.released)
        pipeline.stop()
        withTimeout(5_000) { pipeline.stopAndJoin() }
        assertEquals(1, releasedCallbacks)
        assertFalse("a stopped pipeline cannot be restarted", pipeline.start())
    }

    @Test
    fun framesAfterStopAreDropped() {
        val wake = FakeWakeWord()
        val pipeline = VoicePipeline(wake, FakeStt(SpeechResult("", 0f, UtteranceEnd.NoSpeech)), setState = record)
        pipeline.start()
        runBlocking { withTimeout(5_000) { pipeline.stopAndJoin() } }
        val seen = wake.framesSeen
        repeat(10) { pipeline.offerFrame(frame(WAKE), 4) }
        Thread.sleep(30)
        assertEquals(seen, wake.framesSeen)
    }

    private companion object {
        const val WAKE: Short = 42
    }
}
