package dev.dsh.mobile.mesh.ui.screens.main

internal enum class TerminalInputFailure { UNAVAILABLE, FULL }

/** Report rejected input instead of silently losing keys while not controlling the PTY. */
internal fun terminalInputFailure(
    queue: TerminalInputQueue?,
    data: String,
    maxBytes: Int = Int.MAX_VALUE,
): TerminalInputFailure? {
    if (queue == null) return TerminalInputFailure.UNAVAILABLE
    return try {
        if (queue.offer(data, maxBytes)) null else TerminalInputFailure.FULL
    } catch (_: IllegalArgumentException) {
        TerminalInputFailure.FULL
    }
}
