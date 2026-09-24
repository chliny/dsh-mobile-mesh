package dev.dsh.mobile.mesh.data

/** A successful command is sufficient to release the optimistic UI lock for its own session. */
internal fun shouldClearPendingPermission(
    currentSessionId: String?,
    resultSessionId: String,
    pendingValue: String?,
    resultValue: String,
): Boolean = currentSessionId == resultSessionId && pendingValue == resultValue
