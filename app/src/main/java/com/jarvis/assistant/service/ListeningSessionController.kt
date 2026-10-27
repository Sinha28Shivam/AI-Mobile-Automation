package com.jarvis.assistant.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Hooks a [PipelineLoader] wires into the pipeline it builds; they are bound to one session. */
class PipelineCallbacks(
    /** Returns false once the session is no longer current (stopped/replaced); the pipeline then stops. */
    val setState: (ServiceState) -> Boolean,
    /** Ends the session (e.g. "stop listening" command). */
    val onStopRequested: () -> Unit,
    val metrics: VoiceMetrics?,
)

sealed interface LoadResult {
    /** [onClose] runs once after the pipeline and microphone have been released. */
    class Ready(val pipeline: VoicePipeline, val onClose: () -> Unit = {}) : LoadResult
    data class Failed(val message: String, val recovery: ErrorRecovery = ErrorRecovery.Retry) : LoadResult
}

/**
 * Loads the speech model and builds a fresh, unstarted [VoicePipeline] for one session.
 * Runs on a background dispatcher and is always allowed to finish (native model loading
 * cannot be interrupted); the controller releases the result if the session was stopped.
 */
fun interface PipelineLoader {
    suspend fun load(callbacks: PipelineCallbacks): LoadResult
}

/** Creates a new single-use [AudioCaptureController] (the microphone owner) for one session. */
fun interface CaptureFactory {
    fun create(
        onError: (message: String, recovery: ErrorRecovery) -> Unit,
        onSilencedChanged: (Boolean) -> Unit,
    ): AudioCaptureController
}

/**
 * Owns the listening lifecycle for [JarvisForegroundService]; kept free of Android
 * framework types so it can be tested on the JVM with fakes.
 *
 * Each Start creates a new session with a monotonically increasing generation. All
 * state writes from a session's pipeline/capture callbacks go through [setStateFor],
 * which rejects writes once the session is no longer current, so a late callback can
 * never resurrect a stopped service or leak into a newer session. Each session gets
 * a brand-new pipeline and capture controller; cancelled instances are never reused.
 *
 * Shutdown is observable: the state moves to [ServiceState.Stopping] (or Error)
 * immediately and reaches [ServiceState.Stopped] only after the microphone, the
 * recognizers and the model were released ([stopAndJoin] suspends until then). A new
 * session waits for the previous teardown before opening the microphone.
 *
 * Public methods may be called from any thread.
 */
