package com.jarvis.assistant.tts

import android.content.Context
import android.util.Log
import com.jarvis.assistant.service.ServiceNoticeRepository
import com.jarvis.assistant.service.SpeechSpeaker
import kotlinx.coroutines.CancellationException

/**
 * Adapts [SpeechOutput] to [SpeechSpeaker]. If no usable TTS voice exists, it reports a
 * non-fatal notice instead of failing, so the assistant keeps working silently.
 */
class TtsSpeaker internal constructor(
    private val output: SpeechOutput?,
    private val onUnavailable: (String) -> Unit = ServiceNoticeRepository::post,
    private val onAvailable: () -> Unit = ServiceNoticeRepository::clear,
) : SpeechSpeaker, AutoCloseable {

    override suspend fun speak(text: String) {
        val out = output ?: return onUnavailable(MSG_INIT_FAILED)
        if (!out.awaitReady()) {
            onUnavailable(messageFor(out.state.value))
            return
        }
        try {
            if (out.speak(text)) onAvailable()
        } catch (e: CancellationException) {
            out.stop()
            throw e
        }
    }

    override fun close() {
        output?.shutdown()
    }

    companion object {
        private const val TAG = "TtsSpeaker"
        const val MSG_INIT_FAILED =
            "Voice output unavailable: no working text-to-speech engine. Open voice settings to install one."
        const val MSG_MISSING_VOICE =
            "Voice output unavailable: the voice for your language isn't installed. Open voice settings to download it."

        internal fun messageFor(state: TtsState): String = when (state) {
            TtsState.LanguageMissingData, TtsState.LanguageNotSupported -> MSG_MISSING_VOICE
            else -> MSG_INIT_FAILED
        }

        fun create(context: Context): TtsSpeaker {
            val output = try {
                SpeechOutput(context)
            } catch (e: Exception) {
                Log.w(TAG, "TTS unavailable: ${e.javaClass.simpleName}")
                null
            }
            return TtsSpeaker(output)
        }
    }
}
