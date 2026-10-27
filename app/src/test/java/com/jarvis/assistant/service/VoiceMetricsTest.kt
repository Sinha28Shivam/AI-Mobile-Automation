package com.jarvis.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMetricsTest {

    @Test
    fun percentilesUseNearestRank() {
        val stats = LatencyStats.of((1L..100L).toList())
        assertEquals(100, stats.count)
        assertEquals(50, stats.averageMs)
        assertEquals(50, stats.medianMs)
        assertEquals(95, stats.p95Ms)
        assertEquals(LatencyStats.Empty, LatencyStats.of(emptyList()))
        assertEquals(LatencyStats(1, 7, 7, 7), LatencyStats.of(listOf(7)))
    }

    @Test
    fun countersAndWindow() {
        val m = VoiceMetrics(window = 3)
        listOf(10L, 20L, 30L, 1_000L).forEach(m::recordWakeDetected)
        m.recordCommandSession()
        m.recordModelLoad(1234)
        val s = m.snapshot.value
        assertEquals(4, s.wakeDetections)
        assertEquals(3, s.wakeLatency.count)
        assertEquals(30, s.wakeLatency.medianMs)
        assertEquals(1, s.commandSessions)
        assertEquals(1234L, s.lastModelLoadMs)
        m.reset()
        assertEquals(VoiceMetricsSnapshot(), m.snapshot.value)
    }
}
