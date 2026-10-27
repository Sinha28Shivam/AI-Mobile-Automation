package com.jarvis.assistant.commands

import com.jarvis.assistant.tools.AppLaunchOutcome
import com.jarvis.assistant.tools.AppLauncher
import com.jarvis.assistant.tools.BatteryStatus
import com.jarvis.assistant.tools.DeviceInfoTool
import com.jarvis.assistant.tools.NotificationReader
import com.jarvis.assistant.tools.NotificationSummary
import com.jarvis.assistant.tools.ReminderTool
import com.jarvis.assistant.tools.SettingsNavigationTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {

    private class FakeLauncher : AppLauncher {
        val launched = mutableListOf<String>()
        var outcomeFor: (String) -> AppLaunchOutcome = { AppLaunchOutcome.Launched(it) }
        override suspend fun launch(appName: String) = outcomeFor(appName).also {
            if (it is AppLaunchOutcome.Launched) launched += it.label
        }
    }

    private class FakeDevice(var battery: BatteryStatus? = BatteryStatus(80, false)) : DeviceInfoTool {
        override fun batteryStatus() = battery
        override fun currentTimeText() = "3:45 PM"
    }

    private class FakeSettings(var succeed: Boolean = true) : SettingsNavigationTool {
        val opened = mutableListOf<SettingsDestination>()
        override fun open(destination: SettingsDestination): Boolean {
            if (succeed) opened += destination
            return succeed
        }
    }

    private class FakeNotifications(var enabled: Boolean = false, var item: NotificationSummary? = null) : NotificationReader {
        override fun isAccessEnabled() = enabled
        override fun latest() = item
    }

    private class FakeReminders : ReminderTool {
        val scheduled = mutableListOf<Pair<String, Int>>()
        override fun schedule(title: String, delayMinutes: Int): Boolean {
            scheduled += title to delayMinutes
            return true
        }
    }

    private val launcher = FakeLauncher()
    private val device = FakeDevice()
    private val settings = FakeSettings()
    private val notifications = FakeNotifications()
    private val reminders = FakeReminders()

    private fun router(ai: (suspend (String) -> String?)? = null) =
        CommandRouter(launcher, device, settings, notifications, reminders, ai, aiTimeoutMillis = 1_000)

    @Test fun stopFlagsStopRequested() = runTest {
        val r = router().route("Stop listening")
        assertTrue(r.success)
        assertTrue(r.stopRequested)
        assertFalse(router().route("what time is it").stopRequested)
    }

    @Test fun repeatReturnsLastResponse() = runTest {
        val router = router()
        assertFalse(router.route("repeat that").success)
        val time = router.route("what time is it")
        assertEquals("It's 3:45 PM.", time.spokenText)
        val rep = router.route("repeat that")
        assertTrue(rep.success)
        assertEquals(time.spokenText, rep.spokenText)
        assertEquals(time.spokenText, router.route("say that again").spokenText)
    }

    @Test fun basicToolsWorkWithoutAi() = runTest {
        val router = router()
        assertEquals("Opening youtube.", router.route("open YouTube").spokenText)
        assertEquals(listOf("youtube"), launcher.launched)
        assertEquals("Battery is at 80 percent.", router.route("battery").spokenText)
        device.battery = BatteryStatus(55, true)
        assertEquals("Battery is at 55 percent and charging.", router.route("battery level").spokenText)
        val wifi = router.route("open wi-fi settings")
        assertTrue(wifi.success)
        assertEquals("Opening Wi-Fi settings.", wifi.spokenText)
        assertEquals(listOf(SettingsDestination.WIFI), settings.opened)
    }

    @Test fun failuresAreReportedHonestly() = runTest {
        settings.succeed = false
        val r = router().route("bluetooth settings")
        assertFalse(r.success)
        assertFalse(r.spokenText.startsWith("Opening"))
        launcher.outcomeFor = { AppLaunchOutcome.NotFound }
        assertEquals("I couldn't find an app called telegram.", router().route("open telegram").spokenText)
        launcher.outcomeFor = { AppLaunchOutcome.Failed("Camera") }
        assertFalse(router().route("open camera").success)
        device.battery = null
        assertFalse(router().route("battery").success)
    }

    @Test fun ambiguousAppThenChoice() = runTest {
        launcher.outcomeFor = { name ->
            if (name == "google") AppLaunchOutcome.Ambiguous(listOf("Google Maps", "Google Keep"))
            else AppLaunchOutcome.Launched(name)
        }
        val router = router()
        val r = router.route("open google")
        assertTrue(r is CommandResult.Ambiguous)
        assertEquals(listOf("Google Maps", "Google Keep"), (r as CommandResult.Ambiguous).options)
        val pick = router.route("google maps")
        assertTrue(pick.success)
        assertEquals(listOf("Google Maps"), launcher.launched)
    }

    @Test fun notificationAccessOffOffersSettings() = runTest {
        val router = router()
        val r = router.route("read my notifications")
        assertTrue(r is CommandResult.NeedsConfirmation)
        assertTrue(r.spokenText.contains("Notification access is off"))
        val yes = router.route("yes")
        assertTrue(yes.success)
        assertEquals(listOf(SettingsDestination.NOTIFICATION_ACCESS), settings.opened)
    }

    @Test fun notificationRead() = runTest {
        notifications.enabled = true
        assertEquals("You have no new notifications.", router().route("read my notifications").spokenText)
        notifications.item = NotificationSummary("WhatsApp", "Alice", "Hi")
        assertEquals("Latest notification from WhatsApp. Alice: Hi", router().route("read my notifications").spokenText)
    }

    @Test fun reminderScheduled() = runTest {
        val r = router().route("remind me to call mom in 90 minutes")
        assertTrue(r.success)
        assertEquals(listOf("call mom" to 90), reminders.scheduled)
        assertEquals("Okay, I'll remind you to call mom in 1 hour and 30 minutes.", r.spokenText)
    }

    @Test fun aiAbsentFallsBackPolitely() = runTest {
        val r = router(ai = null).route("play some jazz")
        assertFalse(r.success)
        assertEquals(CommandRouter.NOT_UNDERSTOOD, r.spokenText)
    }

    @Test fun aiValidOutputIsExecuted() = runTest {
        var calls = 0
        val router = router { calls++; """{"tool":"open_app","args":{"app_name":"Spotify"}}""" }
        val r = router.route("I feel like listening to music on that green app")
        assertTrue(r.success)
        assertEquals(listOf("Spotify"), launcher.launched)
        assertEquals(1, calls)
        router.route("what time is it")
        assertEquals("AI must not be used when the parser succeeds", 1, calls)
    }

    @Test fun aiInvalidOutputRejected() = runTest {
        val outputs = listOf(
            """{"tool":"shell","args":{"cmd":"rm -rf /"}}""",
            "sure! I'll open it",
            """{"tool":"create_reminder","args":{"title":"x","delay_minutes":5000}}""",
            """{"tool":"stop_listening"}""",
            null,
        )
        for (o in outputs) {
            val r = router { o }.route("do something weird")
            assertFalse("output $o", r.success)
            assertEquals(CommandRouter.NOT_UNDERSTOOD, r.spokenText)
        }
        assertTrue(launcher.launched.isEmpty())
        assertTrue(reminders.scheduled.isEmpty())
    }

    @Test fun aiThrowingOrSlowFallsBack() = runTest {
        val throwing = router { throw IllegalStateException("model crashed") }.route("do something weird")
        assertEquals(CommandRouter.NOT_UNDERSTOOD, throwing.spokenText)
        val slow = router { delay(10_000); """{"tool":"get_time"}""" }.route("do something weird")
        assertEquals(CommandRouter.NOT_UNDERSTOOD, slow.spokenText)
    }

    @Test fun stopWinsOverPendingConfirmation() = runTest {
        val router = router()
        router.route("read my notifications")
        val stop = router.route("stop")
        assertTrue(stop.stopRequested)
        router.route("yes")
        assertTrue(settings.opened.isEmpty())
    }

    @Test fun describeDelay() {
        assertEquals("1 minute", CommandRouter.describeDelay(1))
        assertEquals("2 hours", CommandRouter.describeDelay(120))
        assertEquals("1 hour and 1 minute", CommandRouter.describeDelay(61))
    }
}