class ListeningSessionController(
    private val scope: CoroutineScope,
    backgroundDispatcher: CoroutineDispatcher,
    private val store: ServiceStateStore,
    private val hasMicrophonePermission: () -> Boolean,
    private val loader: PipelineLoader,
    private val captureFactory: CaptureFactory,
    private val onIdle: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val metrics: VoiceMetrics? = null,
    /** Teardown of a previous controller (e.g. a destroyed service instance) to wait for before opening the mic. */
    priorTeardown: Job? = null,
) {
    enum class StartOutcome { Started, AlreadyRunning, PermissionMissing }

    private inner class Session(val generation: Long) {
        @Volatile var stopping = false
        var startJob: Job? = null
        @Volatile var loading: Deferred<LoadResult>? = null
        @Volatile var pipeline: VoicePipeline? = null
        @Volatile var capture: AudioCaptureController? = null
        var pendingTap = false
        var teardown: Job? = null
    }

    // Never cancelled: loads and teardowns must always run to completion so nothing leaks.
    private val backgroundScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val lock = Any()
    private var generation = 0L
    private var current: Session? = null
    private var lastTeardown: Job? = priorTeardown

    /** The most recent teardown job (completed once every released resource is freed), or null. */
    val pendingTeardown: Job? get() = synchronized(lock) { lastTeardown }

    /** True when no session is active (a teardown may still be finishing). */
    val isIdle: Boolean get() = synchronized(lock) { current == null }

    /** Generation of the active session, or null. For tests/diagnostics. */
    val activeGeneration: Long? get() = synchronized(lock) { current?.generation }

    /**
     * Starts a session, or (if one is already running) does nothing except forwarding a
     * tap-to-talk request. Never creates a second microphone owner.
     */
    fun start(tapToTalk: Boolean = false): StartOutcome {
        synchronized(lock) {
            val running = current
            if (running != null) {
                if (tapToTalk) requestTapLocked(running)
                return StartOutcome.AlreadyRunning
            }
            if (!hasMicrophonePermission()) {
                store.update(ServiceState.PermissionRequired)
                return StartOutcome.PermissionMissing
            }
            val session = Session(++generation)
            session.pendingTap = tapToTalk
            current = session
            store.update(ServiceState.Starting)
            val previousTeardown = lastTeardown
            session.startJob = scope.launch {
                previousTeardown?.join()
                runSession(session)
            }
            return StartOutcome.Started
        }
    }

    /**
     * Tap-to-talk. From the notification it is only honoured while [ServiceState.Listening];
     * from the app it is also queued while the session is starting. Returns true if accepted.
     */
    fun requestTapToTalk(fromNotification: Boolean): Boolean = synchronized(lock) {
        val session = current ?: return false
        if (fromNotification && store.state.value != ServiceState.Listening) return false
        requestTapLocked(session)
        true
    }

    private fun requestTapLocked(session: Session) {
        val pipeline = session.pipeline
        when (store.state.value) {
            ServiceState.Listening -> pipeline?.requestTapToTalk() ?: run { session.pendingTap = true }
            ServiceState.Starting -> session.pendingTap = true
            else -> Unit // Busy with a command or mic silenced: ignore.
        }
    }

    /** Applies a new wake-word sensitivity to the running pipeline, if any. */
    fun setWakeSensitivity(value: Float) {
        synchronized(lock) { current?.pipeline }?.setWakeSensitivity(value)
    }

    /** User Stop. Idempotent; returns immediately, see [stopAndJoin]. */
    fun stop() {
        val session = synchronized(lock) { current }
        if (session != null) {
            endSession(session, error = null)
            return
        }
        // Nothing running: make sure the UI is not left in a transient state.
        val pending = synchronized(lock) {
            val teardown = lastTeardown
            if (teardown == null || teardown.isCompleted) {
                if (store.state.value.isActive || store.state.value == ServiceState.Stopping) {
                    store.update(ServiceState.Stopped)
                }
                false
            } else {
                true
            }
        }
        if (!pending) scope.launch { if (isIdle) onIdle() }
    }

    /** Stops and suspends until every resource of every previous session has been released. */
    suspend fun stopAndJoin() {
        stop()
        synchronized(lock) { lastTeardown }?.join()
    }

    private suspend fun runSession(session: Session) {
        val callbacks = PipelineCallbacks(
            setState = { setStateFor(session, it) },
            onStopRequested = { endSession(session, error = null) },
            metrics = metrics,
        )
        val loadStartedAt = clock()
        val loading = backgroundScope.async {
            try {
                loader.load(callbacks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pipeline load failed: ${e.javaClass.simpleName}")
                LoadResult.Failed(MSG_LOAD_FAILED)
            }
        }
        session.loading = loading
        val result = loading.await()

        val ready = when (result) {
            is LoadResult.Failed -> {
                fail(session, result.message, result.recovery)
                return
            }
            is LoadResult.Ready -> result
        }
        metrics?.recordModelLoad(clock() - loadStartedAt)
        if (session.stopping) return
        val pipeline = ready.pipeline
        session.pipeline = pipeline
        if (!pipeline.start()) {
            fail(session, MSG_LOAD_FAILED, ErrorRecovery.Retry)
            return
        }

        val capture = captureFactory.create(
            onError = { message, recovery ->
                metrics?.recordCaptureError()
                fail(session, message, recovery)
            },
            onSilencedChanged = { pipeline.setMicrophoneSilenced(it) },
        )
        capture.addFrameListener(pipeline::offerFrame)
        session.capture = capture
        if (session.stopping) return
        if (!capture.start()) return // onError already ended the session with an actionable message.
        // Only now is the microphone really open. Don't overwrite a state the pipeline already reported.
        val listening = synchronized(lock) {
            if (store.state.value == ServiceState.Starting) setStateFor(session, ServiceState.Listening)
            else current === session && !session.stopping
        }
        if (!listening) return
        val tap = synchronized(lock) { session.pendingTap.also { session.pendingTap = false } }
        if (tap) pipeline.requestTapToTalk()
    }

    /** Writes [state] only if [session] is still the current, non-stopping session. */
    private fun setStateFor(session: Session, state: ServiceState): Boolean = synchronized(lock) {
        if (current !== session || session.stopping) return false
        if (!store.state.value.isActive) {
            // Someone else moved the shared state to an inactive value: never keep the mic open
            // behind a UI that no longer says we are listening.
            endSession(session, error = null)
            return false
        }
        store.update(state)
        true
    }

    private fun fail(session: Session, message: String, recovery: ErrorRecovery) {
        endSession(session, ServiceState.Error(message, recovery))
    }

    private fun endSession(session: Session, error: ServiceState.Error?) {
        synchronized(lock) {
            if (session.stopping) return
            session.stopping = true
            if (current === session) {
                current = null
                when {
                    error != null -> store.update(error)
                    store.state.value.isActive -> store.update(ServiceState.Stopping)
                }
            }
            val previous = lastTeardown
            val job = backgroundScope.launch {
                previous?.join()
                teardown(session, error)
            }
            session.teardown = job
            lastTeardown = job
        }
    }

    private suspend fun teardown(session: Session, error: ServiceState.Error?) {
        try {
            // After this join no code of the session's start path can run any more.
            synchronized(lock) { session.startJob }?.cancelAndJoin()
            val ready = try {
                session.loading?.await() as? LoadResult.Ready
            } catch (e: CancellationException) {
                null
            }
            session.capture?.stopAndJoin()
            ready?.pipeline?.stopAndJoin()
            try {
                ready?.onClose?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Session close failed: ${e.javaClass.simpleName}")
            }
        } finally {
            val idle = synchronized(lock) {
                val noNewerSession = current == null
                if (noNewerSession && error == null && store.state.value == ServiceState.Stopping) {
                    store.update(ServiceState.Stopped)
                }
                noNewerSession && lastTeardown === session.teardown
            }
            if (idle) scope.launch { if (isIdle) onIdle() }
        }
    }

    companion object {
        private const val TAG = "ListeningSession"
        const val MSG_LOAD_FAILED = "Speech recognizer could not be started. Tap Start to try again."
    }
}
