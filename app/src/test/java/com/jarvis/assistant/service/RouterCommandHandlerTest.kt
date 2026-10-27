package com.jarvis.assistant.service

import com.jarvis.assistant.commands.CommandRouter
import com.jarvis.assistant.commands.SettingsDestination
import com.jarvis.assistant.data.LocalHistoryRepository
import com.jarvis.assistant.tools.AppLaunchOutcome
import com.jarvis.assistant.tools.AppLauncher
import com.jarvis.assistant.tools.BatteryStatus
import com.jarvis.assistant.tools.DeviceInfoTool
import com.jarvis.assistant.tools.NotificationReader
import com.jarvis.assistant.tools.NotificationSummary
import com.jarvis.assistant.tools.ReminderTool
import com.jarvis.assistant.tools.SettingsNavigationTool
import com.jarvis.assistant.tools.SettingsOpenOutcome
import com.jarvis.assistant.tts.TtsSpeaker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RouterCommandHandlerTest {

    @get:Rule val folder = TemporaryFolder()

    private var launchOutcome: AppLaunchOutcome = AppLaunchOutcome.Launched("Camera")
    private var settingsOutcome = SettingsOpenOutcome.Opened

    private fun router() = CommandRouter(
        appLauncher = object : AppLauncher {
            override suspend fun launch(appName: String) = launchOutcome
        },
        deviceInfo = object : DeviceInfoTool {
            override fun batteryStatus() = BatteryStatus(50, false)
            override fun currentTimeText() = "3:45 PM"
        },
        settings = object : SettingsNavigationTool {
            override fun open(destination: SettingsDestination) = settingsOutcome == SettingsOpenOutcome.Opened
            override fun openDetailed(destination: SettingsDestination) = settingsOutcome
        },
        notifications = object : NotificationReader {
            override fun isAccessEnabled() = false
            override fun latest(): NotificationSummary? = null
        },
        reminders = object : ReminderTool {
            override fun schedule(title: String, delayMinutes: Int) = true
        },
    )

    private val history by lazy { LocalHistoryRepository(File(folder.root, "h.txt")) }

    @Test fun mapsResultAndSkipsHistoryWhenDisabled() = runTest {
        val handler = RouterCommandHandler(router(), historyEnabled = { false }, history = history)
        val r = handler.handle("what time is it", 0.9f)
        assertEquals("It's 3:45 PM.", r.spokenText)
        assertFalse(r.stopRequested)
        assertTrue(history.entries().isEmpty())
    }

    @Test fun recordsHistoryWhenEnabledAndPropagatesStop() = runTest {
        val handler = RouterCommandHandler(router(), historyEnabled = { true }, history = history)
        val r = handler.handle("stop listening", 0.9f)
        assertTrue(r.stopRequested)
        val e = history.entries().single()
        assertEquals("stop listening", e.command)
        assertEquals(r.spokenText, e.result)
    }

    @Test fun backgroundBlockedLaunchIsReportedHonestly() = runTest {
        launchOutcome = AppLaunchOutcome.NeedsUserTap("Camera")
        settingsOutcome = SettingsOpenOutcome.NeedsUserTap
        val handler = RouterCommandHandler(router(), historyEnabled = { false }, history = null)
        assertEquals(
            "I couldn't open Camera directly; tap the notification to open it.",
            handler.handle("open camera", 0.9f).spokenText,
        )
        assertTrue(handler.handle("open wifi settings", 0.9f).spokenText!!.contains("tap the notification"))
    }

    @Test fun ttsUnavailablePostsNonFatalNotice() = runTest {
        var notice: String? = null
        val speaker = TtsSpeaker(output = null, onUnavailable = { notice = it }, onAvailable = { notice = null })
        speaker.speak("hello")
        speaker.close()
        assertEquals(TtsSpeaker.MSG_INIT_FAILED, notice)
    }
}
