package dev.dsh.mobile.mesh.connection

/** Start a retention foreground service only while Android permits a foreground launch. */
internal fun shouldStartConnectionService(
    keepConnectedInBackground: Boolean,
    appInForeground: Boolean,
): Boolean = keepConnectedInBackground && appInForeground
