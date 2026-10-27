package com.jarvis.assistant.service

import android.media.AudioRecord
import com.jarvis.assistant.service.FakeAudioSource.Companion.AUDIO_ERROR
import com.jarvis.assistant.service.FakeAudioSource.Companion.AUDIO_ERROR_BAD_VALUE
import com.jarvis.assistant.service.FakeAudioSource.Companion.AUDIO_ERROR_DEAD_OBJECT
import com.jarvis.assistant.service.FakeAudioSource.Companion.AUDIO_ERROR_INVALID_OPERATION
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioCaptureControllerTest {

    private val errors = Collections.synchronizedList(mutableListOf<Pair<String, ErrorRecovery>>())

    private fun controller(source: FakeAudioSource) =
        AudioCaptureController(source, onError = { m, r -> errors += m to r })

    @Test
    fun audioRecordErrorConstantsMatchFakes() {
        assertEquals(AudioRecord.ERROR, AUDIO_ERROR)
        assertEquals(AudioRecord.ERROR_BAD_VALUE, AUDIO_ERROR_BAD_VALUE)
        assertEquals(AudioRecord.ERROR_INVALID_OPERATION, AUDIO_ERROR_INVALID_OPERATION)
        assertEquals(AudioRecord.ERROR_DEAD_OBJECT, AUDIO_ERROR_DEAD_OBJECT)
    }

    @Test
    fun deliversFramesAndStopReleasesExactlyOnce() = runBlocking {
        val source = FakeAudioSource()
        val c = controller(source)
        val got = CountDownLatch(3)
        c.addFrameListener { _, _ -> got.countDown() }
        assertTrue(c.start())
        assertTrue(got.await(2, TimeUnit.SECONDS))
        c.stopAndJoin()
        c.stop()
        c.stopAndJoin()
        assertEquals(1, source.releases.get())
        assertFalse(c.isCapturing)
        assertTrue("user stop is not an error", errors.isEmpty())
    }

    @Test
    fun repeatedStartOpensMicrophoneOnce() = runBlocking {
        val source = FakeAudioSource()
        val c = controller(source)
        assertTrue(c.start())
        assertTrue(c.start())
        assertEquals(1, source.starts.get())
        c.stopAndJoin()
        assertFalse("single use: cannot restart after stop", c.start())
        assertEquals(1, source.starts.get())
    }

    @Test
    fun eachNegativeReadCodeStopsWithActionableMessage() {
        val expected = mapOf(
            AUDIO_ERROR_INVALID_OPERATION to CaptureErrors.INVALID_OPERATION,
            AUDIO_ERROR_BAD_VALUE to CaptureErrors.BAD_VALUE,
            AUDIO_ERROR_DEAD_OBJECT to CaptureErrors.DEAD_OBJECT,
            AUDIO_ERROR to CaptureErrors.GENERIC_READ,
        )
        for ((code, message) in expected) {
            errors.clear()
            val source = FakeAudioSource()
            val c = controller(source)
            source.failNextRead(code)
            assertTrue(c.start())
            runBlocking {
                withTimeout(2_000) { while (source.releases.get() == 0) kotlinx.coroutines.delay(5) }
            }
            assertEquals("code $code", listOf(message to ErrorRecovery.Retry), errors.toList())
            assertEquals(1, source.releases.get())
            assertFalse(c.isCapturing)
            c.stop()
            assertEquals(1, source.releases.get())
        }
    }

    @Test
    fun startFailureReportsErrorAndReleases() {
        val source = FakeAudioSource(AudioStartResult.Failed(CaptureErrors.INIT_FAILED))
        val c = controller(source)
        assertFalse(c.start())
        assertEquals(listOf(CaptureErrors.INIT_FAILED to ErrorRecovery.Retry), errors.toList())
        assertEquals(1, source.releases.get())
        assertFalse(c.start())
    }

    @Test
    fun permissionRevokedDuringStartOrReadIsSafe() = runBlocking {
        val startThrows = FakeAudioSource(throwOnStart = SecurityException("revoked"))
        assertFalse(controller(startThrows).start())
        assertEquals(CaptureErrors.PERMISSION_DENIED to ErrorRecovery.OpenAppSettings, errors.single())
        assertEquals(1, startThrows.releases.get())

        errors.clear()
        val readThrows = FakeAudioSource().apply { readThrows = SecurityException("revoked") }
        val c = controller(readThrows)
        assertTrue(c.start())
        withTimeout(2_000) { while (readThrows.releases.get() == 0) kotlinx.coroutines.delay(5) }
        assertEquals(CaptureErrors.PERMISSION_DENIED to ErrorRecovery.OpenAppSettings, errors.single())
    }

    @Test
    fun readErrorAfterUserStopIsNotReported() = runBlocking {
        val source = FakeAudioSource()
        val c = controller(source)
        assertTrue(c.start())
        c.stopAndJoin()
        source.failNextRead(AUDIO_ERROR_DEAD_OBJECT)
        Thread.sleep(30)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun stopBeforeStartReleasesNothingTwice() {
        val source = FakeAudioSource()
        val c = controller(source)
        c.stop()
        c.stop()
        assertFalse(c.start())
        assertEquals(0, source.starts.get())
    }

    @Test
    fun silenceCallbackForwardedOnlyWhileRunning() = runBlocking {
        val source = FakeAudioSource()
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val c = AudioCaptureController(source, onSilencedChanged = { seen += it })
        assertTrue(c.start())
        source.onSilencedChanged?.invoke(true)
        source.onSilencedChanged?.invoke(false)
        val callback = source.onSilencedChanged
        c.stopAndJoin()
        callback?.invoke(true)
        assertEquals(listOf(true, false), seen.toList())
    }
}
