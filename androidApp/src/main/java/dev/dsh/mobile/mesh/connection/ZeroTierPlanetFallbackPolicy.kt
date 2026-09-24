package dev.dsh.mobile.mesh.connection

/** Keep a saved ZeroTier planet on same-host reconnects when the caller has no replacement value. */
internal fun retainedZeroTierPlanetId(
    transport: MeshTransport?,
    requestedPlanetId: String?,
    existingPlanetId: String?,
): String? = (requestedPlanetId ?: existingPlanetId).takeIf { transport == MeshTransport.ZERO_TIER }
