package com.jarvis.assistant.service

import com.jarvis.assistant.speech.SpeechResult
import com.jarvis.assistant.speech.SpeechToTextEngine
import com.jarvis.assistant.speech.UtteranceEnd
import com.jarvis.assistant.speech.UtteranceStatus
import com.jarvis.assistant.wakeword.WakeWordDetector
import com.jarvis.assistant.wakeword.WakeWordEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ListeningSessionControllerTest {

    /** Tracks native-like resources: every engine must be released exactly once, never used after. */
    private class Resources {
        val created = AtomicInteger(0)
        val released = AtomicInteger(0)
        val usedAfterRelease = AtomicInteger(0)
        val modelsOpen = AtomicInteger(0)
        val modelsClosed = AtomicInteger(0)
    }

    private class FakeWake(private val res: Resources) : WakeWordDetector {
        override var sensitivity = 0.5f
        override val cooldownMs = 0L
        override var onDetected: ((WakeWordEvent) -> Unit)? = null
        @Volatile var released = false
        @Volatile var running = false
        init { res.created.incrementAndGet() }
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun onFrame(frame: ShortArray, length: Int) {
            if (released) res.usedAfterRelease.incrementAndGet()
            if (running && frame[0] == WAKE) onDetected?.invoke(WakeWordEvent("hey jarvis", 1f))
        }
        override fun release() {
            if (released) return
            released = true
            res.released.incrementAndGet()
        }
    }

    private class FakeStt(private val res: Resources, private val result: SpeechResult) : SpeechToTextEngine {
        @Volatile var released = false
        private var frames = 0
        init { res.created.incrementAndGet() }
        override fun startUtterance() { frames = 0 }
        override fun acceptFrame(frame: ShortArray, length: Int): UtteranceStatus {
            if (released) res.usedAfterRelease.incrementAndGet()
            return if (++frames >= 3) UtteranceStatus.Ended(result.endReason) else UtteranceStatus.InProgress
        }
        override fun finish(reason: UtteranceEnd) = result
        override fun release() {
            if (released) return
            released = true
            res.released.incrementAndGet()
        }
    }

    private val res = Resources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = InMemoryServiceStateStore()
    private val states = Collections.synchronizedList(mutableListOf<ServiceState>())
    private val sources = Collections.synchronizedList(mutableListOf<FakeAudioSource>())
    private val idleCount = AtomicInteger(0)

    @Volatile private var permission = true
    @Volatile private var loadGate: CompletableDeferred<Unit>? = null
    @Volatile private var nextResult = SpeechResult("open youtube", 0.9f, UtteranceEnd.EndOfSpeech)
    @Volatile private var handler: CommandHandler = CommandHandler { _, _ -> HandlerResult("ok") }
    @Volatile private var speaker: SpeechSpeaker = SpeechSpeaker.NoOp
    @Volatile private var loadFailure: LoadResult.Failed? = null
    @Volatile private var sourceFactory: () -> FakeAudioSource = { FakeAudioSource() }
    private val handlerCalls = AtomicInteger(0)

    private val loader = PipelineLoader { callbacks ->
        loadGate?.await()
        loadFailure?.let { return@PipelineLoader it }
        res.modelsOpen.incrementAndGet()
        val pipeline = VoicePipeline(
            wakeWord = FakeWake(res),
            speechToText = FakeStt(res, nextResult),
            commandHandler = { t, c -> handlerCalls.incrementAndGet(); handler.handle(t, c) },
            speaker = { speaker.speak(it) },
            onStopRequested = callbacks.onStopRequested,
            setState = { s -> callbacks.setState(s).also { ok -> if (ok) states += s } },
            handlerTimeoutMs = 2_000,
            speakTimeoutMs = 500,
        )
        LoadResult.Ready(pipeline) { res.modelsClosed.incrementAndGet() }
    }

    private val controller = ListeningSessionController(
        scope = scope,
        backgroundDispatcher = Dispatchers.IO,
        store = store,
        hasMicrophonePermission = { permission },
        loader = loader,
        captureFactory = { onError, onSilenced ->
            val source = sourceFactory().also { sources += it }
            AudioCaptureController(source, onError = onError, onSilencedChanged = onSilenced)
        },
        onIdle = { idleCount.incrementAndGet() },
    )

    @After
    fun tearDown() {
        runBlocking { withTimeout(5_000) { controller.stopAndJoin() } }
        scope.cancel()
        assertEquals("engine used after release", 0, res.usedAfterRelease.get())
        assertEquals("every engine released once", res.created.get(), res.released.get())
        assertEquals("every model closed once", res.modelsOpen.get(), res.modelsClosed.get())
        sources.forEach { assertTrue("mic released at most once", it.releases.get() <= 1) }
    }

    private fun awaitState(expected: ServiceState) = runBlocking {
        withTimeout(5_000) { while (store.state.value != expected) delay(2) }
    }

    private fun awaitCondition(block: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!block()) delay(2) }
    }

    private fun stopAndJoin() = runBlocking { withTimeout(5_000) { controller.stopAndJoin() } }

    @Test
    fun startReachesListeningOnlyAfterMicOpens() {
        assertEquals(ListeningSessionController.StartOutcome.Started, controller.start())
        assertEquals(ServiceState.Starting, store.state.value)
        awaitState(ServiceState.Listening)
        assertEquals(1, sources.single().starts.get())
    }

    @Test
    fun stoppedOnlyAfterEverythingReleased() {
        controller.start()
        awaitState(ServiceState.Listening)
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
        assertEquals(1, sources.single().releases.get())
        assertEquals(res.created.get(), res.released.get())
        assertEquals(1, res.modelsClosed.get())
        awaitCondition { idleCount.get() >= 1 }
    }

    @Test
    fun repeatedStartIsIdempotent() {
        assertEquals(ListeningSessionController.StartOutcome.Started, controller.start())
        repeat(5) { assertEquals(ListeningSessionController.StartOutcome.AlreadyRunning, controller.start()) }
        awaitState(ServiceState.Listening)
        repeat(5) { controller.start() }
        Thread.sleep(50)
        assertEquals(1, sources.size)
        assertEquals(1, res.modelsOpen.get())
    }

    @Test
    fun rapidStartStopCyclesLeakNothing() {
        repeat(25) { i ->
            controller.start()
            if (i % 3 == 0) awaitState(ServiceState.Listening)
            controller.stop()
        }
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
        // Never two microphones open at once: each source opened after the previous was released.
        val ordered = sources.toList()
        for (i in 1 until ordered.size) {
            assertTrue("mic $i opened before ${i - 1} released", ordered[i].startedAt > ordered[i - 1].releasedAt)
        }
    }

    @Test
    fun startImmediatelyAfterStopGetsFreshSession() {
        controller.start()
        awaitState(ServiceState.Listening)
        val first = controller.activeGeneration
        controller.stop()
        assertEquals(ListeningSessionController.StartOutcome.Started, controller.start())
        assertNotEquals(first, controller.activeGeneration)
        awaitState(ServiceState.Listening)
        assertEquals(2, sources.size)
        assertTrue(sources[1].startedAt > sources[0].releasedAt)
        assertEquals(2, res.modelsOpen.get())
    }

    @Test
    fun stopDuringModelLoadReleasesLoadedModel() {
        val gate = CompletableDeferred<Unit>()
        loadGate = gate
        controller.start()
        controller.stop()
        assertEquals(ServiceState.Stopping, store.state.value)
        gate.complete(Unit)
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
        assertTrue("mic never opened", sources.isEmpty())
        assertEquals(1, res.modelsClosed.get())
    }

    @Test
    fun stopDuringWakeWordListening() {
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().firstSample = 0
        Thread.sleep(30)
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
    }

    @Test
    fun stopDuringCommandRecognition() {
        nextResult = SpeechResult("x", 0.9f, UtteranceEnd.EndOfSpeech)
        val gate = CompletableDeferred<Unit>()
        handler = CommandHandler { _, _ -> gate.await(); HandlerResult("late") }
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().firstSample = WAKE
        awaitCondition { store.state.value == ServiceState.HeardWakeWord || store.state.value == ServiceState.Processing }
        stopAndJoin()
        gate.complete(Unit)
        assertEquals(ServiceState.Stopped, store.state.value)
    }

    @Test
    fun stopWhileHandlerRunsIgnoresLateResult() {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        handler = CommandHandler { _, _ -> entered.complete(Unit); never.await(); HandlerResult("late") }
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().firstSample = WAKE
        runBlocking { withTimeout(5_000) { entered.await() } }
        assertEquals(ServiceState.Processing, store.state.value)
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
        Thread.sleep(30)
        assertEquals(ServiceState.Stopped, store.state.value)
    }

    @Test
    fun stopWhileSpeaking() {
        val speaking = CompletableDeferred<Unit>()
        speaker = SpeechSpeaker { speaking.complete(Unit); CompletableDeferred<Unit>().await() }
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().firstSample = WAKE
        runBlocking { withTimeout(5_000) { speaking.await() } }
        assertEquals(ServiceState.Speaking, store.state.value)
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
    }

    @Test
    fun captureErrorStopsPipelineThenRestartWorks() {
        sourceFactory = { FakeAudioSource().apply { failNextRead(FakeAudioSource.AUDIO_ERROR_DEAD_OBJECT) } }
        controller.start()
        awaitCondition { store.state.value is ServiceState.Error }
        assertEquals(ServiceState.Error(CaptureErrors.DEAD_OBJECT, ErrorRecovery.Retry), store.state.value)
        awaitCondition { res.released.get() == res.created.get() && res.modelsClosed.get() == 1 }
        assertTrue(controller.isIdle)
        awaitCondition { idleCount.get() >= 1 }
        // The error stays visible (not overwritten by Stopped) after teardown.
        assertTrue(store.state.value is ServiceState.Error)

        sourceFactory = { FakeAudioSource() }
        controller.start()
        awaitState(ServiceState.Listening)
        assertEquals(2, sources.size)
    }

    @Test
    fun microphoneInitFailureShowsErrorNotListening() {
        sourceFactory = { FakeAudioSource(AudioStartResult.Failed(CaptureErrors.INIT_FAILED)) }
        controller.start()
        awaitCondition { store.state.value is ServiceState.Error }
        Thread.sleep(30)
        assertEquals(ServiceState.Error(CaptureErrors.INIT_FAILED), store.state.value)
        assertFalse(states.contains(ServiceState.Listening))
    }

    @Test
    fun permissionMissingNeverOpensMic() {
        permission = false
        assertEquals(ListeningSessionController.StartOutcome.PermissionMissing, controller.start())
        assertEquals(ServiceState.PermissionRequired, store.state.value)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun permissionRevokedWhileRunningFailsSafely() {
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().readThrows = SecurityException("revoked")
        awaitCondition { store.state.value is ServiceState.Error }
        assertEquals(ErrorRecovery.OpenAppSettings, (store.state.value as ServiceState.Error).recovery)
    }

    @Test
    fun modelLoadFailureReportsError() {
        loadFailure = LoadResult.Failed("Speech model not installed", ErrorRecovery.OpenModelSetup)
        controller.start()
        awaitCondition { store.state.value is ServiceState.Error }
        assertEquals(ErrorRecovery.OpenModelSetup, (store.state.value as ServiceState.Error).recovery)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun staleCallbackFromOldSessionIsIgnored() {
        var oldSetState: ((ServiceState) -> Boolean)? = null
        val capturing = PipelineLoader { cb ->
            if (oldSetState == null) oldSetState = cb.setState
            loader.load(cb)
        }
        val c = ListeningSessionController(
            scope, Dispatchers.IO, store, { true }, capturing,
            { onError, onSilenced -> AudioCaptureController(FakeAudioSource().also { sources += it }, onError = onError, onSilencedChanged = onSilenced) },
        )
        c.start()
        awaitState(ServiceState.Listening)
        runBlocking { c.stopAndJoin() }
        assertFalse(oldSetState!!(ServiceState.Speaking))
        assertEquals(ServiceState.Stopped, store.state.value)

        c.start()
        awaitState(ServiceState.Listening)
        assertFalse("old session cannot affect the new one", oldSetState!!(ServiceState.Processing))
        assertEquals(ServiceState.Listening, store.state.value)
        runBlocking { c.stopAndJoin() }
    }

    @Test
    fun notificationTalkOnlyWhileListening() {
        assertFalse(controller.requestTapToTalk(fromNotification = true))
        val gate = CompletableDeferred<Unit>()
        loadGate = gate
        controller.start()
        assertFalse("ignored while Starting", controller.requestTapToTalk(fromNotification = true))
        gate.complete(Unit)
        awaitState(ServiceState.Listening)
        assertTrue(controller.requestTapToTalk(fromNotification = true))
        awaitCondition { handlerCalls.get() == 1 }
    }

    @Test
    fun tapToTalkWhileStartingIsQueued() {
        controller.start(tapToTalk = true)
        awaitCondition { handlerCalls.get() == 1 }
    }

    @Test
    fun stopCommandEndsSession() {
        handler = CommandHandler { _, _ -> HandlerResult("Bye", stopRequested = true) }
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().firstSample = WAKE
        awaitState(ServiceState.Stopped)
        assertTrue(controller.isIdle)
    }

    @Test
    fun externalInactiveStateShutsSessionDown() {
        controller.start()
        awaitState(ServiceState.Listening)
        // e.g. something else reset the shared state: the next pipeline write must not resurrect it.
        store.update(ServiceState.Stopped)
        sources.single().firstSample = WAKE
        awaitCondition { controller.isIdle }
        stopAndJoin()
        assertEquals(ServiceState.Stopped, store.state.value)
    }

    @Test
    fun microphoneSilencedAndRecovered() {
        controller.start()
        awaitState(ServiceState.Listening)
        sources.single().onSilencedChanged?.invoke(true)
        awaitState(ServiceState.MicrophoneSilenced)
        assertTrue(ServiceState.MicrophoneSilenced.isActive)
        sources.single().onSilencedChanged?.invoke(false)
        awaitState(ServiceState.Listening)
    }

    private companion object {
        const val WAKE: Short = 42
    }
}
