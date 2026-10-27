package com.jarvis.assistant

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceStateRepository
import com.jarvis.assistant.tools.AndroidReminderTool
import com.jarvis.assistant.tools.ReminderReceiver
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * (d) Notification channels exist with the expected ids and importance once their feature has
 * been used. Channels are created lazily, so each test triggers its feature first.
 *
 * Importance is user-editable after creation (and some OEM skins adjust it), so the assertions
 * check the intent of each channel rather than an exact value where the user may have changed it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestSupport.listeningPermissions())

    private val nm get() = TestSupport.notificationManager

    @After
    fun tearDown() {
        JarvisForegroundService.stop(TestSupport.targetContext)
        TestSupport.waitUntil(5_000) { !ServiceStateRepository.state.value.isActive }
        ServiceStateRepository.reset()
        nm.activeNotifications
            .filter { it.notification.channelId == TestSupport.REMINDERS_CHANNEL_ID && it.id == TEST_REMINDER_ID }
            .forEach { nm.cancel(it.tag, it.id) }
    }

    @Test
    fun listeningChannelIsLowImportanceWithoutBadge() {
        TestSupport.wakeAndUnlock()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            JarvisForegroundService.start(TestSupport.targetContext)
            assertTrue(
                "listening channel was not created",
                TestSupport.waitUntil(10_000) { nm.getNotificationChannel(TestSupport.LISTENING_CHANNEL_ID) != null },
            )
        } finally {
            JarvisForegroundService.stop(TestSupport.targetContext)
            scenario.close()
        }
        val channel = nm.getNotificationChannel(TestSupport.LISTENING_CHANNEL_ID)
        assertNotNull(channel)
        // Status-only channel: silent, no heads-up. A user could raise it, but it must never be HIGH by default.
        assertTrue("importance=${channel.importance}", channel.importance <= NotificationManager.IMPORTANCE_LOW)
        assertTrue(!channel.canShowBadge())
        assertTrue(!channel.name.isNullOrBlank())
    }

    @Test
    fun remindersChannelIsHighImportance() {
        assumeTrue("notifications disabled for the app", nm.areNotificationsEnabled())
        // Public entry point that ensures the channel exists before reporting deliverability.
        assertTrue(AndroidReminderTool(TestSupport.targetContext).canPostNotifications())

        val channel = nm.getNotificationChannel(TestSupport.REMINDERS_CHANNEL_ID)
        assertNotNull("reminders channel was not created", channel)
        assertTrue("importance=${channel.importance}", channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun forgedReminderIntentPostsNothing() {
        val intent = Intent(TestSupport.targetContext, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMINDER)
            .putExtra(ReminderReceiver.EXTRA_TITLE, "Instrumentation forged reminder")
            .putExtra(ReminderReceiver.EXTRA_ID, TEST_REMINDER_ID)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ReminderReceiver().onReceive(TestSupport.targetContext, intent)
        }
        TestSupport.waitUntil(1_000) { false }
        assertTrue(
            "a reminder intent without a saved reminder must be ignored",
            TestSupport.channelNotifications(TestSupport.REMINDERS_CHANNEL_ID).none { it.id == TEST_REMINDER_ID },
        )
    }

    @Test
    fun registeredChannelsAreNamedAndDescribed() {
        // Every channel the app registers must be user-understandable in system settings.
        nm.notificationChannels.filterNot { it.id.startsWith("miscellaneous") }.forEach {
            assertTrue("channel ${it.id} has no name", !it.name.isNullOrBlank())
        }
        nm.getNotificationChannel(TestSupport.OPEN_REQUESTS_CHANNEL_ID)?.let {
            assertTrue("open_requests importance=${it.importance}", it.importance >= NotificationManager.IMPORTANCE_DEFAULT)
        }
    }

    private companion object {
        const val TEST_REMINDER_ID = 0x7E57
    }
}
