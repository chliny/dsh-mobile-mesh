package dev.dsh.mobile.mesh.connection

/** Start an opt-in retention service only when a foreground start is permitted. */
internal fun shouldStartConnectionService(
    keepConnectedInBackground: Boolean,
    appInForeground: Boolean,
): Boolean = keepConnectedInBackground && appInForeground

/** A background setting change must suspend an already retained carrier, not just its service. */
internal fun shouldSuspendAfterRetentionDisabled(
    keepConnectedInBackground: Boolean,
    appInForeground: Boolean,
    hasDesiredHost: Boolean,
    activeFileTransfer: Boolean,
    alreadySuspended: Boolean,
): Boolean = !keepConnectedInBackground && !appInForeground && hasDesiredHost &&
    !activeFileTransfer && !alreadySuspended

/** Stop requests must not outrun outstanding startForegroundService() acknowledgements. */
internal class ForegroundServiceStartGate {
    private var pendingStarts = 0
    private var desiredRunning = false

    @Synchronized fun requestStart() {
        pendingStarts++
        desiredRunning = true
    }

    /** Whether the manager may stop the service immediately. */
    @Synchronized fun requestStop(): Boolean {
        desiredRunning = false
        return pendingStarts == 0
    }

    /** Whether the service should stop after calling startForeground() for this start. */
    @Synchronized fun foregroundStarted(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        return pendingStarts == 0 && !desiredRunning
    }

    /** Whether the manager should stop the service after a failed pending start. */
    @Synchronized fun startFailed(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        return pendingStarts == 0 && !desiredRunning
    }
}
