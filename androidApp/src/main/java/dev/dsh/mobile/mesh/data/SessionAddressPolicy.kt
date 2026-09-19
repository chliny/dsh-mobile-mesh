package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SessionAddress

internal fun sessionAddressFor(
    sessionId: String,
    origin: String?,
    parentSessionId: String? = null,
    mode: String? = null,
): SessionAddress? = if (origin == "subagent") {
    if (parentSessionId.isNullOrBlank() || mode.isNullOrBlank()) null
    else SessionAddress.Subagent(parentSessionId = parentSessionId, childSessionId = sessionId, mode = mode)
} else {
    SessionAddress.Session(sessionId = sessionId)
}
