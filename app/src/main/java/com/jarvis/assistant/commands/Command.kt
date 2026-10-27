package com.jarvis.assistant.commands

/** Settings screens JARVIS may open. JARVIS never toggles settings itself. */
enum class SettingsDestination(val spokenName: String) {
    WIFI("Wi-Fi settings"),
    BLUETOOTH("Bluetooth settings"),
    SOUND("sound settings"),
    DISPLAY("display settings"),
    BATTERY("battery settings"),
    NOTIFICATIONS("JARVIS notification settings"),
    APP_SETTINGS("JARVIS app settings"),
    NOTIFICATION_ACCESS("notification access settings"),
    MAIN("settings"),
}

/** Typed, allow-listed commands. Anything else is [Unknown]. */
sealed interface JarvisCommand {
    /**
     * Consequential commands change state outside the conversation (e.g. schedule something).
     * They require explicit confirmation when proposed by a model or heard with low confidence.
     */
    val isConsequential: Boolean get() = false

    data class OpenApp(val appName: String) : JarvisCommand
    data object GetBattery : JarvisCommand

    /** Read-only: answers "is my phone charging" from the same battery status. */
    data object GetChargingStatus : JarvisCommand
    data object GetTime : JarvisCommand

    /** Read-only: today's locale-formatted date. */
    data object GetDate : JarvisCommand
    data class OpenSettings(val destination: SettingsDestination) : JarvisCommand
    data object StopListening : JarvisCommand
    data object Repeat : JarvisCommand
    data object ReadLatestNotification : JarvisCommand

    /** Use [create] for untrusted input; the constructor throws on invalid values. */
    data class CreateReminder(val title: String, val delayMinutes: Int) : JarvisCommand {
        init {
            require(isValidTitle(title)) { "Invalid reminder title" }
            require(delayMinutes in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) { "Delay out of range" }
        }

        override val isConsequential: Boolean get() = true

        companion object {
            const val MIN_DELAY_MINUTES = 1
            const val MAX_DELAY_MINUTES = 24 * 60
            const val MAX_TITLE_LENGTH = 100

            fun isValidTitle(title: String): Boolean =
                title.isNotBlank() && title.length <= MAX_TITLE_LENGTH && title.none { it.isISOControl() }

            fun create(title: String, delayMinutes: Int): CreateReminder? {
                val trimmed = title.trim()
                if (!isValidTitle(trimmed)) return null
                if (delayMinutes !in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) return null
                return CreateReminder(trimmed, delayMinutes)
            }
        }
    }

    /** A reminder request that was recognized but cannot be scheduled as spoken. Never executes. */
    data class InvalidReminder(val reason: Reason) : JarvisCommand {
        enum class Reason { MISSING_DELAY, INVALID_DELAY, EMPTY_TITLE, TITLE_TOO_LONG }
    }

    /** "Turn on Wi-Fi" etc. JARVIS never toggles settings; it may only offer the settings screen. */
    data class UnsupportedToggle(val destination: SettingsDestination) : JarvisCommand

    data class Unknown(val originalText: String) : JarvisCommand
}

/** Result of routing a command; [spokenText] is what JARVIS should say. */
sealed interface CommandResult {
    val success: Boolean
    val spokenText: String
    val stopRequested: Boolean get() = false

    data class Completed(
        override val success: Boolean,
        override val spokenText: String,
        override val stopRequested: Boolean = false,
    ) : CommandResult

    /** JARVIS asked a yes/no question; answering "yes" runs [pendingCommand]. */
    data class NeedsConfirmation(
        override val spokenText: String,
        val pendingCommand: JarvisCommand,
    ) : CommandResult {
        override val success: Boolean get() = false
    }

    /** Several matches; the next utterance may pick one of [options]. */
    data class Ambiguous(
        override val spokenText: String,
        val options: List<String>,
    ) : CommandResult {
        override val success: Boolean get() = false
    }
}
