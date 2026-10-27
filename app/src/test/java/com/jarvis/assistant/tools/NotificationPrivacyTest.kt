package com.jarvis.assistant.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyTest {

    @After fun tearDown() = NotificationStore.clear()

    private val defaults = NotificationPrivacySettings()

    @Test fun sensitiveAppsBlockedByDefault() {
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.whatsapp", null, "Mum", "hi"))
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.examplebank.mobile", null, "Alert", "x"))
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.foo", "msg", "A", "b"))
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.carrier.texts", null, "A", "b", defaultSmsPackage = "com.carrier.texts"))
        assertTrue(NotificationPrivacy.mayRetain(defaults, "com.example.weather", null, "Rain", "Rain at 5"))
    }

    @Test fun otpLikeContentBlockedByDefault() {
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.shop", null, "Your OTP", "123456 is your code"))
        assertFalse(NotificationPrivacy.mayRetain(defaults, "com.shop", null, null, "Verification code: 4821"))
        assertTrue(NotificationPrivacy.mayRetain(defaults, "com.shop", null, "Order", "Order 123456 shipped"))
    }

    @Test fun toggleOffAllowsSensitiveButExclusionsStillApply() {
        val s = NotificationPrivacySettings(blockSensitiveApps = false, excludedPackages = setOf("com.example.weather"))
        assertTrue(NotificationPrivacy.mayRetain(s, "com.whatsapp", null, "Mum", "hi"))
        assertFalse(NotificationPrivacy.mayRetain(s, "com.example.weather", null, "Rain", "x"))
    }

    @Test fun parsesPackageListDroppingInvalid() {
        assertEquals(
            setOf("com.a.b", "org.x_y.z"),
            NotificationPrivacy.parsePackageList("com.a.b, org.x_y.z;  bad name, ..., 1com.x, noDots"),
        )
    }

    @Test fun storeCapsCountAndTtl() {
        repeat(7) { NotificationStore.add("k$it", NotificationSummary("App", "t$it", null), nowMillis = 1_000L + it) }
        assertEquals(NotificationStore.CAPACITY, NotificationStore.size(2_000))
        assertEquals("t6", NotificationStore.latest(2_000)!!.title)
        assertNull(NotificationStore.latest(1_006 + NotificationStore.TTL_MS + 1))
        assertEquals(0, NotificationStore.size(1_006 + NotificationStore.TTL_MS + 1))
    }

    @Test fun storeDropsEntriesFromTheFutureAfterClockReset() {
        NotificationStore.add("k", NotificationSummary("App", "t", null), nowMillis = 50_000)
        assertNull(NotificationStore.latest(10))
    }

    @Test fun retainOnlyAndRemovePackage() {
        NotificationStore.add("a", NotificationSummary("A", "a", null), 0, "com.a")
        NotificationStore.add("b", NotificationSummary("B", "b", null), 0, "com.b")
        NotificationStore.removePackage("com.b")
        assertEquals("a", NotificationStore.latest(1)!!.title)
        NotificationStore.retainOnly { pkg, _ -> pkg != "com.a" }
        assertNull(NotificationStore.latest(1))
    }

    @Test fun readerChecksAccessAtTimeOfUseAndClearsWhenRevoked() {
        var access = true
        val reader = AndroidNotificationReader(accessCheck = { access }, clock = { 10L })
        NotificationStore.add("k", NotificationSummary("App", "secret title", "secret body"), 5)
        assertEquals("secret title", reader.latest()!!.title)
        access = false
        assertNull(reader.latest())
        access = true
        // Content was wiped when access was seen revoked; it does not come back.
        assertNull(reader.latest())
    }

    @Test fun containsRetainedContentDetectsNotificationText() {
        NotificationStore.add("k", NotificationSummary("App", "Dinner plans", "See you at 8"), 0)
        assertTrue(AndroidNotificationReader.containsRetainedContent("Latest notification from App. Dinner plans: See you at 8"))
        assertFalse(AndroidNotificationReader.containsRetainedContent("It's 3:45 PM."))
        AndroidNotificationReader.clearRetained()
        assertFalse(AndroidNotificationReader.containsRetainedContent("Dinner plans"))
    }
}
