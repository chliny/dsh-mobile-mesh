package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.core.wire.SessionExchange
import dev.dsh.mobile.mesh.connection.HarnessSessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.mesh.connection.ConnectStage
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionDraft
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.DiscoveredHost
import dev.dsh.mobile.mesh.connection.DiscoveryEngine
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.connection.HostsStore
import dev.dsh.mobile.mesh.connection.MeshTransport
import dev.dsh.mobile.mesh.connection.SshAuthentication
import dev.dsh.mobile.mesh.connection.SshCredentials
import dev.dsh.mobile.mesh.connection.SshSecretStore
import dev.dsh.mobile.mesh.connection.ZeroTierPlanetStore
import android.net.Uri
import dev.dsh.mobile.mesh.connection.ProbeOutcome
import dev.dsh.mobile.mesh.connection.ProbeTimeouts
import dev.dsh.mobile.mesh.connection.parseHostInput
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject

/** Whether a remembered harness is answering right now. */
sealed interface HostProbe {
    /** The probe is in flight. */
    data object Probing : HostProbe

    /** It answered `host.describe`; [description] is what it said. */
    data class Reachable(val description: HostDescription) : HostProbe

    /** No answer — switched off, asleep, or on another network. */
    data object Unreachable : HostProbe
}

/** How far the subnet sweep has got, so the UI can show more than a spinner. */
data class ScanProgress(val probed: Int, val total: Int)

/** Why a launch-token exchange did not produce a browser session. */
enum class SignInError {
    /** The screen has no remembered host to sign in to — nothing was attempted yet. */
    NoHost,

    /** The harness answered and issued no session; the token is not the current process's. */
    Refused,

    /** The exchange never reached a harness. */
    Unreachable,
}

