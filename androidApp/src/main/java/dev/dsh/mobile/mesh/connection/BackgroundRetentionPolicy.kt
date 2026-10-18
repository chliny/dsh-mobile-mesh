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
