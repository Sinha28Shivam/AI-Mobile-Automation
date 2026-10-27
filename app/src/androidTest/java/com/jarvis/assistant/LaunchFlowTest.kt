package com.jarvis.assistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceStateRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** (a) Cold launch shows onboarding first, then the home screen after "Got it". */
@RunWith(AndroidJUnit4::class)
class LaunchFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var originalOnboardingDone = false
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        TestSupport.wakeAndUnlock()
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(5_000) { !ServiceStateRepository.state.value.isActive }
        // State is process-wide; clear any Error/PermissionRequired left by an earlier test.
        ServiceStateRepository.reset()
        originalOnboardingDone = TestSupport.onboardingDone()
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestSupport.setOnboardingDone(originalOnboardingDone)
    }

    @Test
    fun onboardingThenHome() {
        TestSupport.setOnboardingDone(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTextCount("Welcome to JARVIS") > 0
        }
        compose.onNodeWithText("Welcome to JARVIS").assertIsDisplayed()
        // Onboarding explains, never requests: no permission dialog may appear yet.
        compose.onNodeWithText("Got it").performScrollTo().performClick()

        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("JARVIS") > 0 }
        compose.onNodeWithText("JARVIS").assertIsDisplayed()
        compose.onNodeWithText("All processing on-device").assertIsDisplayed()
        compose.onNodeWithText("Start Listening").assertExists()
        compose.onNodeWithText("Status: Stopped").assertExists()
        assertTrue("onboarding flag must persist", TestSupport.onboardingDone())
    }

    @Test
    fun relaunchSkipsOnboarding() {
        TestSupport.setOnboardingDone(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("Start Listening") > 0 }
        assertTrue(compose.onAllNodesWithTextCount("Welcome to JARVIS") == 0)
    }
}
