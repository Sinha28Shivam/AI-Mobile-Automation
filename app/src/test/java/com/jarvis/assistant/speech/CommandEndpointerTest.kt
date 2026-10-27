package com.jarvis.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandEndpointerTest {

    private val frameMs = CommandEndpointer.frameDurationMs(512, 16_000) // 32 ms

    /** Feeds silence (empty partial) until the endpointer ends or [maxFrames] pass. */
    private fun CommandEndpointer.feedSilence(maxFrames: Int): Pair<UtteranceStatus, Int> {
        var status: UtteranceStatus = UtteranceStatus.InProgress
        var n = 0
        while (n < maxFrames && status == UtteranceStatus.InProgress) {
            status = onFrame(frameMs, partial = "")
            n++
        }
        return status to n
    }

    @Test
    fun frameDurationFromSamples() {
        assertEquals(32L, frameMs)
        assertEquals(1_000L, CommandEndpointer.frameDurationMs(16_000, 16_000))
    }

    @Test
    fun noSpeechTimesOutAfterFourSeconds() {
        val ep = CommandEndpointer()
        val (status, frames) = ep.feedSilence(1_000)
        assertEquals(UtteranceStatus.Ended(UtteranceEnd.NoSpeech), status)
        assertTrue(frames * frameMs in 4_000L until 4_000L + frameMs)
        assertFalse(ep.speechStarted)
    }

    @Test
    fun endsOnTrailingSilenceAfterSpeech() {
        val ep = CommandEndpointer()
        assertEquals(UtteranceStatus.InProgress, ep.onFrame(frameMs, "what"))
        assertEquals(UtteranceStatus.InProgress, ep.onFrame(frameMs, "what is"))
        assertTrue(ep.speechStarted)
        // Unchanged partial counts as silence.
        var elapsedSilence = 0L
        var status: UtteranceStatus = UtteranceStatus.InProgress
        while (status == UtteranceStatus.InProgress) {
            status = ep.onFrame(frameMs, "what is")
            elapsedSilence += frameMs
        }
        assertEquals(UtteranceStatus.Ended(UtteranceEnd.EndOfSpeech), status)
        assertTrue(elapsedSilence in 1_200L until 1_200L + frameMs)
    }

    @Test
    fun ongoingSpeechExtendsWindowUntilMaxDuration() {
        val ep = CommandEndpointer()
        var status: UtteranceStatus = UtteranceStatus.InProgress
        var i = 0
        var elapsed = 0L
        while (status == UtteranceStatus.InProgress) {
            status = ep.onFrame(frameMs, "word ${i++}")
            elapsed += frameMs
        }
        assertEquals(UtteranceStatus.Ended(UtteranceEnd.MaxDuration), status)
        assertTrue(elapsed in 6_000L until 6_000L + frameMs)
    }

    @Test
    fun finalSegmentCountsAsSpeechButNotAsNewActivity() {
        val ep = CommandEndpointer(trailingSilenceMs = 320)
        assertEquals(UtteranceStatus.InProgress, ep.onFrame(frameMs, partial = null, finalHadText = true))
        assertTrue(ep.speechStarted)
        val (status, frames) = ep.feedSilence(100)
        assertEquals(UtteranceStatus.Ended(UtteranceEnd.EndOfSpeech), status)
        assertEquals(10, frames)
    }

    @Test
    fun endedStateIsStickyUntilReset() {
        val ep = CommandEndpointer(noSpeechTimeoutMs = 64)
        ep.feedSilence(10)
        assertEquals(UtteranceStatus.Ended(UtteranceEnd.NoSpeech), ep.onFrame(frameMs, "late words"))
        ep.reset()
        assertEquals(UtteranceStatus.InProgress, ep.onFrame(frameMs, "hello"))
    }
}
