package com.jarvis.assistant.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalHistoryRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private fun repo() = LocalHistoryRepository(File(folder.root, "history.txt"))

    @Test fun disabledRecordsNothing() = runTest {
        val r = repo()
        r.record(enabled = false, command = "open youtube", result = "Opening YouTube.")
        assertTrue(r.entries().isEmpty())
    }

    @Test fun keepsMaxEntriesAndClears() = runTest {
        val r = repo()
        repeat(25) { r.record(true, "cmd $it", "res\t$it\nline", timestampMillis = it.toLong()) }
        val e = r.entries()
        assertEquals(LocalHistoryRepository.MAX_ENTRIES, e.size)
        assertEquals("cmd 5", e.first().command)
        assertEquals("res\t24\nline", e.last().result)
        r.clear()
        assertTrue(r.entries().isEmpty())
        assertTrue(!File(folder.root, "history.txt").exists())
    }

    @Test fun clearThenRecordStartsFresh() = runTest {
        val r = repo()
        r.record(true, "a", "b")
        r.clear()
        r.record(true, "c", "d")
        assertEquals(listOf("c"), r.entries().map { it.command })
    }

    @Test fun notificationReadoutIsNeverStored() = runTest {
        val r = repo()
        r.record(true, "read my latest notification", "Latest notification from Messages. Mum: call me")
        r.record(true, "read notifications", "Latest notification is from Bank.")
        val e = r.entries()
        assertEquals(HistorySanitizer.REDACTED_NOTIFICATION, e[0].result)
        assertEquals(HistorySanitizer.REDACTED_NOTIFICATION, e[1].result)
        assertTrue(File(folder.root, "history.txt").readText().contains("Mum").not())
    }

    @Test fun retainedNotificationTextIsRedactedFromAnyReply() = runTest {
        com.jarvis.assistant.tools.NotificationStore.add(
            "k", com.jarvis.assistant.tools.NotificationSummary("App", "Secret dinner", "at 8"), 0,
        )
        try {
            val r = repo()
            r.record(true, "repeat", "You said: Secret dinner")
            assertEquals(HistorySanitizer.REDACTED_NOTIFICATION, r.entries().single().result)
        } finally {
            com.jarvis.assistant.tools.NotificationStore.clear()
        }
    }

    @Test fun customSanitizerIsApplied() = runTest {
        val r = LocalHistoryRepository(File(folder.root, "h2.txt")) { c, _ -> c.uppercase() to "x" }
        r.record(true, "open camera", "Opening Camera.")
        assertEquals(HistoryEntry(1, "OPEN CAMERA", "x"), r.entries().single().copy(timestampMillis = 1))
    }
}
