package com.jarvis.assistant.commands

/**
 * Validates untrusted model output against an exact, allow-listed schema. Anything else is
 * rejected (returns null). Model output can never produce code, shell commands, intents,
 * package names or tools outside this allow-list.
 *
 * ## Schema (exact)
 * ```
 * {"tool": "<tool>", "args": { <exactly the arguments listed for that tool> }}
 * ```
 * - The root must be one JSON object with exactly the keys `tool` (string) and `args` (object).
 *   Optionally wrapped in a single Markdown code fence. No other top-level keys.
 * - `args` must contain every listed argument and nothing else; values must be the listed type
 *   (no nested objects, arrays, floats, booleans or null).
 * - Duplicate keys, trailing content, input over [MAX_INPUT_BYTES] UTF-8 bytes, control
 *   characters, invisible format characters (zero-width / bidi overrides) and surrogate
 *   code units (emoji, non-BMP look-alikes) are rejected anywhere in the input.
 *
 * | tool                       | args                                                              |
 * |----------------------------|-------------------------------------------------------------------|
 * | `open_app`                 | `app_name`: string, 1..[MAX_APP_NAME] chars of letters, digits, space, `. & + ' _ -` |
 * | `get_battery`              | none                                                              |
 * | `get_time`                 | none                                                              |
 * | `get_date`                 | none                                                              |
 * | `open_settings`            | `destination`: one of [SettingsDestination] names (case-insensitive) |
 * | `read_latest_notification` | none                                                              |
 * | `create_reminder`          | `title`: string 1..100 chars; `delay_minutes`: integer 1..1440    |
 *
 * Stop and Repeat are intentionally not available to a model. Consequential results
 * ([JarvisCommand.isConsequential]) must still be confirmed by the user before execution.
 */
class ModelCommandValidator {

    fun validate(raw: String?): JarvisCommand? {
        if (raw == null || raw.length > MAX_INPUT_BYTES) return null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) return null
        if (raw.any { isForbiddenChar(it) }) return null
        val text = stripCodeFence(raw.trim())
        val root = try {
            MiniJson(text).parseRoot()
        } catch (_: MiniJson.JsonException) {
            return null
        }
        if (root.keys != ROOT_KEYS) return null
        val tool = root["tool"] as? String ?: return null
        val rawArgs = root["args"] as? Map<*, *> ?: return null
        val args: Map<String, Any?> = rawArgs.entries.associate { (k, v) -> k as String to v }
        val spec = TOOLS[tool] ?: return null
        if (args.keys != spec) return null
        // Only scalar string/integer values are ever allowed as arguments.
        if (args.values.any { it !is String && it !is Long }) return null
        if (args.values.any { it is String && it.any(::isForbiddenValueChar) }) return null
        return build(tool, args)
    }

    private fun build(tool: String, args: Map<String, Any?>): JarvisCommand? = when (tool) {
        "open_app" -> {
            val name = (args["app_name"] as? String)?.trim()
            if (name != null && name.isNotEmpty() && name.length <= MAX_APP_NAME && APP_NAME_CHARS.matches(name) &&
                isSingleScript(name)
            ) {
                JarvisCommand.OpenApp(name)
            } else {
                null
            }
        }
        "get_battery" -> JarvisCommand.GetBattery
        "get_time" -> JarvisCommand.GetTime
        "get_date" -> JarvisCommand.GetDate
        "open_settings" -> {
            val dest = args["destination"] as? String
            SettingsDestination.entries.firstOrNull { it.name.equals(dest, ignoreCase = true) }
                ?.let { JarvisCommand.OpenSettings(it) }
        }
        "read_latest_notification" -> JarvisCommand.ReadLatestNotification
        "create_reminder" -> {
            val title = args["title"] as? String
            val delay = args["delay_minutes"] as? Long
            if (title == null || delay == null || delay !in Int.MIN_VALUE..Int.MAX_VALUE) {
                null
            } else {
                JarvisCommand.CreateReminder.create(title, delay.toInt())
            }
        }
        else -> null
    }

    /** Rejects homoglyph tricks such as Latin mixed with Cyrillic ("YоuTube" with a Cyrillic "о"). */
    private fun isSingleScript(s: String): Boolean =
        s.filter { it.isLetter() }
            .map { Character.UnicodeScript.of(it.code) }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
            .toSet().size <= 1

    private fun isForbiddenChar(c: Char): Boolean =
        (c.isISOControl() && c != '\n' && c != '\r' && c != '\t') || isInvisibleOrSurrogate(c)

    private fun isForbiddenValueChar(c: Char): Boolean = c.isISOControl() || isInvisibleOrSurrogate(c)

    private fun isInvisibleOrSurrogate(c: Char): Boolean =
        c.isSurrogate() || Character.getType(c) == Character.FORMAT.toInt() ||
            c == '\u2028' || c == '\u2029' || c == '\uFEFF'

    private fun stripCodeFence(s: String): String {
        if (!s.startsWith("```")) return s
        val body = s.removePrefix("```").removePrefix("json").removePrefix("JSON")
        return body.removeSuffix("```").trim()
    }

    companion object {
        const val MAX_INPUT_BYTES = 2048
        const val MAX_APP_NAME = 50
        private val ROOT_KEYS = setOf("tool", "args")
        private val APP_NAME_CHARS = Regex("[\\p{L}\\p{N} .&+'_-]+")

        /** Allow-listed tools and their exact required argument names. */
        val TOOLS: Map<String, Set<String>> = mapOf(
            "open_app" to setOf("app_name"),
            "get_battery" to emptySet(),
            "get_time" to emptySet(),
            "get_date" to emptySet(),
            "open_settings" to setOf("destination"),
            "read_latest_notification" to emptySet(),
            "create_reminder" to setOf("title", "delay_minutes"),
        )
    }
}

