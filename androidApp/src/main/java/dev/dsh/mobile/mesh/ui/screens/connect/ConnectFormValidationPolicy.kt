package dev.dsh.mobile.mesh.ui.screens.connect

internal fun isConnectFormValid(
    host: String,
    port: String,
    sshEnabled: Boolean,
    sshUsername: String,
    sshPort: String,
    sshDshHost: String,
    secret: String,
    tailscaleHostname: String = "",
): Boolean = host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true &&
    (tailscaleHostname.isBlank() || tailscaleHostname.matches(Regex("[A-Za-z0-9-]{1,63}"))) &&
    (!sshEnabled || (sshUsername.isNotBlank() && sshPort.toIntOrNull()?.let { it in 1..65535 } == true &&
        sshDshHost.isNotBlank() && secret.isNotBlank()))
