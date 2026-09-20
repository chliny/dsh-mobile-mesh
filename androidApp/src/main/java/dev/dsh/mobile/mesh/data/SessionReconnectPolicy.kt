package dev.dsh.mobile.mesh.data

/** A reconnect must reopen the selected session's generation-bound follow stream. */
internal fun shouldReopenSessionAfterReconnect(
    hasSelectedSession: Boolean,
    hasConnectionGeneration: Boolean,
): Boolean = hasSelectedSession && hasConnectionGeneration
