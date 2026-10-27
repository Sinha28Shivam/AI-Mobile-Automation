package com.jarvis.assistant.commands

/**
 * Validates untrusted model output such as `{"tool":"open_app","args":{"app_name":"YouTube"}}`
 * against an allow-list of tools and typed arguments. Anything invalid is rejected (returns null).
 * Model output can never produce code, shell commands or tools outside this allow-list.
 */
class ModelCommandValidator {

    fun validate(raw: String?): JarvisCommand? {
        if (raw == null || raw.length > MAX_INPUT_BYTES) return null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) return null
        val text = stripCodeFence(raw.trim())
        val root = try {
            MiniJson(text).parseRoot()
        } catch (_: MiniJson.JsonException) {
            return null
        }
        if (root.keys.any { it !in ROOT_KEYS }) return null
        val tool = root["tool"] as? String ?: return null
        val args: Map<String, Any?> = when (val a = root["args"]) {
            null -> emptyMap()
            is Map<*, *> -> a.entries.associate { (k, v) -> k as String to v }
            else -> return null
        }
        val spec = TOOLS[tool] ?: return null
        if (args.keys.any { it !in spec }) return null
        if (spec.any { it !in args }) return null
        return build(tool, args)
    }

    private fun build(tool: String, args: Map<String, Any?>): JarvisCommand? = when (tool) {
        "open_app" -> {
            val name = (args["app_name"] as? String)?.trim()
            if (name != null && name.isNotEmpty() && name.length <= MAX_APP_NAME && APP_NAME_CHARS.matches(name)) {
                JarvisCommand.OpenApp(name)
            } else {
                null
            }
        }
        "get_battery" -> JarvisCommand.GetBattery
        "get_time" -> JarvisCommand.GetTime
        "open_settings" -> {
            val dest = args["destination"] as? String
            SettingsDestination.entries.firstOrNull { it.name.equals(dest, ignoreCase = true) }
                ?.let { JarvisCommand.OpenSettings(it) }
        }
        "stop_listening" -> JarvisCommand.StopListening
        "repeat" -> JarvisCommand.Repeat
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

    private fun stripCodeFence(s: String): String {
        if (!s.startsWith("```")) return s
        val body = s.removePrefix("```").removePrefix("json").removePrefix("JSON")
        return body.removeSuffix("```").trim()
    }

    companion object {
        const val MAX_INPUT_BYTES = 2048
        private const val MAX_APP_NAME = 60
        private val ROOT_KEYS = setOf("tool", "args")
        private val APP_NAME_CHARS = Regex("[\\p{L}\\p{N} .&+'_-]+")

        /** Allow-listed tools and their exact required argument names. */
        val TOOLS: Map<String, Set<String>> = mapOf(
            "open_app" to setOf("app_name"),
            "get_battery" to emptySet(),
            "get_time" to emptySet(),
            "open_settings" to setOf("destination"),
            "stop_listening" to emptySet(),
            "repeat" to emptySet(),
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
