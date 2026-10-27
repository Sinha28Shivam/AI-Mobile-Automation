package com.jarvis.assistant

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.ServiceStateRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * (b) Microphone permission, grant path. Granting is done with [GrantPermissionRule].
 *
 * Revoking a runtime permission from inside the instrumented process kills that process, so the
 * real-dialog denial path lives in [PermissionDenialDialogTest], which runs only when the
 * permission has been revoked beforehand (see scripts/run-permission-denial-test.ps1).
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestSupport.listeningPermissions())

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalOnboardingDone = false

    @Before
    fun setUp() {
        TestSupport.wakeAndUnlock()
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(5_000) { !ServiceStateRepository.state.value.isActive }
        ServiceStateRepository.reset()
        originalOnboardingDone = TestSupport.onboardingDone()
        TestSupport.setOnboardingDone(true)
    }

    @After
    fun tearDown() {
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(5_000) { !ServiceStateRepository.state.value.isActive }
        ServiceStateRepository.reset()
        scenario?.close()
        TestSupport.setOnboardingDone(originalOnboardingDone)
    }

    @Test
    fun grantedPermissionsAreVisibleToApp() {
        assertTrue(PermissionManager.hasMicrophonePermission(TestSupport.targetContext))
        assertTrue(PermissionManager.hasNotificationPermission(TestSupport.targetContext))
    }

    @Test
    fun withPermissionGranted_serviceNeverReportsPermissionRequired() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        JarvisForegroundService.start(TestSupport.targetContext)
        TestSupport.waitUntil(30_000) {
            val s = ServiceStateRepository.state.value
            s == ServiceState.Listening || s is ServiceState.Error || s == ServiceState.PermissionRequired
        }
        val state = ServiceStateRepository.state.value
        assertTrue("unexpected $state", state != ServiceState.PermissionRequired)
    }

    /** Denial outcome as rendered by the home screen (the state the dialog callback produces). */
    @Test
    fun permissionRequiredStateShowsRecoveryGuidance() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("JARVIS") > 0 }
        scenario!!.onActivity { ServiceStateRepository.update(ServiceState.PermissionRequired) }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount("Status: ${ServiceState.PermissionRequired.label}") > 0
        }
        compose.onNodeWithText("Open app settings").performScrollTo()
        assertTrue(compose.onAllNodesWithTextCount("Stop Listening") == 0)
    }
}
