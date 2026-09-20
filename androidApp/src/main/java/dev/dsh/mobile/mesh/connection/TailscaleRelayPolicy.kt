package dev.dsh.mobile.mesh.connection

/** Same-family Tailscale recovery keeps tsnet identity but replaces the possibly stale relay. */
internal fun shouldRenewTailscaleRelay(
    activeTransport: MeshTransport?,
    nextTransport: MeshTransport?,
): Boolean = activeTransport == MeshTransport.TAILSCALE && nextTransport == MeshTransport.TAILSCALE
