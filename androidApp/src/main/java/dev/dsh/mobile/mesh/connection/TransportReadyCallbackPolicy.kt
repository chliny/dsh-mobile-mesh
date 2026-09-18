package dev.dsh.mobile.mesh.connection

/**
 * Token pairing is part of initial transport establishment and Tailscale authorization resume.
 * Ordinary carrier recovery must not repeat it: ZeroTier/SSH reconnects already have a session
 * and repeating the launch-token exchange makes recovery as slow and fragile as first connect.
 */
internal fun shouldRunTransportReadyCallback(
    reconnect: Boolean,
    preservePendingIdentity: Boolean,
): Boolean = !reconnect || preservePendingIdentity
