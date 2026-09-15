package dev.dsh.mobile.mesh.connection

/**
 * libzt is process-global. Once a node object has initialized this network, an offline transition
 * means "wait for that node", not "initialize another service" — the latter returns -2 while the
 * original NodeService is still starting or temporarily offline.
 */
internal fun shouldReuseZeroTierNode(hasNode: Boolean, sameNetwork: Boolean): Boolean =
    hasNode && sameNetwork
