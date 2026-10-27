package com.jarvis.assistant.commands

/**
 * Deterministic, pure-Kotlin parser from recognized speech to [JarvisCommand].
 * It works without any model and is the primary command path.
 */
class CommandParser {

    /**
     * @param confidence optional recognizer confidence in 0..1; below [MIN_CONFIDENCE] yields Unknown.
     */
    fun parse(text: String, confidence: Float? = null): JarvisCommand {
        if (confidence != null && confidence < MIN_CONFIDENCE) return JarvisCommand.Unknown(text)
        if (text.length > MAX_INPUT_LENGTH) return JarvisCommand.Unknown(text)
        val n = normalize(text)
        if (n.isEmpty()) return JarvisCommand.Unknown(text)

        return parseStop(n)
            ?: parseRepeat(n)
            ?: parseReminder(n)
            ?: parseSettings(n)
            ?: parseNotifications(n)
            ?: parseBattery(n)
            ?: parseTime(n)
            ?: parseOpenApp(n)
            ?: JarvisCommand.Unknown(text)
    }

    private fun parseStop(n: String): JarvisCommand? = when {
        n in STOP_PHRASES -> JarvisCommand.StopListening
        n.startsWith("stop listening") -> JarvisCommand.StopListening
        else -> null
    }

    private fun parseRepeat(n: String): JarvisCommand? =
        if (n in REPEAT_PHRASES) JarvisCommand.Repeat else null

    private fun parseReminder(n: String): JarvisCommand? {
        val prefix = REMINDER_PREFIXES.firstOrNull { n.startsWith("$it ") } ?: return null
        val rest = n.removePrefix(prefix).trim()

        // "remind me to X in N minutes" or "remind me in N minutes to X"
        val trailing = TRAILING_DELAY.matchEntire(rest)
        val leading = LEADING_DELAY.matchEntire(rest)
        val (title, amount, unit) = when {
            trailing != null -> Triple(trailing.groupValues[1], trailing.groupValues[2], trailing.groupValues[3])
            leading != null -> Triple(leading.groupValues[3], leading.groupValues[1], leading.groupValues[2])
            else -> return null
        }
        val number = parseNumber(amount) ?: return null
        val minutes = if (unit.startsWith("h")) number.toLong() * 60 else number.toLong()
        if (minutes > Int.MAX_VALUE) return null
        val cleanTitle = title.removePrefix("to ").removePrefix("about ").trim()
        return JarvisCommand.CreateReminder.create(cleanTitle, minutes.toInt())
    }

    private fun parseSettings(n: String): JarvisCommand? {
        var s = n
        for (verb in SETTINGS_VERBS) {
            if (s.startsWith("$verb ")) {
                s = s.removePrefix("$verb ")
                break
            }
        }
        s = s.removePrefix("the ").removePrefix("my ").trim()
        val hasSettingsWord = s.endsWith(" settings") || s.endsWith(" setting") || s == "settings"
        val subject = s.removeSuffix("settings").removeSuffix("setting").trim()
        if (!hasSettingsWord) {
            // Bare "wifi" / "open bluetooth" map to their settings pages (never toggled).
            return BARE_SETTINGS[s]?.let { JarvisCommand.OpenSettings(it) }
        }
        val destination = if (subject.isEmpty()) SettingsDestination.MAIN else SETTINGS_SUBJECTS[subject] ?: return null
        return JarvisCommand.OpenSettings(destination)
    }

    private fun parseNotifications(n: String): JarvisCommand? {
        if (!n.contains("notification")) return null
        return if (NOTIFICATION_PATTERN.matches(n)) JarvisCommand.ReadLatestNotification else null
    }

    private fun parseBattery(n: String): JarvisCommand? {
        if (!n.contains("battery") && !n.contains("charg")) return null
        return if (BATTERY_PATTERN.matches(n)) JarvisCommand.GetBattery else null
    }

    private fun parseTime(n: String): JarvisCommand? =
        if (TIME_PATTERN.matches(n)) JarvisCommand.GetTime else null

    private fun parseOpenApp(n: String): JarvisCommand? {
        val m = OPEN_APP.matchEntire(n) ?: return null
        val name = m.groupValues[2].removeSuffix(" app").removeSuffix(" application").trim()
        if (name.isEmpty() || name in NOT_APP_NAMES || name.length > MAX_APP_NAME_LENGTH) return null
        return JarvisCommand.OpenApp(name)
    }

