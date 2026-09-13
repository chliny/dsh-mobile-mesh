package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import kotlinx.serialization.Serializable

/**
 * One remembered harness endpoint, reached directly through the device's current network routes.
 *
 * The `last*` fields cache the newest `host.describe` so a Recent card can say what the harness is
 * before its liveness probe lands — and can still say it about a harness that is now switched off.
 * They all default, because the whole list is persisted as one JSON blob whose decode failure is
 * swallowed: a field without a default would silently wipe every remembered host on upgrade. That
 */
@Serializable
data class HostConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val isLoopback: Boolean = false,
    /**
     * Speak TLS to this endpoint. The harness itself never serves HTTPS — this is for a reverse
     * proxy someone put in front of it (Caddy at `https://agent.home`, say), which is the only
     * way it is ever reached over TLS.
     */
    val useTls: Boolean = false,
    val lastConnectedAt: Long = 0L,
    /**
     * The host account's home directory, as of the last successful connection.
     *
     * This is the only host fact 0.1.2 still publishes. Through 0.1.1 the record also remembered
     * the harness version, its working directory and its attached-session count, all from
     * `host.describe`; that call is gone and nothing replaces those three, so they are no longer
     * remembered or shown.
     */
    val lastHome: String? = null,
    /** Optional app-private network stack used to reach this harness. */
    val meshTransport: MeshTransport? = null,
    /** ZeroTier's 16-hex-character network ID when [meshTransport] is ZeroTier. */
    val zeroTierNetworkId: String? = null,
    /** SHA-256 identity of an imported app-private ZeroTier planet. */
    val zeroTierPlanetId: String? = null,
    /** Normalized custom ZeroTier planet payload, retained for editing the connection. */
    val zeroTierPlanetBase64: String? = null,
    /** Optional stable name shown for this phone in the Tailscale admin console. */
    val tailscaleHostname: String? = null,
    /** SSH is the default transport fence; disable it only for an explicitly exposed DSH endpoint. */
    val sshEnabled: Boolean = false,
    val sshPort: Int = 22,
    val sshUsername: String? = null,
    val sshAuthentication: SshAuthentication = SshAuthentication.PASSWORD,
    /** DSH endpoint as reached from the SSH server. */
    val sshDshHost: String = "127.0.0.1",
    /** Process-startup token for this specific Harness connection, used to mint its browser session. */
    val launchToken: String = "",
) {
    /** Bare `host:port` — the identity key and display form, deliberately scheme-free. */
    val authority: String get() = urlAuthority(host, port)

    /** Authority the Harness receives after an SSH local forward has reached its target. */
    val harnessAuthority: String get() = if (sshEnabled) urlAuthority(sshDshHost, port) else authority
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** What a card prints: the authority, scheme-qualified only when it is not the plain default. */
    val displayAddress: String get() = if (useTls) "https://$authority" else authority

}

@Serializable
enum class SshAuthentication { PASSWORD, PRIVATE_KEY }

/**
 * The unfinished connection form, including SSH credentials, so every connection setting restores
 * after the app is reopened.
 */
@Serializable
data class ConnectionDraft(
    /** Optional user-facing name; blank falls back to the host address. */
    val name: String = "",
    val host: String = "",
    val port: String = "3080",
    val meshTransport: String = "direct",
    val zeroTierNetworkId: String = "",
    val zeroTierPlanetId: String? = null,
    /** Custom planet Base64 draft. It is not a credential and is capped by [ZeroTierPlanetStore]. */
    val zeroTierPlanetBase64: String = "",
    val tailscaleHostname: String = "",
    val sshEnabled: Boolean = true,
    val sshPort: String = "22",
    val sshUsername: String = "",
    val sshAuthentication: SshAuthentication = SshAuthentication.PASSWORD,
    val sshPassword: String = "",
    val sshPrivateKey: String = "",
    val sshPrivateKeyPassphrase: String = "",
    /** Harness launch token supplied for the next authenticated connection attempt. */
    val launchToken: String = "",
    val sshDshHost: String = "127.0.0.1",
)

/**
 * A harness found by the active LAN scan.
 *
 * Carries the whole probe answer rather than two fields of it: the sweep already paid for the round
 * trip, and the card wants the session count and the default model too.
 *
 * [description] is null when the harness was identified by its static manifest but its trust fence
 * refused `host.describe` from this address. That is a real find, not a miss — it is a harness with
 * a `--trusted-host` still to add — so it is listed and explained rather than dropped.
 */
data class DiscoveredHost(
    val host: String,
    val port: Int,
    val description: HostDescription?,
    /** True when the advertisement said the listener terminates TLS. */
    val useTls: Boolean = false,
) {
    val authority: String get() = "$host:$port"

    /** Origin to address this endpoint by. */
    val baseUrl: String get() = harnessBaseUrl(host, port, useTls)

    /** Whether the harness accepted an `/api` call from this device. */
    val trusted: Boolean get() = description != null
}

/** App-level persisted settings (DataStore). */
data class AppSettings(
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val keepConnectedInBackground: Boolean = false,
    val notifyTurnComplete: Boolean = true,
    val notifyGoal: Boolean = true,
    val notifyNeedsAction: Boolean = true,
    val themePreference: String = "system", // light | dark | system
    val localeOverride: String? = null, // null = system
    val knownPorts: List<Int> = listOf(3080),
    /**
     * Whether to ask GitHub for the latest release on start.
     *
     * The only request this app makes to anything other than the harness the user pointed it at,
     * so it is worth being able to switch off — on a restricted network, or by anyone who would
     * rather it stayed local-only.
     */
    val updateCheckEnabled: Boolean = true,
    /** A release the user has already declined, so it is offered once rather than every launch. */
    val dismissedUpdate: String? = null,
)