/**
 * Minimal strict JSON parser: objects (max depth 2), strings, integers, booleans, null.
 * Rejects arrays, floats, duplicate keys and trailing content.
 */
internal class MiniJson(private val s: String) {
    class JsonException(message: String) : Exception(message)

    private var i = 0

    fun parseRoot(): Map<String, Any?> {
        skipWs()
        val obj = parseObject(depth = 1)
        skipWs()
        if (i != s.length) fail("trailing content")
        return obj
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        if (depth > MAX_DEPTH) fail("too deep")
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            i++
            return out
        }
        while (true) {
            skipWs()
            val key = parseString()
            if (out.containsKey(key)) fail("duplicate key")
            skipWs()
            expect(':')
            skipWs()
            out[key] = parseValue(depth)
            if (out.size > MAX_KEYS) fail("too many keys")
            skipWs()
            when (next()) {
                ',' -> continue
                '}' -> return out
                else -> fail("expected , or }")
            }
        }
    }

    private fun parseValue(depth: Int): Any? = when (val c = peek()) {
        '"' -> parseString()
        '{' -> parseObject(depth + 1)
        't' -> literal("true", true)
        'f' -> literal("false", false)
        'n' -> literal("null", null)
        else -> if (c == '-' || c in '0'..'9') parseInt() else fail("unexpected value")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!s.startsWith(word, i)) fail("bad literal")
        i += word.length
        return value
    }

    private fun parseInt(): Long {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) fail("non-integer number")
        val token = s.substring(start, i)
        if (token.length > 10) fail("number too long")
        return token.toLongOrNull() ?: fail("bad number")
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad unicode escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> fail("bad escape")
                    }
                }
                c < ' ' -> fail("control char in string")
                else -> sb.append(c)
            }
        }
    }

    private fun skipWs() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")
    private fun next(): Char = peek().also { i++ }
    private fun expect(c: Char) {
        if (next() != c) fail("expected $c")
    }

    private fun fail(msg: String): Nothing = throw JsonException(msg)

    private companion object {
        const val MAX_DEPTH = 2
        const val MAX_KEYS = 8
    }
}
