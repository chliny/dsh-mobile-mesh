package dev.dsh.mobile.mesh.data

import javax.inject.Inject
import javax.inject.Singleton

/** Process-scoped composer drafts, keyed by session so screen navigation does not lose input. */
@Singleton
class ChatDraftStore @Inject constructor() {
    private val drafts = mutableMapOf<String, String>()

    @Synchronized
    fun get(sessionId: String): String = drafts[sessionId].orEmpty()

    @Synchronized
    fun set(sessionId: String, text: String) {
        if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
    }

    @Synchronized
    fun clear(sessionId: String) {
        drafts.remove(sessionId)
    }
}
