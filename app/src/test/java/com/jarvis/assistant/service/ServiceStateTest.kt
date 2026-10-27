package com.jarvis.assistant.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServiceStateTest {

    @Before
    fun setUp() = ServiceStateRepository.reset()

    @After
    fun tearDown() = ServiceStateRepository.reset()

    @Test
    fun initialStateIsStopped() {
        assertEquals(ServiceState.Stopped, ServiceStateRepository.state.value)
    }

    @Test
    fun activeFlagMatchesPipelineStates() {
        listOf(ServiceState.Listening, ServiceState.HeardWakeWord, ServiceState.Processing, ServiceState.Speaking)
            .forEach { assertTrue("$it should be active", it.isActive) }
        listOf(ServiceState.Stopped, ServiceState.PermissionRequired, ServiceState.Error("x"))
            .forEach { assertFalse("$it should be inactive", it.isActive) }
    }

    @Test
    fun startThenStopTransitions() {
        ServiceStateRepository.update(ServiceState.Listening)
        assertEquals(ServiceState.Listening, ServiceStateRepository.state.value)
        ServiceStateRepository.update(ServiceState.Stopped)
        assertEquals(ServiceState.Stopped, ServiceStateRepository.state.value)
    }

    @Test
    fun updateIfActiveIgnoredWhenStopped() {
        assertFalse(ServiceStateRepository.updateIfActive(ServiceState.Processing))
        assertEquals(ServiceState.Stopped, ServiceStateRepository.state.value)
    }

    @Test
    fun updateIfActiveAppliesWhenListening() {
        ServiceStateRepository.update(ServiceState.Listening)
        assertTrue(ServiceStateRepository.updateIfActive(ServiceState.HeardWakeWord))
        assertEquals(ServiceState.HeardWakeWord, ServiceStateRepository.state.value)
    }

    @Test
    fun errorLabelIncludesMessage() {
        assertEquals("Error: Microphone unavailable", ServiceState.Error("Microphone unavailable").label)
    }
}
