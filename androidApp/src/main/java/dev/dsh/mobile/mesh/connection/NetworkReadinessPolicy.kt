package dev.dsh.mobile.mesh.connection

/** Recovery may start only after Android has selected an active Internet-capable network. */
internal fun shouldStartNetworkRecovery(
    isActiveNetwork: Boolean,
    hasInternetCapability: Boolean,
): Boolean = isActiveNetwork && hasInternetCapability
