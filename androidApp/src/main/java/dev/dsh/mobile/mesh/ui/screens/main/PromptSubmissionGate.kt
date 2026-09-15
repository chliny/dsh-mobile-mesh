package dev.dsh.mobile.mesh.ui.screens.main

/** Prevents duplicate taps/IME submits while one prompt request is still in flight. */
internal class PromptSubmissionGate {
    private val inFlight = mutableSetOf<String>()

    @Synchronized
    fun tryAcquire(sessionId: String, text: String): Boolean =
        inFlight.add(key(sessionId, text))

    @Synchronized
    fun release(sessionId: String, text: String) {
        inFlight.remove(key(sessionId, text))
    }

    private fun key(sessionId: String, text: String): String = "$sessionId\u0000$text"
}
