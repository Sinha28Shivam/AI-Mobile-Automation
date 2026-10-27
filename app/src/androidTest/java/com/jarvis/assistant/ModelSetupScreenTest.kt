package com.jarvis.assistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.ai.ModelState
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceStateRepository
import com.jarvis.assistant.ui.ModelSetupScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * (e) Model setup UI states. The stateless screen is rendered with each [ModelState] so the
 * "Not installed" path is covered even on a device that already has the model installed; the
 * app-level test checks that the real installation state is reflected without changing it.
 */
@RunWith(AndroidJUnit4::class)
class ModelSetupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val spec = LocalModelManager.VOSK_SMALL_EN_US

    private fun render(state: ModelState, calls: MutableList<String> = mutableListOf()) {
        compose.setContent {
            ModelSetupScreen(
                spec = spec,
                state = state,
                onDownload = { calls += "download" },
                onImport = { calls += "import" },
                onCancel = { calls += "cancel" },
                onDelete = { calls += "delete" },
                onBack = { calls += "back" },
            )
        }
    }

    @Test
    fun missingShowsNotInstalledWithDownloadAndImport() {
        val calls = mutableListOf<String>()
        render(ModelState.Missing, calls)
        compose.onNodeWithText("Status: Not installed").assertIsDisplayed()
        compose.onNodeWithText("Import zip from this phone").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Download (", substring = true).performScrollTo().performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test
    fun downloadingShowsProgressAndCancel() {
        val calls = mutableListOf<String>()
        render(ModelState.Downloading(0.42f), calls)
        compose.onNodeWithText("Status: Downloading 42%").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        assertEquals(0, compose.onAllNodesWithTextCount("Import zip from this phone"))
    }

    @Test
    fun verifyingShowsInstalling() {
        render(ModelState.Verifying)
        compose.onNodeWithText("Status: Verifying and installing", substring = true).assertIsDisplayed()
    }

    @Test
    fun readyOffersDeleteBehindConfirmation() {
        val calls = mutableListOf<String>()
        render(ModelState.Ready(40L * 1024 * 1024), calls)
        compose.onNodeWithText("Status: Ready", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Delete model").performScrollTo().performClick()
        compose.onNodeWithText("Delete speech model?").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun errorShowsMessageAndRetry() {
        render(ModelState.Error("Checksum mismatch"))
        compose.onNodeWithText("Status: Error: Checksum mismatch").assertIsDisplayed()
        compose.onNodeWithText("Import zip from this phone").performScrollTo().assertIsEnabled()
    }
}

/** Home screen reflects the device's real model installation state (read-only). */
@RunWith(AndroidJUnit4::class)
class HomeModelStateTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun homeReflectsInstalledModelState() {
        TestSupport.wakeAndUnlock()
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(5_000) { !ServiceStateRepository.state.value.isActive }
        ServiceStateRepository.reset()
        val originalOnboarding = TestSupport.onboardingDone()
        TestSupport.setOnboardingDone(true)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val manager = LocalModelManager.get(TestSupport.targetContext)
            // The manager refreshes asynchronously at creation; wait for it to settle.
            TestSupport.waitUntil(10_000) { manager.state.value !is ModelState.Missing || !TestSupport.isModelInstalled() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTextCount("Start Listening") > 0 }

            if (TestSupport.isModelInstalled()) {
                compose.onNodeWithText("Start Listening").assertIsEnabled()
                assertEquals(0, compose.onAllNodesWithTextCount("Set up speech model"))
            } else {
                compose.onNodeWithText("Start Listening").assertIsNotEnabled()
                compose.onNodeWithText("Set up speech model").performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Status: Not installed") > 0 }
                compose.onNodeWithText("Status: Not installed").assertIsDisplayed()
            }
        } finally {
            scenario.close()
            TestSupport.setOnboardingDone(originalOnboarding)
        }
    }
}
