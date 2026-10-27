package com.jarvis.assistant.commands

/**
 * Deterministic, pure-Kotlin parser from recognized speech to [JarvisCommand].
 * It works without any model and is the primary command path. It never logs its input.
 */
class CommandParser {

    /** Classification of a reply to a pending yes/no question. */
    enum class Answer { YES, NO, OTHER }

    /**
     * @param confidence optional recognizer confidence in 0..1; below [MIN_CONFIDENCE] yields Unknown,
     *   except Stop, which is always honoured because stopping is never harmful.
     */
    fun parse(text: String, confidence: Float? = null): JarvisCommand {
        if (text.length > MAX_INPUT_LENGTH) return JarvisCommand.Unknown(text)
        val n = normalize(text)
        if (n.isEmpty()) return JarvisCommand.Unknown(text)
        parseStop(n)?.let { return it }
        if (confidence != null && confidence < MIN_CONFIDENCE) return JarvisCommand.Unknown(text)

        return parseRepeat(n)
            ?: parseReminder(n)
            ?: parseToggle(n)
            ?: parseSettings(n)
            ?: parseNotifications(n)
            ?: parseCharging(n)
            ?: parseBattery(n)
            ?: parseDate(n)
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
        val prefix = REMINDER_PREFIXES.firstOrNull { n == it || n.startsWith("$it ") } ?: return null
        val rest = n.removePrefix(prefix).trim()
        if (rest.isEmpty()) return invalidReminder(JarvisCommand.InvalidReminder.Reason.MISSING_DELAY)

        // "remind me to X in N minutes" or "remind me in N minutes to X"
        val trailing = TRAILING_DELAY.matchEntire(rest)
        val leading = LEADING_DELAY.matchEntire(rest)
        val (title, amount, unit) = when {
            trailing != null -> Triple(trailing.groupValues[1], trailing.groupValues[2], trailing.groupValues[3])
            leading != null -> Triple(leading.groupValues[3], leading.groupValues[1], leading.groupValues[2])
            else -> return invalidReminder(JarvisCommand.InvalidReminder.Reason.MISSING_DELAY)
        }
        val minutes = delayMinutes(amount, unit)
            ?: return invalidReminder(JarvisCommand.InvalidReminder.Reason.INVALID_DELAY)
        if (minutes !in JarvisCommand.CreateReminder.MIN_DELAY_MINUTES..JarvisCommand.CreateReminder.MAX_DELAY_MINUTES) {
            return invalidReminder(JarvisCommand.InvalidReminder.Reason.INVALID_DELAY)
        }
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isEmpty()) return invalidReminder(JarvisCommand.InvalidReminder.Reason.EMPTY_TITLE)
        if (cleanTitle.length > JarvisCommand.CreateReminder.MAX_TITLE_LENGTH) {
            return invalidReminder(JarvisCommand.InvalidReminder.Reason.TITLE_TOO_LONG)
        }
        return JarvisCommand.CreateReminder.create(cleanTitle, minutes)
            ?: invalidReminder(JarvisCommand.InvalidReminder.Reason.EMPTY_TITLE)
    }

    private fun invalidReminder(reason: JarvisCommand.InvalidReminder.Reason) = JarvisCommand.InvalidReminder(reason)

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        for (w in TITLE_LEAD_WORDS) {
            if (t == w) return ""
            if (t.startsWith("$w ")) {
                t = t.removePrefix("$w ").trim()
                break
            }
        }
        return t
    }

    private fun parseToggle(n: String): JarvisCommand? {
        val m = TOGGLE.matchEntire(n) ?: return null
        val subject = m.groups.drop(1).mapNotNull { it?.value }.firstOrNull { it in TOGGLE_SUBJECTS } ?: return null
        return JarvisCommand.UnsupportedToggle(TOGGLE_SUBJECTS.getValue(subject))
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
        val hasSettingsWord = s.endsWith(" settings") || s.endsWith(" setting") || s == "settings" || s == "setting"
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

    private fun parseCharging(n: String): JarvisCommand? =
        if (n.contains("charging") && CHARGING_PATTERN.matches(n)) JarvisCommand.GetChargingStatus else null

    private fun parseBattery(n: String): JarvisCommand? {
        if (!n.contains("battery") && !n.contains("charg")) return null
        return if (BATTERY_PATTERN.matches(n)) JarvisCommand.GetBattery else null
    }

    private fun parseDate(n: String): JarvisCommand? =
        if (DATE_PATTERN.matches(n)) JarvisCommand.GetDate else null

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

        private val WAKE_PREFIXES = listOf(
            "hey jarvis", "hi jarvis", "hello jarvis", "ok jarvis", "okay jarvis", "jarvis",
        )
        private val LEADING_FILLERS = listOf(
            "can you", "could you", "would you", "will you", "please", "kindly",
            "i want to", "i would like to", "id like to", "i need to", "lets", "just", "um", "uh", "hmm",
        )

        /**
         * Common offline-recognizer (Vosk) mis-splits and mis-hearings, applied on word boundaries
         * after punctuation removal and before wake-word stripping.
         */
        private val SUBSTITUTIONS: List<Pair<Regex, String>> = listOf(
            "jervis|jarvas|jarvice|jarves|jarvi" to "jarvis",
            "you tube" to "youtube",
            "whats app|what s app|whats up|what sap|watts app|whatsup" to "whatsapp",
            "wi fi|why fi|wy fi|wai fi|wii fi|wifi" to "wifi",
            "blue tooth|blue tube" to "bluetooth",
            "face book" to "facebook",
            "insta gram" to "instagram",
            "g mail|gee mail" to "gmail",
        ).map { (pattern, replacement) -> Regex("\\b(?:$pattern)\\b") to replacement }

        /**
         * Lowercases, removes punctuation (apostrophes and hyphens join words: "what's"→"whats",
         * "wi-fi"→"wifi"; a minus before a digit becomes "minus"), fixes common recognizer
         * substitutions, strips wake phrase, polite fillers and trailing "please"/"jarvis".
         */
        fun normalize(text: String): String {
            var s = text.lowercase()
                .replace("'", "").replace("\u2019", "")
                .replace(Regex("(^|\\s)[-\u2212](?=\\d)"), "$1minus ")
                .replace("-", "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            for ((pattern, replacement) in SUBSTITUTIONS) s = s.replace(pattern, replacement)
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

        /** Classifies a reply to a yes/no question. Anything not clearly yes or no is [Answer.OTHER]. */
        fun classifyAnswer(text: String): Answer {
            if (text.length > MAX_INPUT_LENGTH) return Answer.OTHER
            val n = normalize(text)
            return when (n) {
                in YES_PHRASES -> Answer.YES
                in NO_PHRASES -> Answer.NO
                else -> Answer.OTHER
            }
        }

        private val YES_PHRASES = setOf(
            "yes", "yeah", "yep", "yup", "yes yes", "sure", "sure thing", "ok", "okay", "ok do it", "okay do it",
            "confirm", "confirmed", "i confirm", "do it", "yes do it", "go ahead", "yes go ahead",
            "open it", "yes open it", "correct", "thats right", "affirmative",
        )
        private val NO_PHRASES = setOf(
            // "not now" normalizes to "not" because a trailing "now" is stripped.
            "no", "nope", "nah", "no no", "no thanks", "no thank you", "not", "not now", "cancel", "cancel it",
            "cancel that", "dont", "dont do it", "no dont", "do not", "stop", "never mind", "nevermind", "negative",
        )

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
        private val TITLE_LEAD_WORDS = listOf("to", "about", "for", "that")
        private const val UNIT = "(minutes?|mins?|hours?|hrs?|h|m|seconds?|secs?)"
        private const val AMOUNT = "(\\S+(?: \\S+){0,2})"
        private val TRAILING_DELAY = Regex("(.*?) ?\\bin $AMOUNT $UNIT")
        private val LEADING_DELAY = Regex("in $AMOUNT $UNIT(?: (.*))?")

        private val UNIT_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        private val TEEN_WORDS = listOf(
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        private val TENS_WORDS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50)

        /** Number words one..sixty (plus "a"/"an" and "ninety"), with or without a space in compounds. */
        internal val NUMBER_WORDS: Map<String, Int> = buildMap {
            put("a", 1)
            put("an", 1)
            UNIT_WORDS.forEachIndexed { i, w -> put(w, i + 1) }
            TEEN_WORDS.forEachIndexed { i, w -> put(w, i + 10) }
            for ((tens, value) in TENS_WORDS) {
                put(tens, value)
                UNIT_WORDS.forEachIndexed { i, u ->
                    put("$tens $u", value + i + 1)
                    put("$tens$u", value + i + 1)
                }
            }
            put("sixty", 60)
            put("ninety", 90)
        }

        internal fun parseNumber(s: String): Int? {
            NUMBER_WORDS[s]?.let { return it }
            if (s.length > 5 || s.any { it !in '0'..'9' }) return null
            return s.toIntOrNull()
        }

        /** Null for unsupported amounts/units (e.g. seconds, "a few", negative numbers). */
        private fun delayMinutes(amount: String, unit: String): Int? {
            if (unit.startsWith("s")) return null
            val hours = unit.startsWith("h")
            if (hours && (amount == "half an" || amount == "half a")) return 30
            val number = parseNumber(amount) ?: return null
            val minutes = if (hours) number.toLong() * 60 else number.toLong()
            return if (minutes > Int.MAX_VALUE) null else minutes.toInt()
        }

        private val TOGGLE_SUBJECTS = mapOf(
            "wifi" to SettingsDestination.WIFI,
            "bluetooth" to SettingsDestination.BLUETOOTH,
            "battery saver" to SettingsDestination.BATTERY,
            "dark mode" to SettingsDestination.DISPLAY,
        )
        private val TOGGLE: Regex = run {
            val subj = "(wifi|bluetooth|battery saver|dark mode)"
            Regex(
                "(?:turn|switch) (?:on|off) (?:the |my )?$subj|(?:turn|switch) (?:the |my )?$subj (?:on|off)|" +
                    "(?:enable|disable|activate|deactivate) (?:the |my )?$subj",
            )
        }

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
        private val CHARGING_PATTERN = Regex(
            "(is|are) (it|my phone|the phone|my battery|the battery|my device|the device|phone|battery) " +
                "(currently |still )?charging|am i charging|charging status|(whats|what is) (my |the )?charging status",
        )
        private val BATTERY_PATTERN = Regex(
            "battery|battery level|battery status|battery percentage|(whats|what is|how is|hows|check|tell me|show)( me)? " +
                "(my |the )?(phone )?battery( level| status| percentage| life)?|how much battery( do i have| is left| left)?|" +
                "how much charge( do i have| is left| left)?",
        )
        private val DATE_PATTERN = Regex(
            "date|the date|todays date|(whats|what is|tell me) (the |todays )?date( today)?|" +
                "(what|which) day is (it|today)( today)?|(whats|what is) today|(whats|what is) the day today",
        )
        private val TIME_PATTERN = Regex(
            "time|what time is it|whats the time|what is the time|tell me the time|current time|whats the current time|" +
                "what is the current time|do you know the time|what time is it now|the time",
        )
        private val OPEN_APP = Regex("(open up|open|launch|start|run|load) (?:the )?(?:app )?(.+)")
        private val NOT_APP_NAMES = setOf("listening", "it", "that", "this", "something", "an app", "app")
    }
}