data class ConnectUiState(
    val remembered: List<HostConfig> = emptyList(),
    /** Liveness per remembered host, keyed by `host:port`. Absent = not probed yet. */
    val recentStatus: Map<String, HostProbe> = emptyMap(),
    val discovered: List<DiscoveredHost> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    /** What the connect attempt is doing right now. */
    val stage: ConnectStage = ConnectStage.Idle,
    /** Why the last attempt failed, or null. */
    val failure: ConnectFailure? = null,
    /** An embedded private-network node needs approval before it can receive an address. */
    val authorizationPending: String? = null,
    /** Tailscale's in-app authorization page, or null for other pending mesh approvals. */
    val tailscaleLoginUrl: String? = null,
    /** The authority actually attempted, e.g. `192.168.1.20:3080` — never the live field text. */
    val attempted: String? = null,
    /** The loop is still retrying in the background, so a cancel is worth offering. */
    val retrying: Boolean = false,
    /** The launch-token prompt is showing. */
    val signInOpen: Boolean = false,
    /** An exchange is in flight. */
    val signingIn: Boolean = false,
    /** Why the last exchange did not produce a session, or null. */
    val signInError: SignInError? = null,
    val autoConnectLast: Boolean = true,
    val autoConnectLan: Boolean = false,
    val autoConnectLoopback: Boolean = true,
    val showAdvanced: Boolean = false,
) {
    /**
     * Derived, never stored.
     *
     * The old stored boolean was only ever cleared by a callback that could not fire, so a failed
     * connect left the button disabled for the rest of the session. Reading it off the stage means
     * there is no latch to get stuck: `disconnect()` resets the whole state object, and every other
     * path ends in either [ConnectStage.Connected] or a failure the screen displays.
     */
    val connecting: Boolean
        get() = stage != ConnectStage.Idle && stage != ConnectStage.Connected

    /**
     * Discovered harnesses that are not already remembered.
     *
     * A harness in both lists used to render twice, with two different Connect buttons doing the
     * same thing; the Recent card is the one with the history on it, so the sweep yields.
     */
    val unknownDiscovered: List<DiscoveredHost>
        get() {
            val known = remembered.map { it.authority }.toSet()
            return discovered.filterNot { it.authority in known }
        }

}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val discoveryEngine: DiscoveryEngine,
    private val hostsStore: HostsStore,
    private val harnessSessions: HarnessSessionStore,
    private val sshSecrets: SshSecretStore,
    private val zeroTierPlanets: ZeroTierPlanetStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()
    val draft = hostsStore.connectionDraft

    fun saveDraft(draft: ConnectionDraft) {
        viewModelScope.launch { hostsStore.saveConnectionDraft(draft) }
    }

    fun clearDraft() {
        viewModelScope.launch { hostsStore.clearConnectionDraft() }
    }

    /**
     * Non-null while this ViewModel owns the outcome rather than the manager.
     *
     * Validation and the pre-flight probe happen here and can end the attempt before the manager is
     * ever engaged; everything from the handshake on belongs to the manager. Set to null only when
     * handing over, so a locally-decided result is not overwritten by a manager still reporting the
     * previous attempt — and so a stale manager stage cannot pin the screen on "Reaching…".
     */
    private var localStage: ConnectStage? = null

    /** The sweep in flight, so a second tap cannot start a rival one and Cancel has something to stop. */
    private var scanJob: Job? = null

    /** One status poll at a time while tsnet waits for the embedded browser authorization. */
    private var tailscaleLoginJob: Job? = null

    /** One-time token supplied with a manual connection; it is never persisted. */
    private var pendingLaunchToken: String? = null
    private var tokenPairingJob: Job? = null

    /**
     * Exchange a harness launch token for a browser session, then retry the connection.
     *
     * Harness 0.1.2 authenticates its whole `/api` surface, so a direct connection to a harness
     * this device has never signed in to is refused before any method runs. The token is printed
     * once per harness process and is only accepted on the index route, which is why this is a
     * separate step rather than something a connection attempt could do on its own.
     *
     * [input] may be the bare token, the `?token=…` URL, or the whole startup line.
     */
    fun signIn(input: String) {
        val host = _state.value.let { current ->
            current.remembered.firstOrNull { it.authority == current.attempted }
        }
        if (host == null) {
            _state.update { it.copy(signInError = SignInError.NoHost) }
            return
        }
        // A failed unauthenticated loop may already have lost its private relay. Recreate the
        // transport and exchange the token from `connectTo`'s transport-ready callback instead
        // of trying to reuse a stale local endpoint.
        _state.update { it.copy(signInOpen = false, signInError = null) }
        viewModelScope.launch {
            hostsStore.saveLaunchToken(input.trim())
            connectTo(host, input)
        }
    }

    /** Open or close the launch-token prompt. */
    fun setSignInOpen(open: Boolean) {
        _state.update { it.copy(signInOpen = open, signInError = null) }
    }

    init {
        viewModelScope.launch {
            connectionManager.state.collect { conn ->
                _state.update { current ->
                    val connected = conn.phase == ConnectionPhase.CONNECTED
                    val owned = localStage != null
                    current.copy(
                        stage = localStage ?: conn.stage,
                        failure = when {
                            connected -> null
                            owned -> current.failure
                            else -> conn.failure
                        },
                        authorizationPending = conn.authorizationPending,
                        tailscaleLoginUrl = conn.tailscaleLoginUrl,
                        // Whoever started the attempt owns this normally, but pairing connects
                        // through the manager directly — so a failure after pairing arrived with
                        // no address at all, and the message read "Something answered at , but…".
                        attempted = current.attempted ?: conn.host?.authority,
                        retrying = !owned && !connected && conn.attempts > 0 && conn.phase != ConnectionPhase.DISCONNECTED,
                        // The Recent card's liveness dot used to be greyed by the failure callback
                        // that no longer exists; without this a dead entry keeps looking healthy.
                        recentStatus = current.attempted
                            ?.takeIf { !owned && conn.failure != null }
                            ?.let { current.recentStatus + (it to HostProbe.Unreachable) }
                            ?: current.recentStatus,
                    )
                }
                if (conn.tailscaleLoginUrl != null) startTailscaleLoginPolling()
                else if (
                    conn.phase == ConnectionPhase.CONNECTED || conn.failure != null ||
                    (conn.phase == ConnectionPhase.DISCONNECTED && conn.authorizationPending == null)
                ) {
                    tailscaleLoginJob?.cancel()
                    tailscaleLoginJob = null
                }
                val token = pendingLaunchToken
                val host = conn.host
                if (conn.failure is ConnectFailure.Unauthenticated && host != null && token != null &&
                    tokenPairingJob?.isActive != true) {
                    pairLaunchToken(host, token)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val settings = hostsStore.settingsOnce()
            _state.update {
                it.copy(
                    autoConnectLast = settings.autoConnectLast,
                    autoConnectLan = settings.autoConnectLan,
                    autoConnectLoopback = settings.autoConnectLoopback,
                )
            }
            hostsStore.hosts.collect { hosts ->
                _state.update { it.copy(remembered = hosts) }
            }
        }
        // Restore only the single most recently active host. Other remembered hosts stay dormant
        // until the user explicitly selects them from the connection list.
        viewModelScope.launch { autoConnect() }
    }

    /**
     * Probe every remembered host once, concurrently.
     *
     * Without this a Recent row can only offer a Connect button that may or may not do anything;
     * one `host.describe` per entry is what turns the list into something you can read before
     * tapping. Results are folded back into storage so the metadata survives the harness going away.
     */
    private suspend fun probeRemembered() {
        val hosts = hostsStore.hosts.first()
        if (hosts.isEmpty()) return
        // Do not probe every saved endpoint on launch. The list is a selector, not a dashboard of
        // liveness; inactive rows must remain idle until selected.
        val activeId = connectionManager.state.value.host?.id
        val targets = hosts.filter { it.id == activeId }
        if (targets.isEmpty()) return
        _state.update { current ->
            current.copy(recentStatus = targets.associate { it.authority to HostProbe.Probing })
        }
        supervisorScope {
            targets.map { host ->
                async {
                    // A private transport has to be started as a whole; probing its DSH port on the
                    // device's ordinary routes would only manufacture an "unreachable" result.
                    if (host.sshEnabled || host.meshTransport != null) {
                        _state.update { current ->
                            current.copy(recentStatus = current.recentStatus - host.authority)
                        }
                        return@async
                    }
                    val description = runCatching {
                        // A remembered host is named, not swept — worth waiting for.
                        discoveryEngine.probe(host.host, host.port, ProbeTimeouts.Manual, config = host)
                    }.getOrNull()
                    _state.update { current ->
                        current.copy(
                            recentStatus = current.recentStatus + (
                                host.authority to (
                                    description?.let { HostProbe.Reachable(it) } ?: HostProbe.Unreachable
                                    )
                                ),
                        )
                    }
                    if (description != null) {
                        hostsStore.cacheDescription(host.host, host.port, description)
                    }
                }
            }.awaitAll()
        }
    }

    /** Re-run the liveness pass, e.g. after the user comes back to the screen. */
    fun refreshRecent() {
        viewModelScope.launch { probeRemembered() }
    }

    /** Connect without being asked to the most recently used reachable harness. */
    private suspend fun autoConnect() {
        val settings = hostsStore.settingsOnce()
        if (settings.autoConnectLast) {
            // Hosts are ordered by the timestamp of their last *successful* connection. The
            // editable draft may describe a connection the user is preparing, but must not change
            // the automatic default: restarting preserves the actual last ZeroTier/Tailscale path.
            val last = hostsStore.hosts.first().firstOrNull()
            if (last != null) {
                // Startup restoration has exactly one candidate: the active/most recently used host.
                // Do not fall through to another saved host when this one is offline.
                if (last.sshEnabled) {
                    // A mesh/SSH host must be paired with this Harness process before opening its
                    // mux. Form-entered tokens live in the draft, so include that persisted token
                    // in automatic startup rather than silently retrying an inevitable 401.
                    val launchToken = hostsStore.connectionDraft.first().launchToken
                        .trim()
                        .takeIf { it.isNotEmpty() }
                    connectTo(last, launchToken)
                    return
                }
                val desc = discoveryEngine.probe(last.host, last.port, ProbeTimeouts.Manual, config = last)
                if (desc != null) connectTo(last)
                return
            }
        }
        // 2. LAN discovery.
        if (settings.autoConnectLan) {
            val first = firstReachableOnLan(settings.knownPorts)
            if (first != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(first.host),
                    host = first.host,
                    port = first.port,
                    isLoopback = false,
                    description = first.description,
                    sshEnabled = false,
                )
                connectTo(config)
                return
            }
        }
        // 3. Same-device loopback.
        if (settings.autoConnectLoopback) {
            val desc = discoveryEngine.probe(LOOPBACK, DEFAULT_PORT)
            if (desc != null) {
                val config = hostsStore.rememberHost(
                    name = hostLabel(LOOPBACK),
                    host = LOOPBACK,
                    port = DEFAULT_PORT,
                    isLoopback = true,
                    description = desc,
                    sshEnabled = false,
                )
                connectTo(config)
            }
        }
    }

    /**
     * Sweep only until something answers, then stop.
     *
     * Auto-connect has no use for the rest of the subnet, so finishing the sweep before making the
     * first attempt is latency nobody asked for. A trust-fenced host does not count — auto-connect
     * cannot do anything with one, and stopping on it would hide a usable harness further along.
     */
    private suspend fun firstReachableOnLan(ports: List<Int>): DiscoveredHost? = coroutineScope {
        val hit = CompletableDeferred<DiscoveredHost?>()
        val sweep = launch {
            val all = discoveryEngine.scan(ports, onFound = { found ->
                if (found.trusted) hit.complete(found)
            })
            hit.complete(all.firstOrNull { it.trusted })
        }
        val result = hit.await()
        sweep.cancel()
        result
    }

    /**
     * Sweep the subnet, showing hosts as they are confirmed.
     *
     * Results stream rather than landing in one batch at the end: the harness someone is looking
     * for is usually found in the first fraction of the sweep, and making them watch the remaining
     * two hundred addresses finish before it appears is the difference between "fast" and "fast on
     * paper". [discovered] is therefore cleared at the start and appended to, not replaced.
     */
    fun scan() {
        if (scanJob?.isActive == true) return
        _state.update {
            it.copy(scanning = true, scanProgress = null, failure = null, discovered = emptyList())
        }
        scanJob = viewModelScope.launch {
            try {
                val settings = hostsStore.settingsOnce()
                discoveryEngine.scan(
                    ports = settings.knownPorts,
                    onProgress = { probed, total ->
                        _state.update { it.copy(scanProgress = ScanProgress(probed, total)) }
                    },
                    onFound = { found ->
                        _state.update { state ->
                            if (state.discovered.any { it.authority == found.authority }) state
                            else state.copy(discovered = state.discovered + found)
                        }
                    },
                )
            } finally {
                // Also the cancel path: a sweep the user stopped keeps whatever it already found.
                _state.update { it.copy(scanning = false, scanProgress = null) }
            }
        }
    }

    /** Stop a sweep in flight, keeping anything it has already turned up. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun connectManual(
        name: String,
        host: String,
        port: String,
        sshEnabled: Boolean,
        sshPort: String,
        sshUsername: String,
        sshAuthentication: SshAuthentication,
        sshPassword: String,
        sshPrivateKey: String,
        sshPrivateKeyPassphrase: String,
        sshDshHost: String,
        launchToken: String,
    ) {
        // The field takes what people actually have — a pasted URL as readily as a bare address.
        // A port named inside it was typed as part of this address, so it outranks the port field,
        // which may still hold the default from a different harness.
        val input = parseHostInput(host)
        val portInt = input?.port ?: port.trim().toIntOrNull()
        if (input == null || portInt == null || portInt !in 1..65535) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        // An explicit scheme decides; otherwise port 443 means a TLS reverse proxy — the harness
        // itself never serves there, and plaintext to a TLS port yields an answer no one can read.
        val useTls = input.useTls ?: (portInt == 443)
        val sshPortInt = sshPort.trim().toIntOrNull()
        if (sshEnabled && (
                useTls || sshPortInt !in 1..65535 || sshUsername.isBlank() ||
                    sshDshHost.isBlank() ||
                    (sshAuthentication == SshAuthentication.PASSWORD && sshPassword.isEmpty()) ||
                    (sshAuthentication == SshAuthentication.PRIVATE_KEY && sshPrivateKey.isBlank())
                )) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        val authority = "${input.host}:$portInt"
        val isLoopback = input.host == LOOPBACK || input.host == "localhost"

        localStage = ConnectStage.Validating
        _state.update { it.copy(stage = ConnectStage.Validating, failure = null, attempted = authority) }

        viewModelScope.launch {
            if (sshEnabled) {
                val config = hostsStore.rememberHost(
                    name = name,
                    host = input.host,
                    port = portInt,
                    isLoopback = isLoopback,
                    sshEnabled = true,
                    sshPort = sshPortInt!!,
                    sshUsername = sshUsername.trim(),
                    sshAuthentication = sshAuthentication,
                    sshDshHost = sshDshHost.trim(),
                )
                sshSecrets.put(
                    config.id,
                    SshCredentials(
                        password = sshPassword.takeIf { sshAuthentication == SshAuthentication.PASSWORD },
                        privateKey = sshPrivateKey.takeIf { sshAuthentication == SshAuthentication.PRIVATE_KEY },
                        privateKeyPassphrase = sshPrivateKeyPassphrase.takeIf { it.isNotEmpty() },
                    ),
                )
                connectTo(config, launchToken)
                return@launch
            }
            localStage = ConnectStage.Reaching
            _state.update { it.copy(stage = ConnectStage.Reaching) }
            val outcome = discoveryEngine.probeOutcome(
                host = input.host,
                port = portInt,
                timeouts = ProbeTimeouts.Manual,
                preflight = true,
                useTls = useTls,
            )
            if (outcome !is ProbeOutcome.Reachable &&
                !(outcome is ProbeOutcome.Unauthenticated && launchToken.isNotBlank())) {
                fail(ConnectFailure.from(outcome), authority)
                return@launch
            }
            hostsStore.addKnownPort(portInt)
            val config = hostsStore.rememberHost(
                name = name,
                host = input.host,
                port = portInt,
                isLoopback = isLoopback,
                useTls = useTls,
                description = (outcome as? ProbeOutcome.Reachable)?.description,
                sshEnabled = false,
            )
            if (outcome is ProbeOutcome.Unauthenticated) pairLaunchToken(config, launchToken)
            else connectTo(config, launchToken)
        }
    }

    /** Save a private-network target and start its embedded userspace relay. */
    fun connectMesh(
        name: String,
        host: String,
        port: String,
        transport: MeshTransport,
        networkId: String = "",
        hostname: String = "",
        sshEnabled: Boolean,
        sshPort: String,
        sshUsername: String,
        sshAuthentication: SshAuthentication,
        sshPassword: String,
        sshPrivateKey: String,
        sshPrivateKeyPassphrase: String,
        sshDshHost: String,
        zeroTierPlanetId: String?,
        launchToken: String,
    ) {
        val input = parseHostInput(host)
        val portInt = input?.port ?: port.trim().toIntOrNull()
        val sshPortInt = sshPort.trim().toIntOrNull()
        if (input == null || portInt == null || portInt !in 1..65535 || input.useTls == true ||
            (transport == MeshTransport.ZERO_TIER && !networkId.trim().matches(Regex("[0-9a-fA-F]{16}"))) ||
            (sshEnabled && (sshPortInt !in 1..65535 || sshUsername.isBlank() || sshDshHost.isBlank() ||
                (sshAuthentication == SshAuthentication.PASSWORD && sshPassword.isEmpty()) ||
                (sshAuthentication == SshAuthentication.PRIVATE_KEY && sshPrivateKey.isBlank())))) {
            fail(ConnectFailure.InvalidInput, attempted = null)
            return
        }
        val authority = "${input.host}:$portInt"
        localStage = ConnectStage.Reaching
        _state.update { it.copy(stage = ConnectStage.Reaching, failure = null, attempted = authority) }
        viewModelScope.launch {
            val config = hostsStore.rememberHost(
                name = name,
                host = input.host,
                port = portInt,
                isLoopback = false,
                useTls = false,
                meshTransport = transport,
                zeroTierNetworkId = networkId.trim().lowercase().takeIf { transport == MeshTransport.ZERO_TIER },
                zeroTierPlanetId = zeroTierPlanetId?.takeIf { transport == MeshTransport.ZERO_TIER },
                tailscaleHostname = hostname.trim().takeIf { transport == MeshTransport.TAILSCALE && it.isNotEmpty() },
                sshEnabled = sshEnabled,
                sshPort = sshPortInt ?: 22,
                sshUsername = sshUsername.trim().takeIf { sshEnabled },
                sshAuthentication = sshAuthentication,
                sshDshHost = sshDshHost.trim().ifEmpty { "127.0.0.1" },
            )
            if (sshEnabled) sshSecrets.put(
                config.id,
                SshCredentials(
                    password = sshPassword.takeIf { sshAuthentication == SshAuthentication.PASSWORD },
                    privateKey = sshPrivateKey.takeIf { sshAuthentication == SshAuthentication.PRIVATE_KEY },
                    privateKeyPassphrase = sshPrivateKeyPassphrase.takeIf { it.isNotEmpty() },
                ),
            )
            connectTo(config, launchToken)
        }
    }

    suspend fun importZeroTierPlanet(uri: Uri): Result<String> = runCatching {
        zeroTierPlanets.import(uri)
    }

    suspend fun importZeroTierPlanetBase64(encoded: String): Result<String> = runCatching {
        zeroTierPlanets.importBase64(encoded)
    }

    fun savedSshCredentials(hostId: String): SshCredentials? = sshSecrets.get(hostId)

    /** Persist the form without starting or replacing the live connection. */
    fun saveConnection(
        existing: HostConfig?,
        name: String,
        host: String,
        port: String,
        transport: MeshTransport?,
        networkId: String,
        planetId: String?,
        planetBase64: String,
        tailscaleHostname: String,
        sshEnabled: Boolean,
        sshPort: String,
        sshUsername: String,
        sshAuthentication: SshAuthentication,
        sshPassword: String,
        sshPrivateKey: String,
        sshPrivateKeyPassphrase: String,
        sshDshHost: String,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val input = parseHostInput(host) ?: return@launch
            val portInt = input.port ?: port.trim().toIntOrNull() ?: return@launch
            if (portInt !in 1..65535) return@launch
            val importedPlanetId = if (transport == MeshTransport.ZERO_TIER && planetBase64.isNotBlank()) {
                zeroTierPlanets.importBase64(planetBase64).also { }
            } else planetId
            val config = HostConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim().ifEmpty { input.host },
                host = input.host,
                port = portInt,
                isLoopback = input.host == LOOPBACK || input.host == "localhost",
                useTls = input.useTls ?: (portInt == 443),
                lastConnectedAt = existing?.lastConnectedAt ?: 0L,
                lastHome = existing?.lastHome,
                meshTransport = transport,
                zeroTierNetworkId = networkId.trim().lowercase().takeIf { transport == MeshTransport.ZERO_TIER },
                zeroTierPlanetId = importedPlanetId?.takeIf { transport == MeshTransport.ZERO_TIER },
                zeroTierPlanetBase64 = planetBase64.filterNot(Char::isWhitespace).takeIf { transport == MeshTransport.ZERO_TIER && it.isNotBlank() },
                tailscaleHostname = tailscaleHostname.trim().takeIf { transport == MeshTransport.TAILSCALE && it.isNotBlank() },
                sshEnabled = sshEnabled,
                sshPort = sshPort.trim().toIntOrNull() ?: 22,
                sshUsername = sshUsername.trim().takeIf { sshEnabled && it.isNotBlank() },
                sshAuthentication = sshAuthentication,
                sshDshHost = sshDshHost.trim().ifEmpty { "127.0.0.1" },
            )
            hostsStore.upsertHost(config)
            if (sshEnabled) {
                sshSecrets.put(config.id, SshCredentials(
                    password = sshPassword.takeIf { sshAuthentication == SshAuthentication.PASSWORD && it.isNotEmpty() },
                    privateKey = sshPrivateKey.takeIf { sshAuthentication == SshAuthentication.PRIVATE_KEY && it.isNotEmpty() },
                    privateKeyPassphrase = sshPrivateKeyPassphrase.takeIf { it.isNotEmpty() },
                ))
            } else {
                sshSecrets.remove(config.id)
            }
            onSaved()
        }
    }

    /** Stop a connect attempt that the loop would otherwise keep retrying every few seconds. */
    fun cancelConnect() {
        // Keep ownership: the manager resets its own state on disconnect, but claiming Idle here
        // means the screen is never briefly re-driven by a trailing emission.
        localStage = ConnectStage.Idle
        connectionManager.disconnect()
        _state.update { it.copy(stage = ConnectStage.Idle, failure = null, retrying = false) }
    }

    fun resumeTailscaleLogin() = startTailscaleLoginPolling()

    fun cancelTailscaleLogin() {
        tailscaleLoginJob?.cancel()
        tailscaleLoginJob = null
        cancelConnect()
    }

    private fun startTailscaleLoginPolling() {
        if (tailscaleLoginJob?.isActive == true) return
        tailscaleLoginJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                connectionManager.resumeAuthorization()
                val state = _state.value
                if (state.stage == ConnectStage.Connected || state.failure != null ||
                    (state.stage == ConnectStage.Idle && state.authorizationPending == null)) break
            }
        }
    }

    /**
     * End the attempt with a reason, and mark the attempted host unreachable if there was one.
     *
     * Holds [localStage] at Idle rather than releasing it: this outcome was decided here, and a
     * manager emission from an earlier attempt must not replace it or revive `connecting`.
     */
    private fun fail(failure: ConnectFailure, attempted: String?) {
        localStage = ConnectStage.Idle
        _state.update {
            it.copy(
                stage = ConnectStage.Idle,
                failure = failure,
                attempted = attempted ?: it.attempted,
                retrying = false,
                recentStatus = attempted?.let { key -> it.recentStatus + (key to HostProbe.Unreachable) }
                    ?: it.recentStatus,
            )
        }
    }

    /**
     * Connect to a remembered host, and say so when it does not work.
     *
     * Progress and failure now arrive on the manager's own state flow, which the collector in
     * `init` folds in — so a tap on a dead Recent entry reports the same diagnosis as a manual
     * attempt instead of looking like an inert button.
     */
    fun connectTo(host: HostConfig, launchToken: String? = null) {
        val token = launchToken?.trim()?.takeIf { it.isNotEmpty() }
        token?.let {
            pendingLaunchToken = it
            // The saved-form Connect path reaches this method directly. Persist its launch token
            // before transport startup so an Activity/process interruption cannot erase pairing.
            viewModelScope.launch { hostsStore.saveLaunchToken(it) }
        }
        localStage = null
        _state.update {
            it.copy(
                stage = ConnectStage.OpeningStreams,
                failure = null,
                attempted = host.authority,
                retrying = false,
            )
        }
        viewModelScope.launch {
            connectionManager.connect(host) { baseUrl ->
                // Keep the token pending until the exchange is granted. The transport startup can
                // fail before this callback runs (or be cancelled by a lifecycle recovery), and
                // clearing it early otherwise turns the next 401 into an unrecoverable retry loop.
                val tokenToPair = token ?: pendingLaunchToken ?: return@connect
                _state.update { it.copy(signingIn = true, signInError = null) }
                when (harnessSessions.pair(host.id, baseUrl, tokenToPair, host.harnessAuthority)) {
                    is SessionExchange.Granted -> {
                        pendingLaunchToken = null
                        _state.update { it.copy(signingIn = false) }
                    }
                    is SessionExchange.Refused -> {
                        pendingLaunchToken = null
                        _state.update {
                            it.copy(signingIn = false, signInOpen = true, signInError = SignInError.Refused)
                        }
                        throw IllegalArgumentException("Harness launch token was refused")
                    }
                    is SessionExchange.Unreachable -> {
                        _state.update {
                            it.copy(signingIn = false, signInOpen = true, signInError = SignInError.Unreachable)
                        }
                        throw IllegalArgumentException("Harness could not be reached for token pairing")
                    }
                }
            }
        }
    }

    private fun pairLaunchToken(host: HostConfig, token: String) {
        tokenPairingJob = viewModelScope.launch {
            _state.update { it.copy(signingIn = true, signInError = null) }
            when (val outcome = harnessSessions.pair(
                host.id, connectionManager.pairingBaseUrl(host), token, host.harnessAuthority,
            )) {
                is SessionExchange.Granted -> {
                    pendingLaunchToken = null
                    _state.update { it.copy(signingIn = false) }
                    connectTo(host)
                }
                is SessionExchange.Refused -> {
                    pendingLaunchToken = null
                    _state.update {
                        it.copy(signingIn = false, signInOpen = true, signInError = SignInError.Refused)
                    }
                }
                is SessionExchange.Unreachable -> _state.update {
                    it.copy(signingIn = false, signInOpen = true, signInError = SignInError.Unreachable)
                }
            }
        }
    }

    fun connectDiscovered(discovered: DiscoveredHost) {
        _state.update { it.copy(failure = null, attempted = discovered.authority) }
        viewModelScope.launch {
            hostsStore.addKnownPort(discovered.port)
            val config = hostsStore.rememberHost(
                name = hostLabel(discovered.host),
                host = discovered.host,
                port = discovered.port,
                isLoopback = false,
                description = discovered.description,
                sshEnabled = false,
            )
            connectTo(config)
        }
    }

    fun forget(host: HostConfig) {
        viewModelScope.launch {
            sshSecrets.remove(host.id)
            hostsStore.removeHost(host.id)
        }
    }

    fun setAuto(key: String, value: Boolean) {
        viewModelScope.launch {
            hostsStore.setSetting { current ->
                when (key) {
                    "last" -> current.copy(autoConnectLast = value)
                    "lan" -> current.copy(autoConnectLan = value)
                    else -> current.copy(autoConnectLoopback = value)
                }
            }
            refreshSettings()
        }
    }

    private suspend fun refreshSettings() {
        val settings = hostsStore.settingsOnce()
        _state.update {
            it.copy(
                autoConnectLast = settings.autoConnectLast,
                autoConnectLan = settings.autoConnectLan,
                autoConnectLoopback = settings.autoConnectLoopback,
            )
        }
    }

    fun clearError() = _state.update { it.copy(failure = null) }

    /**
     * A readable name for an address: reverse DNS when the network offers one, the address itself
     * otherwise. Storing the IP as the name made a card's two lines say the same thing twice.
     */
    private suspend fun hostLabel(address: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val canonical = InetAddress.getByName(address).canonicalHostName
            canonical.takeIf { it.isNotBlank() && it != address }?.substringBefore('.') ?: address
        }.getOrDefault(address)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 3080
    }
}
