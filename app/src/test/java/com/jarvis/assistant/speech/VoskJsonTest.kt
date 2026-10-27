package com.jarvis.assistant.speech

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskJsonTest {

    // Captured from vosk-model-small-en-us-0.15 output format.
    private val finalJson = """
        {"result":[{"conf":1.000000,"end":0.3,"start":0.09,"word":"hey"},
                   {"conf":0.5,"end":0.87,"start":0.3,"word":"jarvis"}],
         "text":"hey jarvis"}
    """.trimIndent()

    @Test
    fun parsesWordsWithConfidence() {
        val words = VoskJson.parseWords(finalJson)
        assertEquals(listOf(RecognizedWord("hey", 1f), RecognizedWord("jarvis", 0.5f)), words)
    }

    @Test
    fun emptyAndMalformedResults() {
        assertTrue(VoskJson.parseWords("""{"text":""}""").isEmpty())
        assertTrue(VoskJson.parseWords("not json").isEmpty())
        assertEquals("", VoskJson.parsePartial("garbage"))
        assertEquals("hey jar", VoskJson.parsePartial("""{"partial":"hey jar"}"""))
    }

    @Test
    fun textWithoutWordArrayFallsBack() {
        assertEquals(listOf(RecognizedWord("hello", 1f)), VoskJson.parseWords("""{"text":"hello"}"""))
    }

    @Test
    fun resultAveragesConfidenceAndDropsUnknown() {
        val words = listOf(RecognizedWord("open", 0.8f), RecognizedWord("[unk]", 0.1f), RecognizedWord("youtube", 0.4f))
        val result = VoskJson.toResult(words, UtteranceEnd.EndOfSpeech)
        assertEquals("open youtube", result.text)
        assertEquals(0.6f, result.confidence, 0.0001f)
    }

    @Test
    fun emptyResultHasZeroConfidence() {
        val result = VoskJson.toResult(listOf(RecognizedWord("[unk]", 1f)), UtteranceEnd.NoSpeech)
        assertTrue(result.isEmpty)
        assertEquals(0f, result.confidence)
        assertEquals(UtteranceEnd.NoSpeech, result.endReason)
    }

    @Test
    fun grammarIncludesUnknownTokenOnce() {
        val grammar = JSONArray(VoskJson.grammar(listOf("Hey Jarvis", "jarvis", " jarvis ", "")))
        assertEquals(listOf("hey jarvis", "jarvis", "[unk]"), (0 until grammar.length()).map { grammar.getString(it) })
    }
}
