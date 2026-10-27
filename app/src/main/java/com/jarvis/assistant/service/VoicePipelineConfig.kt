package com.jarvis.assistant.service

import android.content.Context
import com.jarvis.assistant.tts.TtsSpeaker

/**
 * Integration point for the command layer. Defaults wire the real [RouterCommandHandler]
 * and on-device [TtsSpeaker]; tests or debug builds may replace them before starting the
 * service. Each listening session asks the providers for fresh instances; if a returned
 * [SpeechSpeaker] or [CommandHandler] also implements [AutoCloseable] (e.g. it wraps
 * TextToSpeech), the service closes it when listening stops.
 */
object VoicePipelineConfig {
    @Volatile
    var commandHandlerProvider: (Context) -> CommandHandler = { RouterCommandHandler.create(it) }

    @Volatile
    var speakerProvider: (Context) -> SpeechSpeaker = { TtsSpeaker.create(it) }
}
