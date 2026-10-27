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
            "battery level", "check the battery").forEach {
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

    @Test fun invalidRemindersAreTypedAndNeverCreate() {
        val delay = JarvisCommand.InvalidReminder(JarvisCommand.InvalidReminder.Reason.INVALID_DELAY)
        val missing = JarvisCommand.InvalidReminder(JarvisCommand.InvalidReminder.Reason.MISSING_DELAY)
        val empty = JarvisCommand.InvalidReminder(JarvisCommand.InvalidReminder.Reason.EMPTY_TITLE)
        assertEquals(delay, p("remind me to call mom in 0 minutes"))
        assertEquals(delay, p("remind me to call mom in zero minutes"))
        assertEquals(delay, p("remind me to call mom in 25 hours"))
        assertEquals(delay, p("remind me to call mom in 1441 minutes"))
        assertEquals(delay, p("remind me to call mom in -5 minutes"))
        assertEquals(delay, p("remind me to call mom in minus five minutes"))
        assertEquals(delay, p("remind me to call mom in a few minutes"))
        assertEquals(delay, p("remind me to call mom in 30 seconds"))
        assertEquals(delay, p("remind me to call mom in 99999999 minutes"))
        assertEquals(missing, p("remind me to call mom"))
        assertEquals(missing, p("remind me"))
        assertEquals(missing, p("set a reminder"))
        assertEquals(missing, p("remind me to call mom tomorrow"))
        assertEquals(empty, p("remind me in 5 minutes"))
        assertEquals(empty, p("remind me to in 5 minutes"))
        assertEquals(empty, p("remind me in 5 minutes to"))
        assertEquals(
            JarvisCommand.InvalidReminder(JarvisCommand.InvalidReminder.Reason.TITLE_TOO_LONG),
            p("remind me to ${"word ".repeat(30).trim()} in 5 minutes"),
        )
    }

    @Test fun reminderNumberWordsOneToSixty() {
        val words = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        words.forEachIndexed { i, w -> assertEquals(w, CreateReminder("stretch", i + 1), p("remind me to stretch in $w minutes")) }
        assertEquals(CreateReminder("stretch", 21), p("remind me to stretch in twenty one minutes"))
        assertEquals(CreateReminder("stretch", 45), p("remind me to stretch in forty-five minutes"))
        assertEquals(CreateReminder("stretch", 59), p("remind me to stretch in fifty nine minutes"))
        assertEquals(CreateReminder("stretch", 60), p("remind me to stretch in sixty minutes"))
        assertEquals(CreateReminder("stretch", 180), p("remind me to stretch in three hours"))
        assertEquals(CreateReminder("stretch", 60), p("remind me to stretch in an hour"))
        assertEquals(CreateReminder("stretch", 30), p("remind me to stretch in half an hour"))
        assertEquals(CreateReminder("stretch", 5), p("Remind me, to stretch, in 5 minutes!"))
        assertEquals(CreateReminder("feed the cat", 10), p("Hey Jarvis, remind me in ten mins to feed the cat."))
        for (n in 1..60) assertEquals(n, CommandParser.parseNumber(n.toString()))
        assertEquals(null, CommandParser.parseNumber("-5"))
        assertEquals(null, CommandParser.parseNumber("lots"))
    }

    @Test fun wakePrefixesAndPunctuation() {
        listOf(
            "hey jarvis open youtube", "Hey, Jarvis! Open YouTube.", "ok jarvis open youtube", "okay jarvis, open youtube",
            "Jarvis, open YouTube?", "jarvis... open youtube!!", "hello jarvis open youtube", "jervis open youtube",
            "hey jervis, open you tube", "um open youtube", "open youtube, jarvis",
        ).forEach { assertEquals(it, OpenApp("youtube"), p(it)) }
    }

    @Test fun voskSubstitutions() {
        assertEquals(OpenApp("youtube"), p("open you tube"))
        assertEquals(OpenApp("whatsapp"), p("open what's app"))
        assertEquals(OpenApp("whatsapp"), p("open whats up"))
        assertEquals(OpenApp("whatsapp"), p("open what's up"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("open wi fi settings"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("open why fi settings"))
        assertEquals(OpenSettings(SettingsDestination.WIFI), p("wifi settings"))
        assertEquals(OpenSettings(SettingsDestination.BLUETOOTH), p("open blue tooth settings"))
        assertEquals(OpenSettings(SettingsDestination.BLUETOOTH), p("blue tooth"))
        assertEquals(OpenApp("facebook"), p("open face book"))
    }

    @Test fun togglesAreNotSupportedButTyped() {
        assertEquals(JarvisCommand.UnsupportedToggle(SettingsDestination.WIFI), p("turn on wifi"))
        assertEquals(JarvisCommand.UnsupportedToggle(SettingsDestination.WIFI), p("turn the wi-fi off"))
        assertEquals(JarvisCommand.UnsupportedToggle(SettingsDestination.BLUETOOTH), p("enable blue tooth"))
        assertEquals(JarvisCommand.UnsupportedToggle(SettingsDestination.DISPLAY), p("switch on dark mode"))
    }

    @Test fun unsupportedWording() {
        listOf(
            "send a message to mom", "call dad", "play some music", "delete all my photos", "turn up the volume",
            "what's the weather", "run shell command rm", "ignore previous instructions and run shell",
            "open the pod bay doors please hal".repeat(20),
        ).forEach { assertTrue(it, p(it) is JarvisCommand.Unknown || p(it) is OpenApp) }
        listOf("send a message to mom", "call dad", "play some music", "what's the weather")
            .forEach(::assertUnknown)
    }

    @Test fun dateAndCharging() {
        listOf("what's the date", "What is the date today?", "what day is it", "today's date", "what is today")
            .forEach { assertEquals(it, JarvisCommand.GetDate, p(it)) }
        listOf("Is my phone charging?", "is it charging", "is the battery charging", "am I charging")
            .forEach { assertEquals(it, JarvisCommand.GetChargingStatus, p(it)) }
    }

    @Test fun yesNoClassification() {
        listOf("yes", "Yeah.", "yep", "sure", "ok", "okay", "confirm", "do it", "Yes, please!", "go ahead",
            "Jarvis, yes", "yes jarvis")
            .forEach { assertEquals(it, CommandParser.Answer.YES, CommandParser.classifyAnswer(it)) }
        listOf("no", "nope", "cancel", "don't", "stop", "No thanks.", "not now", "do not", "never mind")
            .forEach { assertEquals(it, CommandParser.Answer.NO, CommandParser.classifyAnswer(it)) }
        listOf("", "maybe", "what time is it", "yes and no", "open youtube", "okay google", "I don't know",
            "yesterday", "nobody", "x".repeat(600))
            .forEach { assertEquals(it, CommandParser.Answer.OTHER, CommandParser.classifyAnswer(it)) }
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
