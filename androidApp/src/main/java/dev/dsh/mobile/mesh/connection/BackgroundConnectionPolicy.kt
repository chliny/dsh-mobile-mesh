package dev.dsh.mobile.mesh.connection

internal enum class BackgroundConnectionAction {
    RETAIN,
    SUSPEND,
}

/** Without background retention, Android lifecycle owns a hard transport suspension boundary. */
internal fun backgroundConnectionAction(
    keepConnectedInBackground: Boolean,
    activeFileTransfer: Boolean = false,
): BackgroundConnectionAction =
    if (keepConnectedInBackground || activeFileTransfer) BackgroundConnectionAction.RETAIN else BackgroundConnectionAction.SUSPEND

/** Keep the process-pinning service alive while a retained background carrier is being renewed. */
internal fun shouldStopConnectionServiceOnRetire(
    reconnecting: Boolean,
    keepConnectedInBackground: Boolean,
): Boolean = !reconnecting || !keepConnectedInBackground
