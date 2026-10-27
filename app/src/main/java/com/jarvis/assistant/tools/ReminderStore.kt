package com.jarvis.assistant.tools

import com.jarvis.assistant.data.LocalHistoryRepository
import java.io.File
import java.io.IOException

/** A reminder waiting to fire. [triggerAtMillis] is wall-clock time (System.currentTimeMillis). */
data class ScheduledReminder(val id: Int, val title: String, val triggerAtMillis: Long)

/**
 * App-private, file-backed list of pending reminders so they can be rescheduled after a
 * reboot or app update. Titles are stored only here (never logged, excluded from backup).
 * Ids are monotonic and persisted, so every reminder gets a unique PendingIntent request
 * code and two reminders with the same title never overwrite each other.
 *
 * Format: first line "next\t<id>", then "id\ttrigger\tescapedTitle" per reminder.
 */
class ReminderStore(private val file: File) {

    private val lock = Any()

    /** Allocates a fresh id and stores the reminder. Returns null if the title is invalid or the store is full. */
    fun add(title: String, triggerAtMillis: Long): ScheduledReminder? = synchronized(lock) {
        if (!ReminderValidation.isValidTitle(title)) return null
        val state = read()
        if (state.reminders.size >= MAX_PENDING) return null
        val used = state.reminders.map { it.id }.toSet()
        var id = state.nextId
        while (id in used) id = if (id >= MAX_ID) FIRST_ID else id + 1
        val reminder = ScheduledReminder(id, title, triggerAtMillis)
        val next = if (id >= MAX_ID) FIRST_ID else id + 1
        write(State(next, state.reminders + reminder))
        reminder
    }

    fun remove(id: Int): ScheduledReminder? = synchronized(lock) {
        val state = read()
        val found = state.reminders.firstOrNull { it.id == id } ?: return null
        write(state.copy(reminders = state.reminders.filterNot { it.id == id }))
        found
    }

    fun get(id: Int): ScheduledReminder? = synchronized(lock) { read().reminders.firstOrNull { it.id == id } }

    fun all(): List<ScheduledReminder> = synchronized(lock) { read().reminders }

    /** Removes reminders whose ids are in [ids]. */
    fun removeAll(ids: Collection<Int>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val state = read()
        write(state.copy(reminders = state.reminders.filterNot { it.id in ids }))
    }

    fun clear() = synchronized(lock) {
        val state = read()
        write(State(state.nextId, emptyList()))
    }

    private data class State(val nextId: Int, val reminders: List<ScheduledReminder>)

    private fun read(): State {
        if (!file.exists()) return State(FIRST_ID, emptyList())
        val lines = try {
            file.readLines(Charsets.UTF_8)
        } catch (_: IOException) {
            return State(FIRST_ID, emptyList())
        }
        var next = FIRST_ID
        val reminders = ArrayList<ScheduledReminder>()
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0] == "next") {
                next = parts[1].toIntOrNull()?.takeIf { it >= FIRST_ID } ?: FIRST_ID
                continue
            }
            if (parts.size != 3) continue
            val id = parts[0].toIntOrNull()?.takeIf { it >= FIRST_ID } ?: continue
            val trigger = parts[1].toLongOrNull() ?: continue
            val title = LocalHistoryRepository.unescape(parts[2])
            if (!ReminderValidation.isValidTitle(title) || reminders.any { it.id == id }) continue
            reminders += ScheduledReminder(id, title, trigger)
        }
        // Never hand out an id that is still in use, even if the counter line was lost.
        val maxUsed = reminders.maxOfOrNull { it.id } ?: 0
        if (next <= maxUsed && maxUsed < MAX_ID) next = maxUsed + 1
        return State(next, reminders)
    }

    private fun write(state: State) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val body = buildString {
            append("next\t").append(state.nextId)
            state.reminders.forEach {
                append('\n').append(it.id).append('\t').append(it.triggerAtMillis).append('\t')
                    .append(LocalHistoryRepository.escape(it.title))
            }
        }
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not save reminders")
        }
    }

    companion object {
        const val FILE_NAME = "reminders.txt"
        const val FIRST_ID = 1
        const val MAX_ID = 999_999
        const val MAX_PENDING = 50
    }
}

/** Pure validation and planning for reminders; no Android types. */
object ReminderValidation {
    const val ACTION_REMINDER = "com.jarvis.assistant.action.REMINDER"
    const val MIN_DELAY_MINUTES = 1
    const val MAX_DELAY_MINUTES = 1440
    const val MAX_RECEIVER_TITLE_LENGTH = 120

    /** Reminders found overdue by up to this much are still delivered (as "missed"). */
    const val MISSED_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Delivery later than this after the due time is labelled "missed". */
    const val LATE_THRESHOLD_MS = 5 * 60 * 1000L

    fun isValidDelay(minutes: Int): Boolean = minutes in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES

    fun isValidTitle(title: String): Boolean =
        title.isNotBlank() && title.length <= MAX_RECEIVER_TITLE_LENGTH && title.none { it.isISOControl() }

    /**
     * Validates a broadcast before anything is shown. Returns the reminder id, or null if the
     * intent is malformed or unexpected (wrong action, bad id, bad title, or not in [stored]).
     */
    fun validateIntent(action: String?, id: Int, title: String?, stored: ScheduledReminder?): Int? {
        if (action != ACTION_REMINDER) return null
        if (id < ReminderStore.FIRST_ID || id > ReminderStore.MAX_ID) return null
        if (title == null || !isValidTitle(title)) return null
        if (stored == null || stored.id != id || stored.title != title) return null
        return id
    }

    /** Notification id for a reminder; kept away from the service (1001) and open-request (2001) ids. */
    fun notificationId(reminderId: Int): Int = NOTIFICATION_ID_BASE + reminderId

    data class Plan(
        val schedule: List<ScheduledReminder>,
        /** Overdue within [MISSED_WINDOW_MS]: deliver now. */
        val deliverNow: List<ScheduledReminder>,
        /** Too old to be useful: drop silently. */
        val drop: List<ScheduledReminder>,
    )

    fun plan(reminders: List<ScheduledReminder>, nowMillis: Long): Plan {
        val (future, overdue) = reminders.partition { it.triggerAtMillis > nowMillis }
        val (recent, old) = overdue.partition { nowMillis - it.triggerAtMillis <= MISSED_WINDOW_MS }
        return Plan(future, recent, old)
    }

    fun isLate(triggerAtMillis: Long, nowMillis: Long): Boolean = nowMillis - triggerAtMillis > LATE_THRESHOLD_MS

    private const val NOTIFICATION_ID_BASE = 100_000
}
