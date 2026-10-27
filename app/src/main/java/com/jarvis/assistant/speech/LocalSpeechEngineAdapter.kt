package com.jarvis.assistant.speech

import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline [SpeechToTextEngine] backed by a Vosk [Recognizer] (Apache 2.0).
 *
 * Fed from our own AudioCaptureController, so Vosk's SpeechService (which
 * opens its own AudioRecord) is intentionally not used. The [model] is owned by
 * the caller and must outlive this adapter. Not thread-safe: use from one thread.
 */
class LocalSpeechEngineAdapter(
    model: Model,
    private val sampleRate: Int,
    private val endpointer: CommandEndpointer = CommandEndpointer(),
) : SpeechToTextEngine {

    private val recognizer = Recognizer(model, sampleRate.toFloat()).apply { setWords(true) }
    private val words = ArrayList<RecognizedWord>()
    private var endReason: UtteranceEnd? = null
    private var released = false

    override fun startUtterance() {
        check(!released) { "Engine released" }
        recognizer.reset()
        words.clear()
        endpointer.reset()
        endReason = null
    }

    override fun acceptFrame(frame: ShortArray, length: Int): UtteranceStatus {
        endReason?.let { return UtteranceStatus.Ended(it) }
        val frameMs = CommandEndpointer.frameDurationMs(length, sampleRate)
        val status = if (recognizer.acceptWaveForm(frame, length)) {
            val segment = VoskJson.parseWords(recognizer.result)
            words += segment
            endpointer.onFrame(frameMs, partial = null, finalHadText = segment.any { it.word != VoskJson.UNKNOWN_TOKEN })
        } else {
            endpointer.onFrame(frameMs, partial = VoskJson.parsePartial(recognizer.partialResult))
        }
        if (status is UtteranceStatus.Ended) endReason = status.reason
        return status
    }

    override fun finish(reason: UtteranceEnd): SpeechResult {
        words += VoskJson.parseWords(recognizer.finalResult)
        val result = VoskJson.toResult(words, endReason ?: reason)
        words.clear()
        return result
    }

    override fun release() {
        if (released) return
        released = true
        words.clear()
        recognizer.close()
    }
}
