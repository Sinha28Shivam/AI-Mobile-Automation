package com.jarvis.assistant.service

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.speech.SpeechResult
import com.jarvis.assistant.speech.SpeechToTextEngine
import com.jarvis.assistant.speech.UtteranceEnd
import com.jarvis.assistant.speech.UtteranceStatus
import com.jarvis.assistant.wakeword.WakePhrase
import com.jarvis.assistant.wakeword.WakeWordDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** Outcome of handling one recognized command. */
data class HandlerResult(val spokenText: String?, val stopRequested: Boolean = false)

/**
 * Turns a recognized command into a result. Called on a background dispatcher;
 * may suspend (it is subject to a timeout). [text] is private user speech: never log it.
 * Only called when [confidence] is at or above the pipeline's minimum command confidence.
 */
fun interface CommandHandler {
    suspend fun handle(text: String, confidence: Float): HandlerResult
}

/** Speaks a reply and suspends until playback has finished (or failed). */
fun interface SpeechSpeaker {
    suspend fun speak(text: String)

    companion object {
        val NoOp = SpeechSpeaker { }
    }
}

/** Placeholder handler until the real command router is wired in. */
class EchoCommandHandler : CommandHandler {
    override suspend fun handle(text: String, confidence: Float) = HandlerResult("You said: $text")
}

/**
 * Wake word -> bounded command capture -> STT -> [CommandHandler] -> [SpeechSpeaker] -> back to wake word.
 *
 * Single use: one instance per listening session. After [stop] its scope is cancelled
 * and [start] returns false; create a new pipeline for the next session.
 *
 * Concurrency: frames are pushed by the capture thread through [offerFrame] into a bounded
 * DROP_OLDEST channel and consumed by exactly one coroutine, which is the only code that
 * touches [wakeWord] and [speechToText] (native Vosk recognizers). The engines are released
 * from that coroutine's completion handler, i.e. only after it can no longer use them, and
 * exactly once. [stopAndJoin] suspends until that release (and [onReleased]) has happened.
 *
 * While processing/speaking, frames are discarded (and the queue is drained before resuming)
 * so the assistant's own voice cannot trigger the wake word or end up in the next command.
 * Every state change goes through [setState]; when it returns false (the owning session is
 * no longer current) the pipeline stops itself instead of getting stuck.
 */
