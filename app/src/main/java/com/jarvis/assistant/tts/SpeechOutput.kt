package com.jarvis.assistant.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface TtsState {
    data object Initializing : TtsState
    data object Ready : TtsState

    /** Engine works but the requested language needs a voice download. */
    data object LanguageMissingData : TtsState
    data object LanguageNotSupported : TtsState
    data object InitFailed : TtsState
    data object Shutdown : TtsState
}

/** Wraps Android [TextToSpeech]. Spoken text is never logged. */
class SpeechOutput(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<TtsState>(TtsState.Initializing)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Init result is posted so `tts` is always assigned before onInit runs, even if an engine
    // invokes the listener synchronously.
    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> mainHandler.post { onInit(status) } }

    private fun onInit(status: Int) {
        if (_state.value == TtsState.Shutdown) return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            _state.value = TtsState.InitFailed
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = complete(utteranceId, true)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                complete(utteranceId, false)
            }
            override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId, false)
        })
        _state.value = when (val r = tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> TtsState.LanguageMissingData
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                // Fall back to US English before giving up.
                if (tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE) TtsState.Ready
                else TtsState.LanguageNotSupported
            }
            else -> if (r >= TextToSpeech.LANG_AVAILABLE) TtsState.Ready else TtsState.LanguageNotSupported
        }
    }

    private fun complete(id: String?, ok: Boolean) {
        id?.let { pending.remove(it)?.complete(ok) }
    }

    /** Waits up to [initTimeoutMillis] for init; true if ready to speak. */
    suspend fun awaitReady(initTimeoutMillis: Long = INIT_TIMEOUT_MS): Boolean {
        val s = withTimeoutOrNull(initTimeoutMillis) { state.first { it != TtsState.Initializing } }
        return s == TtsState.Ready
    }

    /**
     * Speaks [text] (flushing anything queued) and suspends until done, error, or timeout.
     * Returns true only if the engine reported completion.
     */
    suspend fun speak(text: String, timeoutMillis: Long = defaultTimeout(text)): Boolean {
        if (text.isBlank() || !awaitReady()) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val queued = tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { done.await() } ?: false
        } finally {
            pending.remove(id)
        }
    }

    fun stop() {
        if (_state.value == TtsState.Ready) tts.stop()
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        _state.value = TtsState.Shutdown
        tts.shutdown()
    }

    companion object {
        private const val TAG = "JarvisTts"
        const val INIT_TIMEOUT_MS = 5_000L
        private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

        private fun defaultTimeout(text: String): Long = 5_000L + text.length * 120L

        /** Opens system TTS settings so the user can install an offline voice. */
        fun openTtsSettings(context: Context): Boolean {
            val intents = listOf(
                Intent(ACTION_TTS_SETTINGS),
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                Intent(Settings.ACTION_SETTINGS),
            )
            return intents.any {
                try {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }
    }
}
