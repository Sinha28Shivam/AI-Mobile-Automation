package com.jarvis.assistant.wakeword

/**
 * Removes a leading wake phrase from a command transcript, so "hey jarvis open youtube"
 * said in one breath (when the rest of the utterance reaches the command recognizer)
 * becomes "open youtube" and the wake phrase is never treated as part of the command.
 * Only leading occurrences are removed; the rest of the text is returned unchanged.
 */
object WakePhrase {
    private val PREFIXES = setOf("hey", "hi", "ok", "okay")
    private val WHITESPACE = Regex("\\s+")

    fun stripLeading(text: String, keyword: String = WakeWordDecider.DEFAULT_KEYWORD): String {
        val key = keyword.trim().lowercase()
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].lowercase()
            i += when {
                word == key -> 1
                word in PREFIXES && tokens.getOrNull(i + 1)?.lowercase() == key -> 2
                else -> break
            }
        }
        return tokens.drop(i).joinToString(" ")
    }
}
