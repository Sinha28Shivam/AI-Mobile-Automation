package com.jarvis.assistant

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.ServiceStateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * (b) Microphone denial path through the real system dialog (AOSP and MIUI permission UIs).
 *
 * Revoking RECORD_AUDIO from inside the test process would kill it, so these tests are skipped
 * (assumption failure) unless the permission was revoked before the run:
 *   scripts\run-permission-denial-test.ps1
 */
@RunWith(AndroidJUnit4::class)
class PermissionDenialDialogTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalOnboardingDone = false

    @Before
    fun setUp() {
        assumeFalse("RECORD_AUDIO is granted; run scripts/run-permission-denial-test.ps1", TestSupport.hasMicPermission())
        TestSupport.wakeAndUnlock()
        ServiceStateRepository.reset()
        originalOnboardingDone = TestSupport.onboardingDone()
        TestSupport.setOnboardingDone(true)
    }

    @After
    fun tearDown() {
        scenario?.close()
        ServiceStateRepository.reset()
        TestSupport.setOnboardingDone(originalOnboardingDone)
    }

    @Test
    fun denyingMicrophoneShowsPermissionRequired() {
        assumeTrue("Start is disabled until a speech model is installed", TestSupport.isModelInstalled())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("Start Listening") > 0 }
        compose.onNodeWithText("Start Listening").performClick()

        // Android 13 asks for microphone and notifications in sequence; deny each prompt.
        var denied = 0
        while (true) {
            val button = findDenyButton(timeoutMs = if (denied == 0) 10_000 else 3_000) ?: break
            button.click()
            denied++
            TestSupport.device.waitForIdle(1_000)
        }
        assertTrue("no permission dialog appeared", denied > 0)

        compose.waitUntil(5_000) { ServiceStateRepository.state.value == ServiceState.PermissionRequired }
        assertEquals(ServiceState.PermissionRequired, ServiceStateRepository.state.value)
        compose.onNodeWithText("Open app settings").performScrollTo()
        assertFalse(TestSupport.hasMicPermission())
        assertFalse(
            "must not post the listening notification without permission",
            TestSupport.channelNotifications(TestSupport.LISTENING_CHANNEL_ID).isNotEmpty(),
        )
    }

    @Test
    fun serviceStartWithoutPermissionReportsPermissionRequired() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        JarvisForegroundService.start(TestSupport.targetContext)
        val ok = TestSupport.waitUntil(5_000) { ServiceStateRepository.state.value == ServiceState.PermissionRequired }
        assertEquals("state=${ServiceStateRepository.state.value}", true, ok)
        TestSupport.waitUntil(1_000) { false }
        assertFalse(TestSupport.channelNotifications(TestSupport.LISTENING_CHANNEL_ID).isNotEmpty())
    }

    private fun findDenyButton(timeoutMs: Long): UiObject2? {
        val device = TestSupport.device
        // AOSP: permission_deny_button / permission_deny_and_dont_ask_again_button. MIUI: com.lbe.security.miui.
        val byRes = By.res(Pattern.compile(".*:id/permission_deny(_and_dont_ask_again)?_button"))
        val byText = By.text(Pattern.compile("(?i)(deny|don.t allow|reject|refuse)"))
        device.wait(Until.hasObject(By.res(Pattern.compile(".*permission.*|.*lbe.*"))), timeoutMs)
        return device.findObject(byRes) ?: device.wait(Until.findObject(byText), 1_000)
    }
}
