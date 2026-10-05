package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import kotlinx.coroutines.flow.MutableStateFlow

/** Publish the cache before the ID that makes the drawer navigate to the transcript. */
internal fun publishSelectedConversation(
    selected: MutableStateFlow<String?>,
    conversation: MutableStateFlow<ConversationSnapshot?>,
    sessionId: String,
    cached: ConversationSnapshot?,
) {
    conversation.value = cached?.takeIf { it.sessionId == sessionId }
    selected.value = sessionId
}

/** A journal cannot be folded until its own follow baseline has installed a log cut. */
internal fun canRebuildFromFollow(cursor: Int?): Boolean = cursor != null

/** Returns the durable start time of the latest open turn, if the event suffix is still running. */
internal fun runningTurnStartMillis(events: List<SessionEventEnvelope>): Long? {
    var openStart: Long? = null
    for (event in events) {
        when (event.type) {
            "turn/start" -> openStart = event.time
            "turn/end" -> openStart = null
        }
    }
    return openStart
}
