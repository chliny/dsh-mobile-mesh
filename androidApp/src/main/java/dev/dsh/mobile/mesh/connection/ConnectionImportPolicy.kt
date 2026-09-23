package dev.dsh.mobile.mesh.connection

/** Identity deliberately excludes names, credentials, and optional mesh details. */
data class ConnectionIdentity(
    val host: String,
    val port: Int,
    val sshEnabled: Boolean,
    val transport: String,
)

internal fun HostConfig.connectionIdentity(): ConnectionIdentity = ConnectionIdentity(
    host = host.trim().lowercase(),
    port = port,
    sshEnabled = sshEnabled,
    transport = meshTransport?.storedValue ?: "direct",
)

data class ConnectionImportConflict(
    val imported: HostConfig,
    val existing: HostConfig,
) {
    val identity: ConnectionIdentity get() = imported.connectionIdentity()
}

internal fun findConnectionImportConflicts(
    imported: List<HostConfig>,
    existing: List<HostConfig>,
): List<ConnectionImportConflict> {
    val byIdentity = existing.associateBy(HostConfig::connectionIdentity)
    val seen = mutableSetOf<ConnectionIdentity>()
    return imported.mapNotNull { incoming ->
        val identity = incoming.connectionIdentity()
        if (!seen.add(identity)) return@mapNotNull null
        byIdentity[identity]?.let { ConnectionImportConflict(incoming, it) }
    }
}
