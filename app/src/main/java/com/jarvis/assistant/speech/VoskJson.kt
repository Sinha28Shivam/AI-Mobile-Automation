package com.jarvis.assistant.speech

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One recognized word with Vosk's per-word confidence (0..1). */
data class RecognizedWord(val word: String, val confidence: Float)

/**
 * Parses Vosk recognizer JSON. Final results (with setWords(true)) look like
 * `{"result":[{"conf":1.0,"start":0.09,"end":0.3,"word":"hey"}],"text":"hey"}`;
 * partial results look like `{"partial":"hey jar"}`.
 */
object VoskJson {
    const val UNKNOWN_TOKEN = "[unk]"

    fun parseWords(json: String): List<RecognizedWord> = try {
        val root = JSONObject(json)
        val array = root.optJSONArray("result")
        if (array == null) {
            // No word-level info (e.g. empty result): use text with neutral confidence.
            root.optString("text").split(' ').filter { it.isNotBlank() }.map { RecognizedWord(it, 1f) }
        } else {
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val word = item.optString("word")
                if (word.isBlank()) null
                else RecognizedWord(word, item.optDouble("conf", 0.0).toFloat().coerceIn(0f, 1f))
            }
        }
    } catch (e: JSONException) {
        emptyList()
    }

    fun parsePartial(json: String): String = try {
        JSONObject(json).optString("partial")
    } catch (e: JSONException) {
        ""
    }

    /** Builds a Vosk grammar JSON array, always ending with the unknown-word token. */
    fun grammar(phrases: List<String>): String {
        val array = JSONArray()
        phrases.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().forEach { array.put(it) }
        array.put(UNKNOWN_TOKEN)
        return array.toString()
    }

    /** Joins words (excluding [unk]) into a transcript and averages their confidence. */
    fun toResult(words: List<RecognizedWord>, endReason: UtteranceEnd): SpeechResult {
        val known = words.filter { it.word != UNKNOWN_TOKEN }
        if (known.isEmpty()) return SpeechResult("", 0f, endReason)
        val text = known.joinToString(" ") { it.word }
        val confidence = known.map { it.confidence }.average().toFloat()
        return SpeechResult(text, confidence, endReason)
    }
}
