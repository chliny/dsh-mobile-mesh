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

    suspend fun stop() {
        val connector = active ?: return
        active = null
        connector.stop()
    }
}
