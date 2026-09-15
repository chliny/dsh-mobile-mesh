package dev.dsh.mobile.mesh.connection

internal enum class BackgroundConnectionAction {
    RETAIN,
    SUSPEND,
}

/** Without background retention, Android lifecycle owns a hard transport suspension boundary. */
internal fun backgroundConnectionAction(keepConnectedInBackground: Boolean): BackgroundConnectionAction =
    if (keepConnectedInBackground) BackgroundConnectionAction.RETAIN else BackgroundConnectionAction.SUSPEND
