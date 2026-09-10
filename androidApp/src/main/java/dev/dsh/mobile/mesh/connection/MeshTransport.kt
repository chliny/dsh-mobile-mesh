package dev.dsh.mobile.mesh.connection

/** Private-network transport selected for a remembered harness. */
enum class MeshTransport(val storedValue: String) {
    ZERO_TIER("zerotier"),
    TAILSCALE("tailscale");

    companion object {
        fun of(value: String?): MeshTransport? = entries.firstOrNull { it.storedValue == value }
    }
}

/** A local relay opened by an embedded mesh stack. */
data class MeshRelay(val host: String, val port: Int) {
    val baseUrl: String get() = "http://$host:$port"
}

/** The connector must remain running while the user completes authorization outside the app. */
open class MeshAuthorizationPending(message: String) : IllegalStateException(message)

class TailscaleLoginRequired(val loginUrl: String) : MeshAuthorizationPending(
    "Finish Tailscale sign-in to continue connecting automatically.",
)

/** Starts only the transport selected for the active harness. */
interface MeshConnector {
    suspend fun start(config: HostConfig): MeshRelay
    suspend fun stop()
}
