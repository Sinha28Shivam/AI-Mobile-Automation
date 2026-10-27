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
    }
}
