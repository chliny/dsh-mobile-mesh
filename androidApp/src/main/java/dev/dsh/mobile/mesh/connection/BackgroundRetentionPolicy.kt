package dev.dsh.mobile.mesh.connection

/** Start an opt-in retention service only when a foreground start is permitted. */
internal fun shouldStartConnectionService(
    keepConnectedInBackground: Boolean,
    appInForeground: Boolean,
): Boolean = keepConnectedInBackground && appInForeground
