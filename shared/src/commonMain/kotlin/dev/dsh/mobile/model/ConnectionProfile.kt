package dev.dsh.mobile.model

import kotlinx.serialization.Serializable

@Serializable
data class Endpoint(val host: String, val port: Int, val tls: Boolean = false) {
    init {
        require(host.isNotBlank()) { "Host is required" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
    }

    val baseUrl: String get() = "${if (tls) "https" else "http"}://${if (':' in host) "[$host]" else host}:$port"
}

@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    /** Always names the real DSH listener, even when it is reached through SSH. */
    val dshEndpoint: Endpoint,
    val mesh: MeshConfig = MeshConfig.None,
    /** Defaults on for newly created profiles. */
    val ssh: SshTunnelConfig = SshTunnelConfig.disabled(),
)

@Serializable
sealed class MeshConfig {
    @Serializable data object None : MeshConfig()
    @Serializable data class Tailscale(val deviceName: String? = null) : MeshConfig()
    @Serializable data class ZeroTier(val networkId: String, val planetId: String? = null) : MeshConfig()
}

@Serializable
data class SshTunnelConfig(
    val enabled: Boolean,
    val endpoint: Endpoint? = null,
    val username: String? = null,
    val authentication: SshAuthentication? = null,
    val hostKeyFingerprint: String? = null,
    val remoteDshEndpoint: Endpoint = Endpoint("127.0.0.1", 3080),
) {
    fun requireEnabled(): CompleteSshTunnelConfig {
        require(enabled) { "SSH forwarding is disabled" }
        return CompleteSshTunnelConfig(
            endpoint = requireNotNull(endpoint),
            username = requireNotNull(username).also { require(it.isNotBlank()) },
            authentication = requireNotNull(authentication),
            hostKeyFingerprint = requireNotNull(hostKeyFingerprint).also { require(it.startsWith("SHA256:")) },
            remoteDshEndpoint = remoteDshEndpoint,
        )
    }

    companion object {
        fun disabled() = SshTunnelConfig(enabled = false)
    }
}

data class CompleteSshTunnelConfig(
    val endpoint: Endpoint,
    val username: String,
    val authentication: SshAuthentication,
    val hostKeyFingerprint: String,
    val remoteDshEndpoint: Endpoint,
)

@Serializable
sealed class SshAuthentication {
    @Serializable data class Password(val credentialId: String) : SshAuthentication()
    @Serializable data class PrivateKey(val privateKeyCredentialId: String, val passphraseCredentialId: String? = null) : SshAuthentication()
}