    companion object {
        const val MIN_CONFIDENCE = 0.4f
        const val MAX_INPUT_LENGTH = 500
        private const val MAX_APP_NAME_LENGTH = 60

        private val WAKE_PREFIXES = listOf("hey jarvis", "hi jarvis", "ok jarvis", "okay jarvis", "jarvis")
        private val LEADING_FILLERS = listOf(
            "can you", "could you", "would you", "will you", "please", "kindly",
            "i want to", "i would like to", "id like to", "i need to", "lets", "just",
        )

        /**
         * Lowercases, removes punctuation (apostrophes and hyphens join words: "what's"→"whats",
         * "wi-fi"→"wifi"), strips wake phrase, polite fillers and trailing "please"/"jarvis".
         */
        fun normalize(text: String): String {
            var s = text.lowercase()
                .replace("'", "").replace("\u2019", "").replace("-", "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            var changed = true
            while (changed && s.isNotEmpty()) {
                changed = false
                for (p in WAKE_PREFIXES + LEADING_FILLERS) {
                    if (s == p) {
                        s = ""; changed = true; break
                    }
                    if (s.startsWith("$p ")) {
                        s = s.removePrefix("$p ").trim(); changed = true; break
                    }
                }
            }
            s = s.replace(Regex("\\bplease\\b"), " ").replace(Regex("\\s+"), " ").trim()
            s = s.removeSuffix(" jarvis").removeSuffix(" for me").removeSuffix(" now").trim()
            return s
        }

        private val STOP_PHRASES = setOf(
            "stop", "stop it", "stop now", "stop listening", "go to sleep", "be quiet",
            "shut up", "thats all", "that is all", "never mind", "nevermind", "cancel",
        )
        private val REPEAT_PHRASES = setOf(
            "repeat", "repeat that", "repeat it", "say that again", "say it again", "come again",
            "what did you say", "repeat what you said", "pardon", "again",
        )

        private val REMINDER_PREFIXES = listOf(
            "remind me", "set a reminder", "set reminder", "create a reminder", "create reminder", "add a reminder",
        )
        private const val UNIT = "(minutes?|mins?|hours?|hrs?|h|m)"
        private const val AMOUNT = "(\\d{1,5}|an|a|one|two|three|four|five|six|seven|eight|nine|ten|" +
            "eleven|twelve|fifteen|twenty|thirty|forty|fortyfive|forty five|fifty|sixty|ninety)"
        private val TRAILING_DELAY = Regex("(?:to |about |for )?(.+?) in $AMOUNT $UNIT")
        private val LEADING_DELAY = Regex("in $AMOUNT $UNIT (?:to |about |for )?(.+)")

        private val NUMBER_WORDS = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
            "fortyfive" to 45, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        )

        private fun parseNumber(s: String): Int? = NUMBER_WORDS[s] ?: s.toIntOrNull()

        private val SETTINGS_VERBS = listOf("open up", "open", "go to", "show me", "show", "launch", "take me to")
        private val SETTINGS_SUBJECTS = mapOf(
            "wifi" to SettingsDestination.WIFI,
            "wireless" to SettingsDestination.WIFI,
            "internet" to SettingsDestination.WIFI,
            "network" to SettingsDestination.WIFI,
            "bluetooth" to SettingsDestination.BLUETOOTH,
            "sound" to SettingsDestination.SOUND,
            "volume" to SettingsDestination.SOUND,
            "audio" to SettingsDestination.SOUND,
            "display" to SettingsDestination.DISPLAY,
            "screen" to SettingsDestination.DISPLAY,
            "brightness" to SettingsDestination.DISPLAY,
            "battery" to SettingsDestination.BATTERY,
            "battery saver" to SettingsDestination.BATTERY,
            "power" to SettingsDestination.BATTERY,
            "notification" to SettingsDestination.NOTIFICATIONS,
            "notifications" to SettingsDestination.NOTIFICATIONS,
            "notification access" to SettingsDestination.NOTIFICATION_ACCESS,
            "app notification" to SettingsDestination.NOTIFICATIONS,
            "app notifications" to SettingsDestination.NOTIFICATIONS,
            "app" to SettingsDestination.APP_SETTINGS,
            "jarvis" to SettingsDestination.APP_SETTINGS,
            "jarvis app" to SettingsDestination.APP_SETTINGS,
            "phone" to SettingsDestination.MAIN,
            "system" to SettingsDestination.MAIN,
            "device" to SettingsDestination.MAIN,
        )
        private val BARE_SETTINGS = mapOf(
            "wifi" to SettingsDestination.WIFI,
            "bluetooth" to SettingsDestination.BLUETOOTH,
            "battery saver" to SettingsDestination.BATTERY,
            "notification access" to SettingsDestination.NOTIFICATION_ACCESS,
        )

        private val NOTIFICATION_PATTERN = Regex(
            "(read|tell me|check|show|what are|whats|what is|any|do i have)( me)?( any)?( my| the)?( new| latest| last| recent)? " +
                "(notifications?)( please)?|(latest|last|new|recent) notifications?|notifications?",
        )
        private val BATTERY_PATTERN = Regex(
            "battery|battery level|battery status|battery percentage|(whats|what is|how is|hows|check|tell me|show)( me)? " +
                "(my |the )?(phone )?battery( level| status| percentage| life)?|how much battery( do i have| is left| left)?|" +
                "how much charge( do i have| is left| left)?|is (my |the )?(phone )?(battery )?charging",
        )
        private val TIME_PATTERN = Regex(
            "time|what time is it|whats the time|what is the time|tell me the time|current time|whats the current time|" +
                "what is the current time|do you know the time|what time is it now|the time",
        )
        private val OPEN_APP = Regex("(open up|open|launch|start|run|load) (?:the )?(?:app )?(.+)")
        private val NOT_APP_NAMES = setOf("listening", "it", "that", "this", "something", "an app", "app")
    }
}
