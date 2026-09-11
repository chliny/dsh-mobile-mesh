package dev.dsh.mobile.mesh.connection

import javax.inject.Inject
import javax.inject.Singleton

/** Selects the one userspace network stack needed by the active connection. */
@Singleton
class MeshTransportManager @Inject constructor(
    private val zeroTier: ZeroTierConnector,
    private val tailscale: TailscaleConnector,
) {
    private var active: MeshConnector? = null

    suspend fun start(config: HostConfig): MeshRelay? {
        val next = when (config.meshTransport) {
            MeshTransport.ZERO_TIER -> zeroTier
            MeshTransport.TAILSCALE -> tailscale
            null -> null
        }
        if (active != null && active !== next) {
            active?.stop()
            active = null
        }
        if (next == null) return null
        return try {
            next.start(config).also {
                active = next
            }
        } catch (error: Throwable) {
            if (error is MeshAuthorizationPending) {
                active = next
                throw error
            }
            runCatching { next.stop() }
            active = null
            throw error
        }
    }

    /**
     * Refresh the active transport without unnecessarily destroying a userspace mesh identity.
     * ZeroTier can immediately reuse its running node after a network handover; stopping libzt first
     * adds up to 10 seconds of native shutdown plus another online/address wait and is unsafe when
     * the old service thread is still unwinding.
     */
    suspend fun reconnect(config: HostConfig): MeshRelay? {
        val next = when (config.meshTransport) {
            MeshTransport.ZERO_TIER -> zeroTier
            MeshTransport.TAILSCALE -> tailscale
            null -> null
        }
        if (next == null) {
            stop()
            return null
        }
        if (next === zeroTier && active === zeroTier) {
            return zeroTier.start(config)
        }
        stop()
        return start(config)
    }

    suspend fun stop() {
        val connector = active ?: return
        active = null
        connector.stop()
    }
}
