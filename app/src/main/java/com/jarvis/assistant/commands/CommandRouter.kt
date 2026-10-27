package com.jarvis.assistant.commands

import com.jarvis.assistant.tools.AppLaunchOutcome
import com.jarvis.assistant.tools.AppLauncher
import com.jarvis.assistant.tools.DeviceInfoTool
import com.jarvis.assistant.tools.NotificationReader
import com.jarvis.assistant.tools.ReminderTool
import com.jarvis.assistant.tools.SettingsNavigationTool
import com.jarvis.assistant.tools.SettingsOpenOutcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Routes recognized text to allow-listed tools.
 *
 * - The deterministic [parser] always runs first. A language model ([localModel], or the legacy
 *   [aiInterpreter] hook which receives the delimited prompt) is consulted only for Unknown input
 *   with adequate confidence, through [GuardedAiInterpreter] and [ModelCommandValidator].
 * - Stop always wins and clears any pending follow-up. Repeat only replays [lastResponse] text.
 * - Pending yes/no questions and app choices expire after [pendingTtlMillis]. An answer that is
 *   not valid is re-prompted once; a second invalid answer cancels the pending flow.
 * - Consequential commands ([JarvisCommand.isConsequential]) proposed by a model, or heard with
 *   confidence below [minActionConfidence], require explicit confirmation.
 * - Recognized text is never logged here. Not thread-safe: call from one coroutine at a time.
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
    private val clock: Clock = Clock.SYSTEM,
    private val pendingTtlMillis: Long = DEFAULT_PENDING_TTL_MS,
    localModel: LocalLanguageModel? = null,
    private val minAiConfidence: Float = DEFAULT_MIN_AI_CONFIDENCE,
    private val minActionConfidence: Float = DEFAULT_MIN_ACTION_CONFIDENCE,
) {
    /** Last thing JARVIS said (used by Repeat). */
    var lastResponse: String? = null
        private set

    private class Pending(val result: CommandResult, val expiresAt: Long, val invalidAnswers: Int)

    private var pending: Pending? = null

    private val guardedAi: GuardedAiInterpreter? =
        (localModel ?: aiInterpreter?.let { hook -> LocalLanguageModel { prompt -> hook(prompt) } })
            ?.let { GuardedAiInterpreter(it, validator, aiTimeoutMillis) }

    /** True while a non-expired yes/no question or app choice is waiting for an answer. */
    val hasPendingFollowUp: Boolean
        get() = pending?.let { clock.nowMillis() < it.expiresAt } ?: false

    suspend fun route(text: String, confidence: Float? = null): CommandResult {
        val parsed = parser.parse(text, confidence)

        // Stop is high priority and always wins, including over pending follow-ups.
        if (parsed is JarvisCommand.StopListening) {
            pending = null
            return remember(CommandResult.Completed(true, STOPPED, stopRequested = true))
        }
        // Repeat only replays text; it never re-executes an action and leaves pending state as is.
        if (parsed is JarvisCommand.Repeat) {
            val last = lastResponse
            return CommandResult.Completed(last != null, last ?: NOTHING_TO_REPEAT)
        }

        pending?.let { p ->
            pending = null
            if (clock.nowMillis() < p.expiresAt) return resolvePending(p, text, parsed, confidence)
            // Expired: treat this utterance as a fresh command.
        }

        var command = parsed
        var fromModel = false
        if (parsed is JarvisCommand.Unknown && aiAllowed(confidence)) {
            guardedAi?.interpret(text)?.let {
                command = it
                fromModel = true
            }
        }
        val lowConfidence = confidence != null && confidence < minActionConfidence
        if (command.isConsequential && (fromModel || lowConfidence)) {
            return remember(CommandResult.NeedsConfirmation(confirmationQuestion(command), command))
        }
        return remember(execute(command))
    }

    /** Executes a typed command. Tool exceptions are reported as failures, never as success. */
    suspend fun execute(command: JarvisCommand): CommandResult = try {
        executeUnchecked(command)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        CommandResult.Completed(false, SOMETHING_WENT_WRONG)
    }

    private suspend fun executeUnchecked(command: JarvisCommand): CommandResult = when (command) {
        is JarvisCommand.OpenApp -> openApp(command.appName)
        JarvisCommand.GetBattery -> battery()
        JarvisCommand.GetChargingStatus -> chargingStatus()
        JarvisCommand.GetTime -> CommandResult.Completed(true, "It's ${deviceInfo.currentTimeText()}.")
        JarvisCommand.GetDate -> date()
        is JarvisCommand.OpenSettings -> openSettings(command.destination)
        JarvisCommand.StopListening -> CommandResult.Completed(true, STOPPED, stopRequested = true)
        JarvisCommand.Repeat ->
            CommandResult.Completed(lastResponse != null, lastResponse ?: NOTHING_TO_REPEAT)
        JarvisCommand.ReadLatestNotification -> readNotification()
        is JarvisCommand.CreateReminder -> createReminder(command)
        is JarvisCommand.InvalidReminder -> CommandResult.Completed(false, invalidReminderText(command.reason))
        is JarvisCommand.UnsupportedToggle -> CommandResult.NeedsConfirmation(
            "I can't change ${command.destination.spokenName.removeSuffix(" settings")} myself. " +
                "Should I open ${command.destination.spokenName}?",
            JarvisCommand.OpenSettings(command.destination),
        )
        is JarvisCommand.Unknown -> CommandResult.Completed(false, NOT_UNDERSTOOD)
    }

    private fun aiAllowed(confidence: Float?): Boolean = confidence == null || confidence >= minAiConfidence

    private suspend fun resolvePending(
        p: Pending,
        text: String,
        parsed: JarvisCommand,
        confidence: Float?,
    ): CommandResult {
        val lowConfidence = confidence != null && confidence < minActionConfidence
        val answer = if (lowConfidence) CommandParser.Answer.OTHER else CommandParser.classifyAnswer(text)
        return when (val r = p.result) {
            is CommandResult.NeedsConfirmation -> when (answer) {
                CommandParser.Answer.YES -> remember(execute(r.pendingCommand))
                CommandParser.Answer.NO -> remember(CommandResult.Completed(true, CANCELLED))
                CommandParser.Answer.OTHER -> invalidAnswer(p) {
                    CommandResult.NeedsConfirmation(CONFIRM_REPROMPT, r.pendingCommand)
                }
            }
            is CommandResult.Ambiguous -> {
                if (answer == CommandParser.Answer.NO) return remember(CommandResult.Completed(true, CANCELLED))
                val choice = if (lowConfidence) null else pickOption(r.options, text, parsed)
                if (choice != null) {
                    remember(openApp(choice))
                } else {
                    invalidAnswer(p) {
                        CommandResult.Ambiguous("Please say ${joinOptions(r.options)}, or say cancel.", r.options)
                    }
                }
            }
            is CommandResult.Completed -> remember(execute(parsed))
        }
    }

    private inline fun invalidAnswer(p: Pending, reprompt: () -> CommandResult): CommandResult {
        if (p.invalidAnswers >= MAX_INVALID_ANSWERS) {
            return remember(CommandResult.Completed(false, CANCELLED_AFTER_INVALID))
        }
        return remember(reprompt(), invalidAnswers = p.invalidAnswers + 1)
    }

    private fun pickOption(options: List<String>, text: String, parsed: JarvisCommand): String? {
        val n = (parsed as? JarvisCommand.OpenApp)?.appName?.let(CommandParser::normalize)
            ?: CommandParser.normalize(text).removePrefix("the ").trim()
        if (n.isEmpty()) return null
        ORDINALS[n]?.let { return options.getOrNull(it) }
        options.firstOrNull { CommandParser.normalize(it) == n }?.let { return it }
        val partial = options.filter { " ${CommandParser.normalize(it)} ".contains(" $n ") }
        return partial.singleOrNull()
    }

    private suspend fun openApp(name: String): CommandResult = when (val outcome = appLauncher.launch(name)) {
        is AppLaunchOutcome.Launched -> CommandResult.Completed(true, "Opening ${outcome.label}.")
        AppLaunchOutcome.NotFound -> CommandResult.Completed(false, "I couldn't find an app called $name.")
        is AppLaunchOutcome.Ambiguous -> {
            val options = outcome.labels.distinct().take(MAX_OPTIONS)
            when (options.size) {
                0 -> CommandResult.Completed(false, "I couldn't find an app called $name.")
                // Several installed apps share this exact label; voice cannot tell them apart.
                1 -> CommandResult.Completed(
                    false,
                    "More than one app is called ${options.single()}, so I can't tell which one to open.",
                )
                else -> CommandResult.Ambiguous("I found several apps: ${joinOptions(options)}. Which one?", options)
            }
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

    private fun chargingStatus(): CommandResult {
        val status = deviceInfo.batteryStatus()
            ?: return CommandResult.Completed(false, "I couldn't read the charging status.")
        val text = if (status.charging) {
            "Yes, your phone is charging. Battery is at ${status.percent} percent."
        } else {
            "No, your phone isn't charging. Battery is at ${status.percent} percent."
        }
        return CommandResult.Completed(true, text)
    }

    private fun date(): CommandResult {
        val text = deviceInfo.currentDateText()?.takeIf { it.isNotBlank() }
            ?: return CommandResult.Completed(false, "I couldn't read today's date.")
        return CommandResult.Completed(true, "Today is $text.")
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
            val reason = reminders.lastFailureReason()?.takeIf { it.isNotBlank() }
            CommandResult.Completed(false, if (reason != null) "I couldn't set that reminder. $reason" else "I couldn't set that reminder.")
        }

    private fun confirmationQuestion(command: JarvisCommand): String = when (command) {
        is JarvisCommand.CreateReminder ->
            "Should I set a reminder to ${command.title} in ${describeDelay(command.delayMinutes)}?"
        else -> "Should I go ahead?"
    }

    private fun remember(result: CommandResult, invalidAnswers: Int = 0): CommandResult {
        lastResponse = result.spokenText
        pending = if (result is CommandResult.NeedsConfirmation || result is CommandResult.Ambiguous) {
            Pending(result, clock.nowMillis() + pendingTtlMillis, invalidAnswers)
        } else {
            null
        }
        return result
    }

    companion object {
        const val NOT_UNDERSTOOD = "Sorry, I didn't understand. Could you say that again?"
        const val STOPPED = "Okay, I've stopped listening."
        const val NOTHING_TO_REPEAT = "I haven't said anything yet."
        const val CANCELLED = "Okay, cancelled."
        const val CANCELLED_AFTER_INVALID = "I didn't get a clear answer, so I've cancelled that."
        const val CONFIRM_REPROMPT = "Please say yes or no, or cancel."
        const val SOMETHING_WENT_WRONG = "Something went wrong, so I couldn't do that."
        const val DEFAULT_AI_TIMEOUT_MS = GuardedAiInterpreter.DEFAULT_TIMEOUT_MS
        const val DEFAULT_PENDING_TTL_MS = 30_000L
        const val DEFAULT_MIN_AI_CONFIDENCE = 0.5f
        const val DEFAULT_MIN_ACTION_CONFIDENCE = 0.5f
        const val MAX_OPTIONS = 3
        private const val MAX_INVALID_ANSWERS = 1

        private val ORDINALS = mapOf(
            "first" to 0, "first one" to 0, "one" to 0, "number one" to 0,
            "second" to 1, "second one" to 1, "two" to 1, "number two" to 1,
            "third" to 2, "third one" to 2, "three" to 2, "number three" to 2,
        )

        internal fun needsTap(label: String) = "I couldn't open $label directly; tap the notification to open it."

        internal fun invalidReminderText(reason: JarvisCommand.InvalidReminder.Reason): String = when (reason) {
            JarvisCommand.InvalidReminder.Reason.MISSING_DELAY ->
                "Please say when, for example: remind me to call mom in 10 minutes."
            JarvisCommand.InvalidReminder.Reason.INVALID_DELAY ->
                "I can only set reminders from 1 minute to 24 hours ahead."
            JarvisCommand.InvalidReminder.Reason.EMPTY_TITLE ->
                "What should I remind you about? For example: remind me to call mom in 10 minutes."
            JarvisCommand.InvalidReminder.Reason.TITLE_TOO_LONG ->
                "That reminder is too long. Please use a shorter title."
        }

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
