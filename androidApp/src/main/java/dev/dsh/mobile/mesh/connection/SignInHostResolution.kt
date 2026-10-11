package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.ui.screens.connect.connectionAttemptAuthority

/** Resolve token submission to the failed connection even when its SSH dial authority differs. */
internal fun resolveSignInHost(
    signInHostId: String?,
    remembered: List<HostConfig>,
    activeHost: HostConfig?,
    attempted: String?,
): HostConfig? =
    signInHostId?.let { id -> remembered.firstOrNull { it.id == id } }
        ?: activeHost?.takeIf { it.id == signInHostId }
        ?: remembered.firstOrNull { it.authority == attempted }
        ?: activeHost?.takeIf { host ->
            connectionAttemptAuthority(host.host, host.port, host.sshEnabled, host.sshPort) == attempted
        }
