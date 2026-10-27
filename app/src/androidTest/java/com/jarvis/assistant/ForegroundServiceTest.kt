package com.jarvis.assistant

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.ServiceStateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * (c) Foreground-service start/stop through the public companion API and notification actions.
 *
 * The outcome depends on whether the speech model is installed on the device under test; the
 * suite never installs or deletes it. Without a model the service must report the honest
 * "Speech model not installed" error and tear down its notification, never claim Listening.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ForegroundServiceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestSupport.listeningPermissions())

    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalOnboardingDone = false

    @Before
    fun setUp() {
        TestSupport.wakeAndUnlock()
        stopAndAwait()
        ServiceStateRepository.reset()
        originalOnboardingDone = TestSupport.onboardingDone()
        TestSupport.setOnboardingDone(true)
        // Start from a visible activity, as the app does; background FGS starts are restricted on 12+.
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        stopAndAwait()
        ServiceStateRepository.reset()
        scenario?.close()
        TestSupport.setOnboardingDone(originalOnboardingDone)
    }

    @Test
    fun startPostsForegroundNotificationOrReportsMissingModel_thenStopRemovesIt() {
        val modelInstalled = TestSupport.isModelInstalled()
        val seenStates = mutableListOf<ServiceState>()
        var seenNotification: StatusBarNotification? = null

        JarvisForegroundService.start(TestSupport.targetContext)

        val settled = TestSupport.waitUntil(MODEL_LOAD_TIMEOUT_MS) {
            val s = ServiceStateRepository.state.value
            if (seenStates.lastOrNull() != s) seenStates += s
            if (seenNotification == null) seenNotification = listeningNotification()
            s == ServiceState.Listening || s is ServiceState.Error || s == ServiceState.PermissionRequired
        }
        assertTrue("service never settled; states seen: $seenStates", settled)
        val state = ServiceStateRepository.state.value

        if (modelInstalled) {
            if (state is ServiceState.Error) {
                // A real device may legitimately fail (e.g. mic held by a call). It must be honest, not "Listening".
                assertNotEquals(JarvisForegroundService.MODEL_MISSING_MESSAGE, state.message)
                assertTrue("notification must be removed after an error", awaitNoListeningNotification())
                return
            }
            assertEquals(ServiceState.Listening, state)
            val sbn = listeningNotification()
            assertNotNull("foreground notification missing on ${TestSupport.LISTENING_CHANNEL_ID}", sbn)
            assertTrue(TestSupport.isForegroundServiceNotification(sbn!!))
            assertTrue("listening notification must be ongoing", sbn.isOngoing)
            assertActions(sbn.notification)

            JarvisForegroundService.stop(TestSupport.targetContext)
            assertTrue("state did not return to Stopped", TestSupport.waitUntil(STOP_TIMEOUT_MS) {
                ServiceStateRepository.state.value == ServiceState.Stopped
            })
            assertTrue("notification not removed after stop", awaitNoListeningNotification())
        } else {
            assertTrue("expected Error, was $state", state is ServiceState.Error)
            assertEquals(JarvisForegroundService.MODEL_MISSING_MESSAGE, (state as ServiceState.Error).message)
            assertFalse("must never claim Listening without a model", ServiceState.Listening in seenStates)
            assertTrue("notification must be removed after the model-missing error", awaitNoListeningNotification())
            // The notification is normally visible only for milliseconds here; validate it if we caught it.
            seenNotification?.let {
                assertTrue(TestSupport.isForegroundServiceNotification(it))
                assertActions(it.notification)
            }
        }
    }

    @Test
    fun stopActionInNotificationStopsService() {
        org.junit.Assume.assumeTrue("needs an installed speech model", TestSupport.isModelInstalled())
        JarvisForegroundService.start(TestSupport.targetContext)
        assertTrue(TestSupport.waitUntil(MODEL_LOAD_TIMEOUT_MS) {
            val s = ServiceStateRepository.state.value
            s == ServiceState.Listening || s is ServiceState.Error
        })
        org.junit.Assume.assumeTrue(
            "service could not listen on this device: ${ServiceStateRepository.state.value}",
            ServiceStateRepository.state.value == ServiceState.Listening,
        )
        val sbn = listeningNotification()
        assertNotNull(sbn)
        val stop = sbn!!.notification.actions.first { it.title.toString().equals("Stop", ignoreCase = true) }
        stop.actionIntent.send()

        assertTrue(TestSupport.waitUntil(STOP_TIMEOUT_MS) { ServiceStateRepository.state.value == ServiceState.Stopped })
        assertTrue(awaitNoListeningNotification())
    }

    @Test
    fun stopWhenNotRunningIsHarmless() {
        JarvisForegroundService.stop(TestSupport.targetContext)
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(1_000) { false }
        assertFalse(ServiceStateRepository.state.value.isActive)
        assertTrue(awaitNoListeningNotification())
    }

    private fun assertActions(notification: Notification) {
        val titles = notification.actions.orEmpty().map { it.title.toString() }
        assertTrue("Stop action missing: $titles", titles.any { it.equals("Stop", ignoreCase = true) })
    }

    private fun listeningNotification(): StatusBarNotification? =
        TestSupport.channelNotifications(TestSupport.LISTENING_CHANNEL_ID).firstOrNull()

    private fun awaitNoListeningNotification(): Boolean =
        TestSupport.waitUntil(STOP_TIMEOUT_MS) { listeningNotification() == null }

    private fun stopAndAwait() {
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(STOP_TIMEOUT_MS) {
            !ServiceStateRepository.state.value.isActive && listeningNotification() == null
        }
    }

    private companion object {
        // Loading the Vosk model can take several seconds on mid-range phones.
        const val MODEL_LOAD_TIMEOUT_MS = 30_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
