package com.jarvis.assistant.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

class WakePhraseTest {
    @Test
    fun stripsLeadingWakePhrase() {
        assertEquals("open youtube", WakePhrase.stripLeading("hey jarvis open youtube"))
        assertEquals("open youtube", WakePhrase.stripLeading("Jarvis open youtube"))
        assertEquals("what time is it", WakePhrase.stripLeading("jarvis hey jarvis what time is it"))
        assertEquals("", WakePhrase.stripLeading("hey jarvis"))
    }

    @Test
    fun keepsNonLeadingWordsAndPlainCommands() {
        assertEquals("open youtube", WakePhrase.stripLeading("open youtube"))
        assertEquals("hey there", WakePhrase.stripLeading("hey there"))
        assertEquals("tell jarvis hello", WakePhrase.stripLeading("tell jarvis hello"))
    }
}
