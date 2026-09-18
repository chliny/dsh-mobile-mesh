package dev.dsh.mobile.mesh.connection

/**
 * Merge a connection update before writing it to the remembered-host list.
 *
 * Transport setup frequently republishes the HostConfig it started with. That snapshot can have an
 * empty launchToken even though a newer pairing has already stored one. Never let that stale blank
 * snapshot erase the token belonging to the same remembered endpoint.
 */
internal fun mergeRememberedHost(existing: HostConfig?, incoming: HostConfig): HostConfig {
    if (existing == null) return incoming
    return incoming.copy(
        launchToken = incoming.launchToken.ifBlank { existing.launchToken },
    )
}
