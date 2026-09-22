package dev.dsh.mobile.mesh.ui.screens.connect

/** Address shown in connection errors: SSH failures happen at the SSH server port, not DSH port. */
internal fun connectionAttemptAuthority(
    host: String,
    harnessPort: Int,
    sshEnabled: Boolean,
    sshPort: Int?,
): String = if (sshEnabled && sshPort != null) "$host:$sshPort" else "$host:$harnessPort"
