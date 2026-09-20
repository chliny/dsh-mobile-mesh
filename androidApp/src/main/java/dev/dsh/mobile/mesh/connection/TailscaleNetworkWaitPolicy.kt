package dev.dsh.mobile.mesh.connection

/** Replacement Wi-Fi/cellular networks can take several seconds to become the default route. */
internal const val TAILSCALE_NETWORK_WAIT_ATTEMPTS = 40
internal const val TAILSCALE_NETWORK_WAIT_DELAY_MS = 250L
