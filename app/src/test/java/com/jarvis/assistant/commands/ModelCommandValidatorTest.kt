package com.jarvis.assistant.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCommandValidatorTest {

    private val v = ModelCommandValidator()

    @Test fun validCommands() {
        assertEquals(JarvisCommand.OpenApp("YouTube"), v.validate("""{"tool":"open_app","args":{"app_name":"YouTube"}}"""))
        assertEquals(JarvisCommand.GetBattery, v.validate("""{"tool":"get_battery","args":{}}"""))
        assertEquals(JarvisCommand.GetTime, v.validate("""{"tool":"get_time"}"""))
        assertEquals(
            JarvisCommand.OpenSettings(SettingsDestination.WIFI),
            v.validate(""" { "tool" : "open_settings", "args" : { "destination" : "wifi" } } """),
        )
        assertEquals(
            JarvisCommand.CreateReminder("call mom", 15),
            v.validate("""{"tool":"create_reminder","args":{"title":"call mom","delay_minutes":15}}"""),
        )
        assertEquals(JarvisCommand.ReadLatestNotification, v.validate("""{"tool":"read_latest_notification"}"""))
        assertEquals(JarvisCommand.GetTime, v.validate("```json\n{\"tool\":\"get_time\"}\n```"))
    }

    @Test fun unknownToolsRejected() {
        assertNull(v.validate("""{"tool":"shell","args":{"cmd":"rm -rf /"}}"""))
        assertNull(v.validate("""{"tool":"send_sms","args":{"to":"123","body":"hi"}}"""))
        assertNull(v.validate("""{"tool":"OPEN_APP","args":{"app_name":"x"}}"""))
    }

    @Test fun missingOrExtraArgsRejected() {
        assertNull(v.validate("""{"tool":"open_app","args":{}}"""))
        assertNull(v.validate("""{"tool":"open_app"}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x"}}"""))
        assertNull(v.validate("""{"tool":"get_time","args":{"extra":"1"}}"""))
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":"x","package":"com.evil"}}"""))
        assertNull(v.validate("""{"tool":"get_time","code":"x"}"""))
    }

    @Test fun badTypesRejected() {
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":5}}"""))
        assertNull(v.validate("""{"tool":"open_app","args":"YouTube"}"""))
        assertNull(v.validate("""{"tool":5}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":"10"}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":1.5}}"""))
        assertNull(v.validate("""{"tool":"open_settings","args":{"destination":"developer_options"}}"""))
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":["a"]}}"""))
    }

    @Test fun outOfRangeReminderRejected() {
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":0}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":1441}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":-5}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x","delay_minutes":99999999999}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"  ","delay_minutes":5}}"""))
    }

    @Test fun malformedRejected() {
        assertNull(v.validate(null))
        assertNull(v.validate(""))
        assertNull(v.validate("open youtube"))
        assertNull(v.validate("""{"tool":"get_time""""))
        assertNull(v.validate("""{"tool":"get_time"} extra"""))
        assertNull(v.validate("""{"tool":"get_time","tool":"get_battery"}"""))
        assertNull(v.validate("""[{"tool":"get_time"}]"""))
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":{"x":"y"}}}"""))
    }

    @Test fun oversizeRejected() {
        val big = """{"tool":"open_app","args":{"app_name":"${"a".repeat(2100)}"}}"""
        assertNull(v.validate(big))
    }

    @Test fun injectionStringsRejected() {
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":"YouTube; rm -rf /"}}"""))
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":"$(reboot)"}}"""))
        assertNull(v.validate("""{"tool":"open_app","args":{"app_name":"a\nb"}}"""))
        assertNull(v.validate("""{"tool":"create_reminder","args":{"title":"x\u0000y","delay_minutes":5}}"""))
    }
}
