package dev.dsh.mobile.mesh.data

/** An older independently-produced baseline must not overwrite a newer live inbox projection. */
internal fun shouldApplyInboxProjection(previousSeq: Int?, incomingSeq: Int?): Boolean =
    incomingSeq == null || previousSeq == null || incomingSeq >= previousSeq

/** Watermarks are meaningful only within one host generation; callers hold the store lock. */
internal class InboxProjectionWatermarks {
    private val bySession = mutableMapOf<String, Int>()

    fun accept(sessionId: String, incomingSeq: Int?): Boolean {
        val previousSeq = bySession[sessionId]
        if (!shouldApplyInboxProjection(previousSeq, incomingSeq)) return false
        if (incomingSeq != null && (previousSeq == null || incomingSeq > previousSeq)) {
            bySession[sessionId] = incomingSeq
        }
        return true
    }

    fun reset() {
        bySession.clear()
    }
}
