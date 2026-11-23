package dev.dsh.mobile.mesh.data

import javax.inject.Inject
import javax.inject.Singleton

/** Process-scoped composer drafts, keyed by session so screen navigation does not lose input. */
@Singleton
class ChatDraftStore @Inject constructor() {
    private val drafts = mutableMapOf<String, String>()

    /**
     * References produced by another screen (the forge changes view) and waiting to be merged into
     * the Session's composer the next time that composer is composed. They cannot be written into
     * [drafts] directly: a blank Session's authoritative draft lives in the new-session store
     * instead, and only ChatScreen knows which bucket currently owns it.
     */
    private val pendingInsertions = mutableMapOf<String, MutableList<String>>()

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

    /** Queue one frozen reference for the Session's composer. */
    @Synchronized
    fun appendPending(sessionId: String, text: String) {
        if (text.isEmpty()) return
        pendingInsertions.getOrPut(sessionId) { mutableListOf() }.add(text)
    }

    /** Take every reference waiting for the Session, oldest first, separated by a blank line. */
    @Synchronized
    fun takePending(sessionId: String): String =
        pendingInsertions.remove(sessionId).orEmpty().joinToString("\n\n")
}
