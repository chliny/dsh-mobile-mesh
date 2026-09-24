package dev.dsh.mobile.mesh.connection

/** Network readiness is completed by ConnectivityManager callbacks, not polling. */
internal fun shouldSignalTailscaleNetworkWaiter(
    isActiveNetwork: Boolean,
    hasInternetCapability: Boolean,
): Boolean = isActiveNetwork && hasInternetCapability
