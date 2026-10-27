package com.jarvis.assistant

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.tts.SpeechOutput
import com.jarvis.assistant.tts.TtsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * (f) TTS initialization smoke test. Devices without a TTS engine or voice are valid: the app
 * must settle in an explicit failure state rather than hang in Initializing. Nothing is spoken.
 */
@RunWith(AndroidJUnit4::class)
class TtsInitTest {

    @Test
    fun platformTtsInitCompletes() {
        val latch = CountDownLatch(1)
        var status = Int.MIN_VALUE
        lateinit var tts: TextToSpeech
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tts = TextToSpeech(TestSupport.targetContext) {
                status = it
                latch.countDown()
            }
        }
        try {
            assertTrue("TextToSpeech init callback never fired", latch.await(15, TimeUnit.SECONDS))
            assertTrue("status=$status", status == TextToSpeech.SUCCESS || status == TextToSpeech.ERROR)
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun speechOutputSettlesInKnownState() = runBlocking {
        lateinit var output: SpeechOutput
        InstrumentationRegistry.getInstrumentation().runOnMainSync { output = SpeechOutput(TestSupport.targetContext) }
        try {
            val settled = withTimeoutOrNull(15_000) { output.state.first { it != TtsState.Initializing } }
            assertTrue("SpeechOutput stayed Initializing", settled != null)
            assertNotEquals(TtsState.Shutdown, settled)
            val ready = output.awaitReady(1_000)
            assertEquals(settled == TtsState.Ready, ready)
            // Blank input is rejected without touching the engine.
            assertEquals(false, output.speak("   "))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { output.shutdown() }
        }
        assertEquals(TtsState.Shutdown, output.state.value)
        assertEquals(false, output.speak("hello"))
    }
}
