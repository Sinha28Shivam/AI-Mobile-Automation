package com.jarvis.assistant.commands

import com.jarvis.assistant.commands.JarvisCommand.CreateReminder
import com.jarvis.assistant.commands.JarvisCommand.OpenApp
import com.jarvis.assistant.commands.JarvisCommand.OpenSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser()

    private fun p(text: String) = parser.parse(text)
    private fun assertUnknown(text: String) =
        assertTrue("expected Unknown for '$text' but was ${p(text)}", p(text) is JarvisCommand.Unknown)

    @Test fun specExamples() {
        assertEquals(OpenApp("youtube"), p("Open YouTube."))
        assertEquals(OpenApp("whatsapp"), p("Open WhatsApp."))
        assertEquals(JarvisCommand.GetBattery, p("What is my battery level?"))
        assertEquals(JarvisCommand.GetTime, p("What time is it?"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("Open Wi-Fi settings."))
        assertEquals(JarvisCommand.StopListening, p("Stop listening."))
        assertEquals(JarvisCommand.Repeat, p("Repeat that."))
    }

    @Test fun openAppVariants() {
        assertEquals(OpenApp("youtube"), p("launch YouTube"))
        assertEquals(OpenApp("spotify"), p("Start Spotify"))
        assertEquals(OpenApp("google maps"), p("open google maps app"))
        assertEquals(OpenApp("camera"), p("Hey Jarvis, can you please open the camera?"))
        assertEquals(OpenApp("whatsapp"), p("jarvis open whatsapp please"))
    }

    @Test fun batteryVariants() {
        listOf("battery", "how much battery", "How much battery do I have?", "what's my battery",
            "battery level", "check the battery", "Is my phone charging?").forEach {
            assertEquals(it, JarvisCommand.GetBattery, p(it))
        }
    }

    @Test fun timeVariants() {
        listOf("what's the time", "What is the time?", "tell me the time", "time", "Jarvis, what time is it now?")
            .forEach { assertEquals(it, JarvisCommand.GetTime, p(it)) }
    }

    @Test fun settingsVariants() {
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("wifi settings"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("open wi-fi settings"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("open wifi"))
        assertEquals(OpenSettings(SettingsDestination.BLUETOOTH), p("Bluetooth settings"))
        assertEquals(OpenSettings(SettingsDestination.BLUETOOTH), p("open bluetooth"))
        assertEquals(OpenSettings(SettingsDestination.SOUND), p("open sound settings"))
        assertEquals(OpenSettings(SettingsDestination.DISPLAY), p("go to display settings"))
        assertEquals(OpenSettings(SettingsDestination.BATTERY), p("open battery settings"))
        assertEquals(OpenSettings(SettingsDestination.BATTERY), p("battery saver"))
        assertEquals(OpenSettings(SettingsDestination.NOTIFICATIONS), p("notification settings"))
        assertEquals(OpenSettings(SettingsDestination.NOTIFICATION_ACCESS), p("open notification access settings"))
        assertEquals(OpenSettings(SettingsDestination.APP_SETTINGS), p("open app settings"))
        assertEquals(OpenSettings(SettingsDestination.MAIN), p("open settings"))
        assertEquals(OpenSettings(SettingsDestination.MAIN), p("settings"))
    }

    @Test fun stopAndRepeatVariants() {
        assertEquals(JarvisCommand.StopListening, p("stop"))
        assertEquals(JarvisCommand.StopListening, p("Jarvis stop!"))
        assertEquals(JarvisCommand.StopListening, p("please stop listening now"))
        assertEquals(JarvisCommand.Repeat, p("say that again"))
        assertEquals(JarvisCommand.Repeat, p("repeat"))
    }

    @Test fun notifications() {
        assertEquals(JarvisCommand.ReadLatestNotification, p("read my notifications"))
        assertEquals(JarvisCommand.ReadLatestNotification, p("Read my latest notification"))
        assertEquals(JarvisCommand.ReadLatestNotification, p("any new notifications?"))
    }

    @Test fun reminders() {
        assertEquals(CreateReminder("call mom", 10), p("Remind me to call mom in 10 minutes"))
        assertEquals(CreateReminder("drink water", 120), p("remind me to drink water in 2 hours"))
        assertEquals(CreateReminder("check the oven", 1), p("Remind me in a minute to check the oven"))
        assertEquals(CreateReminder("stretch", 5), p("set a reminder to stretch in five minutes"))
        assertEquals(CreateReminder("x", 1440), p("remind me to x in 24 hours"))
    }

    @Test fun invalidRemindersAreUnknown() {
        assertUnknown("remind me to call mom in 0 minutes")
        assertUnknown("remind me to call mom in 25 hours")
        assertUnknown("remind me to call mom")
        assertUnknown("remind me in 5 minutes")
    }

    @Test fun unknownAndEmpty() {
        assertUnknown("")
        assertUnknown("   ")
        assertUnknown("...!?")
        assertUnknown("jarvis")
        assertUnknown("hey jarvis")
        assertUnknown("tell me a joke")
        assertUnknown("what is the meaning of life")
        assertUnknown("open")
        assertUnknown("x".repeat(600))
    }

    @Test fun lowConfidenceIsUnknown() {
        assertTrue(parser.parse("open youtube", confidence = 0.1f) is JarvisCommand.Unknown)
        assertEquals(OpenApp("youtube"), parser.parse("open youtube", confidence = 0.9f))
    }

    @Test fun unknownKeepsOriginalText() {
        assertEquals(JarvisCommand.Unknown("Tell me a joke!"), p("Tell me a joke!"))
    }

    @Test fun normalization() {
        assertEquals("open youtube", CommandParser.normalize("Hey Jarvis, please open YouTube!"))
        assertEquals("whats the time", CommandParser.normalize("Jarvis, can you please... what's the time?"))
        assertEquals("open wifi settings", CommandParser.normalize("Open Wi-Fi settings."))
    }
}
