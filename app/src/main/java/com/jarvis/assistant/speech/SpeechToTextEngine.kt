package com.jarvis.assistant.speech

/** Why a bounded command utterance ended. */
enum class UtteranceEnd {
    /** Speech was heard, followed by trailing silence. */
    EndOfSpeech,

    /** The hard maximum command window elapsed. */
    MaxDuration,

    /** Nothing was said within the no-speech timeout. */
    NoSpeech,
}

/** Status returned for every frame fed into an utterance. */
sealed interface UtteranceStatus {
    data object InProgress : UtteranceStatus
    data class Ended(val reason: UtteranceEnd) : UtteranceStatus
}

/**
 * Final transcript of one command utterance. [confidence] is the mean per-word
 * confidence in 0..1 (0 when nothing was recognized). Never log [text].
 */
data class SpeechResult(
    val text: String,
    val confidence: Float,
    val endReason: UtteranceEnd,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Provider-neutral offline speech-to-text for one bounded command at a time.
 *
 * Audio is pushed in by the single capture owner (see AudioCaptureController);
 * engines must not open the microphone themselves. Calls for one utterance are
 * expected from a single thread: [startUtterance], then [acceptFrame] until it
 * returns [UtteranceStatus.Ended] (or the caller gives up), then [finish].
 */
interface SpeechToTextEngine {
    fun startUtterance()

    /** Feeds [length] PCM16 16 kHz mono samples of [frame]. The array is not retained. */
    fun acceptFrame(frame: ShortArray, length: Int): UtteranceStatus

    /** Flushes the recognizer and returns the transcript; [reason] is used if the utterance had not ended yet. */
    fun finish(reason: UtteranceEnd = UtteranceEnd.EndOfSpeech): SpeechResult

    /** Frees native resources. The engine is unusable afterwards. */
    fun release()
}
