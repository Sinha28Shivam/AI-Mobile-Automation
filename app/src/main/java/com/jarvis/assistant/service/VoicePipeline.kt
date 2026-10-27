package com.jarvis.assistant.service

import android.util.Log
import com.jarvis.assistant.speech.SpeechResult
import com.jarvis.assistant.speech.SpeechToTextEngine
import com.jarvis.assistant.speech.UtteranceEnd
import com.jarvis.assistant.speech.UtteranceStatus
import com.jarvis.assistant.wakeword.WakeWordDetector
import kotlinx.coroutines.CancellationException
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
 * Frames are pushed by the capture thread through [offerFrame] into a bounded
 * DROP_OLDEST channel and consumed by one coroutine, which is the only code that
 * touches [wakeWord] and [speechToText]. While processing/speaking, frames are
 * discarded so the assistant's own voice cannot trigger the wake word or end up
 * in the next command. Engines are released when the pipeline stops, after the
 * consumer has finished, via [onReleased].
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
) {
    private enum class Mode { WakeWord, Command, Busy }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val frames = Channel<ShortArray>(frameCapacity, BufferOverflow.DROP_OLDEST)
    private val tapRequested = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var job: Job? = null

    @Volatile
    private var mode = Mode.WakeWord

    @Volatile
    private var wakeDetected = false

    /** Starts the consumer. Calling it more than once has no effect. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        wakeWord.onDetected = { wakeDetected = true }
        val consumer = scope.launch { run() }
        // Runs after the consumer is fully done, so native engines are never freed mid-call.
        consumer.invokeOnCompletion { releaseEngines() }
        job = consumer
    }

    /** Called on the capture thread. Copies the frame; never blocks. */
    fun offerFrame(frame: ShortArray, length: Int) {
        if (length <= 0 || mode == Mode.Busy) return
        frames.trySend(frame.copyOf(length))
    }

    fun setWakeSensitivity(value: Float) {
        wakeWord.sensitivity = value
    }

    /** Skips the wake word and captures a command right away (ignored while busy). */
    fun requestTapToTalk() {
        tapRequested.set(true)
    }

    /** Cancels processing and releases engines asynchronously. Safe to call repeatedly. */
    fun stop() {
        frames.close()
        if (job == null) {
            if (started.compareAndSet(false, true)) releaseEngines()
        }
        scope.cancel()
    }

    private suspend fun run() {
        wakeWord.start()
        for (frame in frames) {
            if (tapRequested.getAndSet(false) && mode == Mode.WakeWord) beginCommand()
            when (mode) {
                Mode.WakeWord -> {
                    wakeWord.onFrame(frame, frame.size)
                    if (wakeDetected) {
                        wakeDetected = false
                        beginCommand()
                    }
                }
                Mode.Command -> {
                    val status = speechToText.acceptFrame(frame, frame.size)
                    if (status is UtteranceStatus.Ended) {
                        mode = Mode.Busy
                        handleUtterance(speechToText.finish(status.reason))
                    }
                }
                Mode.Busy -> Unit
            }
        }
    }

    private fun beginCommand() {
        wakeWord.stop()
        speechToText.startUtterance()
        mode = Mode.Command
        setState(ServiceState.HeardWakeWord)
    }

    private suspend fun handleUtterance(result: SpeechResult) {
        if (!setState(ServiceState.Processing)) return
        var stop = false
        val reply: String? = try {
            when {
                result.isEmpty && result.endReason == UtteranceEnd.NoSpeech -> NO_SPEECH_REPLY
                result.isEmpty || result.confidence < LOW_CONFIDENCE_THRESHOLD -> LOW_CONFIDENCE_REPLY
                else -> {
                    val handled = withTimeout(handlerTimeoutMs) { commandHandler.handle(result.text, result.confidence) }
                    stop = handled.stopRequested
                    onExchange(result.text, handled.spokenText)
                    handled.spokenText
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Command handler timed out")
            ERROR_REPLY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Command handler failed: ${e.javaClass.simpleName}")
            ERROR_REPLY
        }

        if (!reply.isNullOrBlank() && setState(ServiceState.Speaking)) {
            try {
                withTimeout(speakTimeoutMs) { speaker.speak(reply) }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Speech output timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Speech output failed: ${e.javaClass.simpleName}")
            }
        }

        if (stop) {
            onStopRequested()
            return
        }
        resumeWakeWord()
    }

    private fun resumeWakeWord() {
        // Discard anything captured while busy (e.g. our own TTS), then listen again.
        while (frames.tryReceive().isSuccess) Unit
        tapRequested.set(false)
        wakeWord.start()
        mode = Mode.WakeWord
        setState(ServiceState.Listening)
    }

    private fun releaseEngines() {
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
        onReleased()
    }

    companion object {
        private const val TAG = "VoicePipeline"
        const val LOW_CONFIDENCE_THRESHOLD = 0.4f
        const val LOW_CONFIDENCE_REPLY = "Sorry, I didn't catch that."
        const val NO_SPEECH_REPLY = "I didn't hear a command."
        const val ERROR_REPLY = "Sorry, something went wrong."
        const val DEFAULT_HANDLER_TIMEOUT_MS = 10_000L
        const val DEFAULT_SPEAK_TIMEOUT_MS = 30_000L
        const val DEFAULT_FRAME_CAPACITY = 32
    }
}
