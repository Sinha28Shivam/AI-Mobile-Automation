package com.jarvis.assistant.commands

import com.jarvis.assistant.tools.AppLaunchOutcome
import com.jarvis.assistant.tools.AppLauncher
import com.jarvis.assistant.tools.DeviceInfoTool
import com.jarvis.assistant.tools.NotificationReader
import com.jarvis.assistant.tools.ReminderTool
import com.jarvis.assistant.tools.SettingsNavigationTool
import com.jarvis.assistant.tools.SettingsOpenOutcome
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * Routes recognized text to allow-listed tools. The deterministic [parser] always runs first;
 * the optional [aiInterpreter] is consulted only for Unknown input and its output must pass
 * [validator]. Not thread-safe: call from one coroutine at a time.
 */
class CommandRouter(
    private val appLauncher: AppLauncher,
    private val deviceInfo: DeviceInfoTool,
    private val settings: SettingsNavigationTool,
    private val notifications: NotificationReader,
    private val reminders: ReminderTool,
    private val aiInterpreter: (suspend (String) -> String?)? = null,
    private val parser: CommandParser = CommandParser(),
    private val validator: ModelCommandValidator = ModelCommandValidator(),
    private val aiTimeoutMillis: Long = DEFAULT_AI_TIMEOUT_MS,
) {
    /** Last thing JARVIS said (used by Repeat). */
    var lastResponse: String? = null
        private set

    private var pending: CommandResult? = null

    suspend fun route(text: String, confidence: Float? = null): CommandResult {
        val parsed = parser.parse(text, confidence)

        // Stop is high priority and always wins, including over pending follow-ups.
        if (parsed is JarvisCommand.StopListening) {
            pending = null
            return remember(CommandResult.Completed(true, "Okay, I've stopped listening.", stopRequested = true))
        }
        if (parsed is JarvisCommand.Repeat) {
            val last = lastResponse
            return CommandResult.Completed(last != null, last ?: "I haven't said anything yet.")
        }

        resolvePending(text)?.let { return remember(it) }

        val command = if (parsed is JarvisCommand.Unknown) interpretWithAi(text) ?: parsed else parsed
        return remember(execute(command))
    }

    suspend fun execute(command: JarvisCommand): CommandResult = when (command) {
        is JarvisCommand.OpenApp -> openApp(command.appName)
        JarvisCommand.GetBattery -> battery()
        JarvisCommand.GetTime -> CommandResult.Completed(true, "It's ${deviceInfo.currentTimeText()}.")
        is JarvisCommand.OpenSettings -> openSettings(command.destination)
        JarvisCommand.StopListening ->
            CommandResult.Completed(true, "Okay, I've stopped listening.", stopRequested = true)
        JarvisCommand.Repeat ->
            CommandResult.Completed(lastResponse != null, lastResponse ?: "I haven't said anything yet.")
        JarvisCommand.ReadLatestNotification -> readNotification()
        is JarvisCommand.CreateReminder -> createReminder(command)
        is JarvisCommand.Unknown -> CommandResult.Completed(false, NOT_UNDERSTOOD)
    }

    private suspend fun interpretWithAi(text: String): JarvisCommand? {
        val ai = aiInterpreter ?: return null
        if (text.isBlank()) return null
        val output = try {
            withTimeout(aiTimeoutMillis) { ai(text) }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val cmd = validator.validate(output) ?: return null
        // A model may not trigger a Repeat/Stop loop; those come only from the deterministic parser.
        return if (cmd is JarvisCommand.Repeat || cmd is JarvisCommand.StopListening) null else cmd
    }

    private suspend fun resolvePending(text: String): CommandResult? {
        val p = pending ?: return null
        pending = null
        val n = CommandParser.normalize(text)
        return when (p) {
            is CommandResult.NeedsConfirmation -> when {
                n in YES -> execute(p.pendingCommand)
                n in NO -> CommandResult.Completed(true, "Okay.")
                else -> null
            }
            is CommandResult.Ambiguous -> {
                val choice = p.options.firstOrNull { CommandParser.normalize(it) == n }
                    ?: p.options.firstOrNull { n.isNotEmpty() && CommandParser.normalize(it).contains(n) }
                choice?.let { openApp(it) }
            }
            is CommandResult.Completed -> null
        }
    }

    private suspend fun openApp(name: String): CommandResult = when (val outcome = appLauncher.launch(name)) {
        is AppLaunchOutcome.Launched -> CommandResult.Completed(true, "Opening ${outcome.label}.")
        AppLaunchOutcome.NotFound -> CommandResult.Completed(false, "I couldn't find an app called $name.")
        is AppLaunchOutcome.Ambiguous -> {
            val options = outcome.labels.take(MAX_OPTIONS)
            CommandResult.Ambiguous("I found several apps: ${joinOptions(options)}. Which one?", options)
        }
        is AppLaunchOutcome.Failed -> CommandResult.Completed(false, "I couldn't open ${outcome.label}.")
        is AppLaunchOutcome.NeedsUserTap -> CommandResult.Completed(false, needsTap(outcome.label))
    }

    private fun battery(): CommandResult {
        val status = deviceInfo.batteryStatus()
            ?: return CommandResult.Completed(false, "I couldn't read the battery level.")
        val suffix = if (status.charging) " and charging" else ""
        return CommandResult.Completed(true, "Battery is at ${status.percent} percent$suffix.")
    }

    private fun openSettings(destination: SettingsDestination): CommandResult =
        when (settings.openDetailed(destination)) {
            SettingsOpenOutcome.Opened -> CommandResult.Completed(true, "Opening ${destination.spokenName}.")
            SettingsOpenOutcome.NeedsUserTap -> CommandResult.Completed(false, needsTap(destination.spokenName))
            SettingsOpenOutcome.Failed ->
                CommandResult.Completed(false, "I couldn't open ${destination.spokenName} on this phone.")
        }

    private fun readNotification(): CommandResult {
        if (!notifications.isAccessEnabled()) {
            return CommandResult.NeedsConfirmation(
                "Notification access is off. Enable it in settings. Should I open notification access settings?",
                JarvisCommand.OpenSettings(SettingsDestination.NOTIFICATION_ACCESS),
            )
        }
        val latest = notifications.latest()
            ?: return CommandResult.Completed(true, "You have no new notifications.")
        val body = listOfNotNull(latest.title?.takeIf { it.isNotBlank() }, latest.text?.takeIf { it.isNotBlank() })
            .joinToString(": ")
        val spoken = if (body.isEmpty()) "Latest notification is from ${latest.appLabel}."
        else "Latest notification from ${latest.appLabel}. $body"
        return CommandResult.Completed(true, spoken)
    }

    private fun createReminder(c: JarvisCommand.CreateReminder): CommandResult =
        if (reminders.schedule(c.title, c.delayMinutes)) {
            CommandResult.Completed(true, "Okay, I'll remind you to ${c.title} in ${describeDelay(c.delayMinutes)}.")
        } else {
            CommandResult.Completed(false, "I couldn't set that reminder.")
        }

    private fun remember(result: CommandResult): CommandResult {
        lastResponse = result.spokenText
        pending = result.takeIf { it is CommandResult.NeedsConfirmation || it is CommandResult.Ambiguous }
        return result
    }

    companion object {
        const val NOT_UNDERSTOOD = "Sorry, I didn't understand. Could you say that again?"
        const val DEFAULT_AI_TIMEOUT_MS = 8_000L
        private const val MAX_OPTIONS = 4
        private val YES = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "do it", "yes please", "go ahead", "open it")
        private val NO = setOf("no", "nope", "no thanks", "dont", "not now")

        internal fun needsTap(label: String) = "I couldn't open $label directly; tap the notification to open it."

        internal fun describeDelay(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            val hp = if (h == 1) "1 hour" else "$h hours"
            val mp = if (m == 1) "1 minute" else "$m minutes"
            return when {
                h == 0 -> mp
                m == 0 -> hp
                else -> "$hp and $mp"
            }
        }

        private fun joinOptions(options: List<String>): String =
            if (options.size <= 1) options.joinToString() else options.dropLast(1).joinToString(", ") + " or " + options.last()
    }
}