class VoicePipeline(
    private val wakeWord: WakeWordDetector,
    private val speechToText: SpeechToTextEngine,
    @Volatile var commandHandler: CommandHandler = EchoCommandHandler(),
    @Volatile var speaker: SpeechSpeaker = SpeechSpeaker.NoOp,
    private val onStopRequested: () -> Unit = {},
    private val onExchange: (command: String, reply: String?) -> Unit = { _, _ -> },
    private val setState: (ServiceState) -> Boolean = ServiceStateRepository::updateIfActive,
    private val onReleased: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val handlerTimeoutMs: Long = DEFAULT_HANDLER_TIMEOUT_MS,
    private val speakTimeoutMs: Long = DEFAULT_SPEAK_TIMEOUT_MS,
    frameCapacity: Int = DEFAULT_FRAME_CAPACITY,
    private val minCommandConfidence: Float = LOW_CONFIDENCE_THRESHOLD,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val metrics: VoiceMetrics? = null,
) {
    private enum class Mode { WakeWord, Command, Busy }

    private class Frame(val samples: ShortArray, val capturedAtMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val frames = Channel<Frame>(frameCapacity, BufferOverflow.DROP_OLDEST)
    private val tapRequested = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val releasedOnce = AtomicBoolean(false)
    private val released = CompletableDeferred<Unit>()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var mode = Mode.WakeWord

    @Volatile
    private var wakeDetected = false

    @Volatile
    private var micSilenced = false
    private var reportedSilenced = false

    /** Starts the consumer. Returns false if already started or already stopped. */
    fun start(): Boolean {
        if (stopped.get() || !started.compareAndSet(false, true)) return false
        wakeWord.onDetected = { if (mode == Mode.WakeWord) wakeDetected = true }
        val consumer = scope.launch { run() }
        // Runs after the consumer is fully done, so native engines are never freed mid-call.
        consumer.invokeOnCompletion { releaseEngines() }
        job = consumer
        return true
    }

    val isStopped: Boolean get() = stopped.get()

    /** Called on the capture thread. Copies the frame; never blocks. */
    fun offerFrame(frame: ShortArray, length: Int) {
        if (length <= 0 || mode == Mode.Busy || stopped.get()) return
        frames.trySend(Frame(frame.copyOf(length), clock()))
    }

    fun setWakeSensitivity(value: Float) {
        wakeWord.sensitivity = value
    }

    /** Reported by the capture owner when Android silences/unsilences our microphone client. */
    fun setMicrophoneSilenced(silenced: Boolean) {
        micSilenced = silenced
    }

    /** Skips the wake word and captures a command right away (ignored unless waiting for the wake word). */
    fun requestTapToTalk() {
        if (!stopped.get()) tapRequested.set(true)
    }

    /** Cancels processing; engines are released asynchronously once the consumer exits. Idempotent. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        frames.close()
        scope.cancel()
        // Never started: no consumer will run its completion handler.
        if (started.compareAndSet(false, true)) releaseEngines()
    }

    /** Stops and suspends until the engines have been released and [onReleased] has run. */
    suspend fun stopAndJoin() {
        stop()
        job?.join()
        released.await()
    }

    private suspend fun run() {
        wakeWord.start()
        for (frame in frames) {
            if (tapRequested.getAndSet(false) && mode == Mode.WakeWord) {
                metrics?.recordTapToTalk()
                if (!beginCommand()) return
            }
            when (mode) {
                Mode.WakeWord -> {
                    if (micSilenced != reportedSilenced) {
                        reportedSilenced = micSilenced
                        if (reportedSilenced) metrics?.recordMicrophoneSilenced()
                        if (!report(listeningState())) return
                    }
                    wakeWord.onFrame(frame.samples, frame.samples.size)
                    if (wakeDetected) {
                        wakeDetected = false
                        metrics?.recordWakeDetected(clock() - frame.capturedAtMs)
                        if (!beginCommand()) return
                    }
                }
                Mode.Command -> {
                    val status = speechToText.acceptFrame(frame.samples, frame.samples.size)
                    if (status is UtteranceStatus.Ended) {
                        mode = Mode.Busy
                        val endedAt = clock()
                        val result = try {
                            speechToText.finish(status.reason)
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Speech finish failed: ${e.javaClass.simpleName}")
                            SpeechResult("", 0f, status.reason)
                        }
                        if (!handleUtterance(result, endedAt)) return
                    }
                }
                Mode.Busy -> Unit
            }
        }
    }

    private fun listeningState(): ServiceState =
        if (reportedSilenced) ServiceState.MicrophoneSilenced else ServiceState.Listening

    /** Reports [state]; if the session is no longer current, stops this pipeline and returns false. */
    private fun report(state: ServiceState): Boolean {
        if (stopped.get()) return false
        if (setState(state)) return true
        stop()
        return false
    }

    private fun beginCommand(): Boolean {
        wakeWord.stop()
        wakeDetected = false
        speechToText.startUtterance()
        mode = Mode.Command
        metrics?.recordCommandSession()
        return report(ServiceState.HeardWakeWord)
    }

    /** Returns false when the pipeline must stop consuming. */
    private suspend fun handleUtterance(result: SpeechResult, endedAtMs: Long): Boolean {
        if (!report(ServiceState.Processing)) return false
        var stop = false
        // The wake phrase is never part of a command ("hey jarvis open youtube" -> "open youtube").
        val command = WakePhrase.stripLeading(result.text)
        val reply: String? = try {
            when {
                result.isEmpty && result.endReason == UtteranceEnd.NoSpeech -> {
                    metrics?.recordNoSpeech()
                    NO_SPEECH_REPLY
                }
                result.isEmpty -> {
                    metrics?.recordLowConfidence()
                    LOW_CONFIDENCE_REPLY
                }
                // Only the wake phrase was heard again.
                command.isBlank() -> {
                    metrics?.recordNoSpeech()
                    NO_COMMAND_REPLY
                }
                result.confidence < minCommandConfidence -> {
                    metrics?.recordLowConfidence()
                    LOW_CONFIDENCE_REPLY
                }
                else -> {
                    val handled = withTimeout(handlerTimeoutMs) { commandHandler.handle(command, result.confidence) }
                    stop = handled.stopRequested
                    onExchange(command, handled.spokenText)
                    handled.spokenText
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Command handler timed out")
            metrics?.recordHandlerFailure()
            ERROR_REPLY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Command handler failed: ${e.javaClass.simpleName}")
            metrics?.recordHandlerFailure()
            ERROR_REPLY
        }
        metrics?.recordResponseLatency(clock() - endedAtMs)

        if (!reply.isNullOrBlank()) {
            if (!report(ServiceState.Speaking)) return false
            try {
                withTimeout(speakTimeoutMs) { speaker.speak(reply) }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Speech output timed out")
                metrics?.recordSpeechOutputFailure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Speech output failed: ${e.javaClass.simpleName}")
                metrics?.recordSpeechOutputFailure()
            }
        }

        if (stop) {
            onStopRequested()
            return false
        }
        return resumeWakeWord()
    }

    private fun resumeWakeWord(): Boolean {
        if (stopped.get()) return false
        // Discard anything captured while busy (e.g. our own TTS), then listen again.
        while (frames.tryReceive().isSuccess) Unit
        tapRequested.set(false)
        wakeDetected = false
        // Cooldown starts at the end of the command session, not at detection time.
        wakeWord.onSessionEnded()
        wakeWord.start()
        reportedSilenced = micSilenced
        mode = Mode.WakeWord
        return report(listeningState())
    }

    private fun releaseEngines() {
        if (!releasedOnce.compareAndSet(false, true)) return
        try {
            wakeWord.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake word release failed: ${e.javaClass.simpleName}")
        }
        try {
            speechToText.release()
        } catch (e: Exception) {
            Log.w(TAG, "Speech engine release failed: ${e.javaClass.simpleName}")
        }
        try {
            onReleased()
        } catch (e: Exception) {
            Log.w(TAG, "Release callback failed: ${e.javaClass.simpleName}")
        } finally {
            released.complete(Unit)
        }
    }

    companion object {
        private const val TAG = "VoicePipeline"
        const val LOW_CONFIDENCE_THRESHOLD = 0.4f
        const val LOW_CONFIDENCE_REPLY = "Sorry, I didn't catch that clearly. Say \"Hey Jarvis\" and try again."
        const val NO_SPEECH_REPLY = "I didn't hear a command. Say \"Hey Jarvis\", then your command."
        const val NO_COMMAND_REPLY = "Yes? Say \"Hey Jarvis\" followed by a command."
        const val ERROR_REPLY = "Sorry, something went wrong. Please try again."
        const val DEFAULT_HANDLER_TIMEOUT_MS = 10_000L
        const val DEFAULT_SPEAK_TIMEOUT_MS = 30_000L
        const val DEFAULT_FRAME_CAPACITY = 32
    }
}
