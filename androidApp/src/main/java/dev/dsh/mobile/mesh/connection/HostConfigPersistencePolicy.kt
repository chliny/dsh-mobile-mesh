package dev.dsh.mobile.mesh.connection

/** A saved row can be edited to point at a different server; its row ID alone is not a cache key. */
internal fun sameHarnessEndpoint(first: HostConfig, second: HostConfig): Boolean =
    first.host.trim().equals(second.host.trim(), ignoreCase = true) &&
        first.port == second.port && first.useTls == second.useTls &&
        first.meshTransport == second.meshTransport &&
        (first.meshTransport != MeshTransport.ZERO_TIER ||
            (first.zeroTierNetworkId == second.zeroTierNetworkId && first.zeroTierPlanetId == second.zeroTierPlanetId)) &&
        first.sshEnabled == second.sshEnabled &&
        (!first.sshEnabled || (first.sshPort == second.sshPort &&
            first.sshUsername == second.sshUsername && first.sshDshHost == second.sshDshHost))

/**
 * Preserve pairing tokens across stale blank updates, and confirmed API results across inconclusive
 * probes of the same endpoint. Editing the target must not borrow the previous server's API.
 */
internal fun mergeRememberedHost(existing: HostConfig?, incoming: HostConfig): HostConfig {
    if (existing == null) return incoming
    return incoming.copy(
        launchToken = incoming.launchToken.ifBlank { existing.launchToken },
        lastProtocol = incoming.lastProtocol?.takeUnless { it == HarnessProtocol.UNDETERMINED }
            ?: existing.lastProtocol?.takeIf { sameHarnessEndpoint(existing, incoming) },
    )
}
