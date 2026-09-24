package dev.dsh.mobile.mesh.ui.screens.connect

/** Address shown in connection errors: SSH failures happen at the SSH server port, not DSH port. */
internal fun connectionAttemptAuthority(
    host: String,
    harnessPort: Int,
    sshEnabled: Boolean,
    sshPort: Int?,
): String = if (sshEnabled && sshPort != null) "$host:$sshPort" else "$host:$harnessPort"

/** Correct an SSH-attempt display when the failure happened before any connection was attempted. */
internal fun failureDisplayAuthority(
    attempted: String?,
    sshEnabled: Boolean,
    sshPort: Int?,
): String? {
    if (!sshEnabled || sshPort == null || attempted == null) return attempted
    val host = attempted.substringBeforeLast(':', attempted)
    val attemptedPort = attempted.substringAfterLast(':', "").toIntOrNull()
    return if (attemptedPort == 22 && sshPort != 22) "$host:$sshPort" else attempted
}
