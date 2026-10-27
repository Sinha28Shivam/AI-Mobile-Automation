package com.jarvis.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReminderStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "reminders.txt") }
    private fun store() = ReminderStore(file)

    @Test fun sameTitleRemindersGetUniqueIdsAndCoexist() {
        val s = store()
        val a = s.add("call mum", 1_000)!!
        val b = s.add("call mum", 1_000)!!
        assertNotEquals(a.id, b.id)
        assertEquals(2, s.all().size)
        assertNotEquals(ReminderValidation.notificationId(a.id), ReminderValidation.notificationId(b.id))
    }

    @Test fun idsAreMonotonicAndPersistedAcrossInstancesAndRemoval() {
        val first = store().add("a", 1)!!
        store().remove(first.id)
        val second = store().add("b", 2)!!
        assertTrue(second.id > first.id)
        val third = ReminderStore(file).add("c", 3)!!
        assertTrue(third.id > second.id)
    }

    @Test fun persistsTitlesWithSpecialCharacters() {
        store().add("buy milk, \"eggs\" \\n and ünïcode", 42)
        val r = store().all().single()
        assertEquals("buy milk, \"eggs\" \\n and ünïcode", r.title)
        assertEquals(42L, r.triggerAtMillis)
    }

    @Test fun invalidTitlesAreRejected() {
        assertNull(store().add("line\tbreak", 1))
        assertNull(store().add(" ", 1))
        assertNull(store().add("x".repeat(121), 1))
        assertTrue(store().all().isEmpty())
    }

    @Test fun ignoresCorruptLinesAndNeverReusesLiveIds() {
        file.writeText("garbage\n7\t100\tok\nx\ty\tz\n9\tnotnum\tbad")
        val s = store()
        assertEquals(listOf(7), s.all().map { it.id })
        assertTrue(s.add("new", 5)!!.id > 7)
    }

    @Test fun capsPendingReminders() {
        val s = store()
        repeat(ReminderStore.MAX_PENDING) { s.add("r$it", it.toLong()) }
        assertNull(s.add("one too many", 0))
    }

    @Test fun clearAndRemoveAll() {
        val s = store()
        val a = s.add("a", 1)!!
        s.add("b", 2)
        s.removeAll(listOf(a.id))
        assertEquals(listOf("b"), s.all().map { it.title })
        s.clear()
        assertTrue(s.all().isEmpty())
    }

    @Test fun delayLimits() {
        assertTrue(ReminderValidation.isValidDelay(1))
        assertTrue(ReminderValidation.isValidDelay(1440))
        assertTrue(!ReminderValidation.isValidDelay(0))
        assertTrue(!ReminderValidation.isValidDelay(1441))
        assertTrue(!ReminderValidation.isValidDelay(-5))
    }

    @Test fun receiverValidationRejectsMalformedIntents() {
        val stored = ScheduledReminder(5, "stretch", 0)
        val action = ReminderValidation.ACTION_REMINDER
        assertEquals(5, ReminderValidation.validateIntent(action, 5, "stretch", stored))
        assertNull(ReminderValidation.validateIntent(null, 5, "stretch", stored))
        assertNull(ReminderValidation.validateIntent("com.evil.REMINDER", 5, "stretch", stored))
        assertNull(ReminderValidation.validateIntent("$action.extra", 5, "stretch", stored))
        assertNull(ReminderValidation.validateIntent(action, -1, "stretch", stored))
        assertNull(ReminderValidation.validateIntent(action, 0, "stretch", stored))
        assertNull(ReminderValidation.validateIntent(action, 5, null, stored))
        assertNull(ReminderValidation.validateIntent(action, 5, " ", stored))
        assertNull(ReminderValidation.validateIntent(action, 5, "x".repeat(121), ScheduledReminder(5, "x".repeat(121), 0)))
        assertNull(ReminderValidation.validateIntent(action, 5, "bad\nline", stored))
        assertNull(ReminderValidation.validateIntent(action, 5, "stretch", null))
        assertNull(ReminderValidation.validateIntent(action, 5, "other", stored))
        assertNull(ReminderValidation.validateIntent(action, 6, "stretch", stored))
    }

    @Test fun rescheduleplanSplitsFutureMissedAndExpired() {
        val now = 10L * 24 * 60 * 60 * 1000
        val future = ScheduledReminder(1, "f", now + 60_000)
        val missed = ScheduledReminder(2, "m", now - 60 * 60 * 1000)
        val expired = ScheduledReminder(3, "e", now - ReminderValidation.MISSED_WINDOW_MS - 1)
        val plan = ReminderValidation.plan(listOf(future, missed, expired), now)
        assertEquals(listOf(future), plan.schedule)
        assertEquals(listOf(missed), plan.deliverNow)
        assertEquals(listOf(expired), plan.drop)
    }

    @Test fun lateDetection() {
        assertTrue(!ReminderValidation.isLate(1_000, 1_000 + ReminderValidation.LATE_THRESHOLD_MS))
        assertTrue(ReminderValidation.isLate(1_000, 1_001 + ReminderValidation.LATE_THRESHOLD_MS))
    }

    @Test fun notificationIdsAvoidOtherAppNotifications() {
        val id = ReminderValidation.notificationId(ReminderStore.FIRST_ID)
        assertTrue(id != 1001 && id != 2001 && id > 2001)
    }
}
