package com.jarvis.assistant.commands

/** Injectable monotonic time source (milliseconds) so expiry logic is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.nanoTime() / 1_000_000L }
    }
}
