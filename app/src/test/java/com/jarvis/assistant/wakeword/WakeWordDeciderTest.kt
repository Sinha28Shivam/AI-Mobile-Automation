package com.jarvis.assistant.wakeword

import com.jarvis.assistant.speech.RecognizedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDeciderTest {

    private fun words(vararg pairs: Pair<String, Float>) = pairs.map { RecognizedWord(it.first, it.second) }

    @Test
    fun triggersOnKeywordAboveThreshold() {
        val decider = WakeWordDecider()
        val result = decider.evaluateFinal(words("hey" to 1f, "jarvis" to 0.9f), sensitivity = 0.5f, nowMs = 0)
        assertEquals(0.9f, result!!, 0.0001f)
    }

    @Test
    fun ignoresUnknownAndOtherWords() {
        val decider = WakeWordDecider()
        assertNull(decider.evaluateFinal(words("[unk]" to 1f), 1f, 0))
        assertNull(decider.evaluateFinal(words("hey" to 1f), 1f, 0))
        assertNull(decider.evaluateFinal(emptyList(), 1f, 0))
    }

    @Test
    fun keywordMatchIsWholeWordAndCaseInsensitive() {
        val decider = WakeWordDecider()
        assertNull(decider.evaluateFinal(words("jarvisx" to 1f), 1f, 0))
        assertNotNull(decider.evaluateFinal(words("JARVIS" to 1f), 1f, 0))
    }

    @Test
    fun sensitivityMapsToConfidenceThreshold() {
        assertEquals(0.95f, WakeWordDecider.requiredConfidence(0f), 0.0001f)
        assertEquals(0.35f, WakeWordDecider.requiredConfidence(1f), 0.0001f)
        assertEquals(0.65f, WakeWordDecider.requiredConfidence(0.5f), 0.0001f)
        assertEquals(0.95f, WakeWordDecider.requiredConfidence(-3f), 0.0001f)
        assertEquals(0.35f, WakeWordDecider.requiredConfidence(7f), 0.0001f)
    }

    @Test
    fun lowConfidenceRejectedAtStrictSensitivity() {
        val decider = WakeWordDecider()
        assertNull(decider.evaluateFinal(words("jarvis" to 0.6f), sensitivity = 0f, nowMs = 0))
        assertNotNull(decider.evaluateFinal(words("jarvis" to 0.6f), sensitivity = 1f, nowMs = 0))
    }

    @Test
    fun cooldownDebouncesRepeatedDetections() {
        val decider = WakeWordDecider(cooldownMs = 2_000)
        val hit = words("jarvis" to 1f)
        assertNotNull(decider.evaluateFinal(hit, 0.5f, 1_000))
        assertTrue(decider.isCoolingDown(2_500))
        assertNull(decider.evaluateFinal(hit, 0.5f, 2_999))
        assertNotNull(decider.evaluateFinal(hit, 0.5f, 3_000))
    }

    @Test
    fun rejectedLowConfidenceDoesNotStartCooldown() {
        val decider = WakeWordDecider()
        assertNull(decider.evaluateFinal(words("jarvis" to 0.1f), 0.5f, 0))
        assertFalse(decider.isCoolingDown(1))
        assertNotNull(decider.evaluateFinal(words("jarvis" to 1f), 0.5f, 1))
    }

    @Test
    fun partialOnlyTriggersAtHighSensitivity() {
        val decider = WakeWordDecider()
        assertFalse(decider.evaluatePartial("hey jarvis", 0.5f, 0))
        assertTrue(decider.evaluatePartial("hey jarvis", 0.9f, 0))
        assertFalse("cooldown applies to partials too", decider.evaluatePartial("hey jarvis", 0.9f, 100))
    }

    @Test
    fun resetCooldownAllowsImmediateDetection() {
        val decider = WakeWordDecider()
        assertNotNull(decider.evaluateFinal(words("jarvis" to 1f), 0.5f, 0))
        decider.resetCooldown()
        assertNotNull(decider.evaluateFinal(words("jarvis" to 1f), 0.5f, 10))
    }

    @Test
    fun strictSensitivityRequiresFullPhrase() {
        val decider = WakeWordDecider()
        assertNull(decider.evaluateFinal(words("jarvis" to 1f), sensitivity = 0.2f, nowMs = 0))
        assertNull(decider.evaluateFinal(words("jarvis" to 1f, "hey" to 1f), sensitivity = 0.2f, nowMs = 0))
        assertNotNull(decider.evaluateFinal(words("hey" to 1f, "jarvis" to 1f), sensitivity = 0.2f, nowMs = 0))
    }

    @Test
    fun higherSensitivityAcceptsKeywordAlone() {
        assertNotNull(WakeWordDecider().evaluateFinal(words("jarvis" to 1f), sensitivity = 0.5f, nowMs = 0))
        assertTrue(WakeWordDecider.requiresFullPhrase(0f))
        assertFalse(WakeWordDecider.requiresFullPhrase(0.5f))
    }

    @Test
    fun loudnessFloorScalesWithSensitivity() {
        assertEquals(300.0, WakeWordDecider.minimumRms(0f), 0.001)
        assertEquals(0.0, WakeWordDecider.minimumRms(1f), 0.001)
        val decider = WakeWordDecider()
        val hit = words("hey" to 1f, "jarvis" to 1f)
        assertNull("quiet speech rejected when strict", decider.evaluateFinal(hit, 0.2f, 0, peakRms = 100.0))
        assertNotNull("same speech accepted when lenient", decider.evaluateFinal(hit, 0.9f, 0, peakRms = 100.0))
        assertFalse(WakeWordDecider().evaluatePartial("jarvis", 0.9f, 0, peakRms = 10.0))
    }

    @Test
    fun rmsOfFrame() {
        assertEquals(0.0, WakeWordDecider.rms(ShortArray(4), 4), 0.0)
        assertEquals(100.0, WakeWordDecider.rms(shortArrayOf(100, -100, 100, -100), 4), 0.001)
        assertEquals(0.0, WakeWordDecider.rms(ShortArray(0), 0), 0.0)
    }

    @Test
    fun restartCooldownBlocksUntilWindowPasses() {
        val decider = WakeWordDecider(cooldownMs = 1_000)
        decider.restartCooldown(5_000)
        assertNull(decider.evaluateFinal(words("jarvis" to 1f), 0.5f, 5_500))
        assertNotNull(decider.evaluateFinal(words("jarvis" to 1f), 0.5f, 6_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankKeywordRejected() {
        WakeWordDecider(keyword = "  ")
    }
}
