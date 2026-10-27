package com.jarvis.assistant.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class HistoryEntry(val timestampMillis: Long, val command: String, val result: String)

/**
 * Opt-in local command history in an app-private file (excluded from backup since
 * allowBackup=false). Records nothing unless `enabled` is true. Keeps at most [MAX_ENTRIES].
 * Format: one entry per line, tab-separated, with tabs/newlines/backslashes escaped.
 */
class LocalHistoryRepository internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    private val mutex = Mutex()

    suspend fun record(enabled: Boolean, command: String, result: String, timestampMillis: Long = System.currentTimeMillis()) {
        if (!enabled) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val entries = readUnlocked() + HistoryEntry(timestampMillis, command.take(MAX_FIELD), result.take(MAX_FIELD))
                writeUnlocked(entries.takeLast(MAX_ENTRIES))
            }
        }
    }

    /** Newest last. */
    suspend fun entries(): List<HistoryEntry> = mutex.withLock { withContext(Dispatchers.IO) { readUnlocked() } }

    suspend fun clear() {
        mutex.withLock { withContext(Dispatchers.IO) { if (file.exists()) file.delete() } }
    }

    private fun readUnlocked(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
                HistoryEntry(ts, unescape(parts[1]), unescape(parts[2]))
            }.takeLast(MAX_ENTRIES)
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun writeUnlocked(entries: List<HistoryEntry>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(
                entries.joinToString("\n") { "${it.timestampMillis}\t${escape(it.command)}\t${escape(it.result)}" },
                Charsets.UTF_8,
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: IOException) {
            // History is best-effort; never crash the assistant for it.
        }
    }

    companion object {
        const val MAX_ENTRIES = 20
        private const val MAX_FIELD = 500
        private const val FILE_NAME = "command_history.txt"

        @Volatile
        private var instance: LocalHistoryRepository? = null

        /** Shared instance so the service and UI serialize access through one mutex. */
        fun get(context: Context): LocalHistoryRepository =
            instance ?: synchronized(this) { instance ?: LocalHistoryRepository(context).also { instance = it } }

        internal fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

        internal fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
