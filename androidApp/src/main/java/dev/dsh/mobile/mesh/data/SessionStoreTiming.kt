package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope

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
