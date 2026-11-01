package dev.dsh.mobile.mesh.connection

/** A saved row can be edited to point at a different server; its row ID alone is not a cache key. */
internal fun HostConfig.harnessEndpointKey(): String = kotlinx.serialization.json.buildJsonArray {
    add(kotlinx.serialization.json.JsonPrimitive(host.trim().lowercase()))
    add(kotlinx.serialization.json.JsonPrimitive(port))
    add(kotlinx.serialization.json.JsonPrimitive(useTls))
    add(kotlinx.serialization.json.JsonPrimitive(meshTransport?.storedValue ?: "direct"))
    if (meshTransport == MeshTransport.ZERO_TIER) {
        add(zeroTierNetworkId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        add(zeroTierPlanetId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
    }
    add(kotlinx.serialization.json.JsonPrimitive(sshEnabled))
    if (sshEnabled) {
        add(kotlinx.serialization.json.JsonPrimitive(sshPort))
        add(sshUsername?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        add(kotlinx.serialization.json.JsonPrimitive(sshDshHost))
    }
}.toString()

internal fun sameHarnessEndpoint(first: HostConfig, second: HostConfig): Boolean =
    first.harnessEndpointKey() == second.harnessEndpointKey()

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
