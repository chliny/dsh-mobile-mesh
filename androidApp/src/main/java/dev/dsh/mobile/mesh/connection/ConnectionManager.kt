package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import dev.dsh.mobile.mesh.core.wire.ConnectionLoop
import dev.dsh.mobile.mesh.core.wire.ConnectionState
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.LoopConfig
import dev.dsh.mobile.mesh.core.wire.GenerationFailure
import dev.dsh.mobile.mesh.core.wire.HandshakeStep
import dev.dsh.mobile.mesh.core.wire.LoopSinks
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectFailure
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing connection state. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/**
 * How far the readiness handshake has got, so the connect screen can say what it is doing.
 *
 * `OpeningStreams` is now one socket rather than two, and `Verifying` is waiting for the
 * host's ready frame rather than a `host.describe` answer. The names are kept because what
 * they mean to someone watching the screen has not changed.
 */
enum class ConnectStage { Idle, Validating, Reaching, OpeningStreams, Verifying, Connected }

data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val host: HostConfig? = null,
    val description: HostDescription? = null,
    val stage: ConnectStage = ConnectStage.Idle,
    /**
     * Why the most recent generation failed, or null.
     *
     * Survives the backoff ticks between attempts on purpose: the loop keeps retrying, and wiping
     * this on every state change would blank the only explanation the user gets.
     */
    val failure: ConnectFailure? = null,
    /** The private-network node is waiting for approval outside the app. */
    val authorizationPending: String? = null,
    /** The embedded Tailscale sign-in page, when this pending authorization needs one. */
    val tailscaleLoginUrl: String? = null,
    /** Consecutive failed handshake attempts; 0 while none has failed. */
    val attempts: Int = 0,
    /** True once at least one generation completed the readiness handshake. */
    val hasConnected: Boolean = false,
)

/**
 * Owns the live connection to one harness: the ConnectionLoop (readiness
 * handshake + reconnect/backoff), the foreground service binding for
 * background operation, and the UI state mirror. Single active host at a time.
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: HarnessClientFactory,
    private val hostsStore: HostsStore,
    private val meshTransport: MeshTransportManager,
    private val sshTunnel: SshTunnelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = defaultNetwork
            defaultNetwork = network
            Log.d("ConnectionManager", "Default network available: $network (previous=$previous)")
            if (networkLostWhileConnected && previous != network) {
                networkLostWhileConnected = false
                markCarrierRecoveryNeeded()
                recoverTransportAfterCarrierLoss()
            }
        }

        override fun onLost(network: Network) {
            // During handover Android can report the old network after the replacement is already
            // available. Ignore that stale callback or we rebuild a healthy relay unnecessarily.
            if (network != defaultNetwork) {
                Log.d("ConnectionManager", "Ignoring stale network lost: $network (current=$defaultNetwork)")
                return
            }
            defaultNetwork = null
            if (_state.value.phase == ConnectionPhase.CONNECTED) networkLostWhileConnected = true
            Log.d("ConnectionManager", "Default network lost: $network")
        }
    }

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var loop: ConnectionLoop? = null
    private var api: DshApiClient? = null
    private var activeHost: HostConfig? = null
    private var activeBaseUrl: String? = null
    private var pendingTransportReady: (suspend (String) -> Unit)? = null
    private val authorizationResumeMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var teardownJob: Job? = null
    @Volatile private var transportRecoveryInFlight = false
    @Volatile private var networkLostWhileConnected = false
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var lifecycleEpoch = 0L
    @Volatile private var lastForegroundRecoveryAtMs = 0L

    init {
        runCatching {
            defaultNetwork = connectivity.activeNetwork
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.w("ConnectionManager", "Unable to register network callback", it) }
        // Apply the background-retention toggle immediately instead of waiting for a later
        // handshake. This also tears down the foreground-service notification when it is disabled.
        scope.launch {
            hostsStore.settings.collect { settings ->
                if (settings.keepConnectedInBackground && _state.value.phase == ConnectionPhase.CONNECTED) {
                    startService()
                } else if (!settings.keepConnectedInBackground) {
                    stopService()
                }
            }
        }
    }

    /**
     * The current generation, or null while disconnected.
     *
     * Screens need it for two things the unary client cannot give them: the mux, to open a
     * session journal or the control stream, and the `clientId` every waterfall answer has to
     * carry. It is replaced wholesale on each reconnect, so nothing may cache the mux out of it.
     */
    @Volatile
    var generation: HostGeneration? = null
        private set

    /**
     * Host event frames (screens subscribe here).
     *
     * One flow now, where there were two: 0.1.2 delivers notifications and pending waterfalls
     * on the single `$events` stream, and the mux/host split it replaced no longer exists.
     *
     * Nothing here is replayed after a reconnect. State that must survive one has to come from
     * a query or a stream baseline instead — an `emit` that arrives while disconnected is gone.
     */
    val eventFrames = kotlinx.coroutines.flow.MutableSharedFlow<RemoteEventFrame>(extraBufferCapacity = 256)

    private val sinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) {
            eventFrames.tryEmit(frame)
        }

        override fun onConnected(generation: HostGeneration) {
            this@ConnectionManager.generation = generation
            val host = activeHost
            if (host != null) scope.launch { hostsStore.touchHost(host.host, host.port) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = activeHost,
                description = generation.description,
                stage = ConnectStage.Connected,
                failure = null,
                attempts = 0,
                hasConnected = true,
            )
            maybeStartService()
        }

        override fun onStateChange(state: ConnectionState) {
            // The mux generation is no longer usable as soon as the carrier starts reconnecting.
            // Keep the unary client: it is an HTTP client, not the retired WebSocket generation, and
            // clearing it here makes the UI report "not connected" forever after the next successful
            // reconnect because the loop does not need to rebuild this stateless client.
            val current = _state.value
            if (state == ConnectionState.RECONNECTING) {
                generation = null
                // Only an established carrier moving from CONNECTED to RECONNECTING can be a
                // dead relay. A new loop also announces RECONNECTING as its first state.
                if (current.phase == ConnectionPhase.CONNECTED) {
                    markCarrierRecoveryNeeded()
                    recoverTransportAfterCarrierLoss()
                }
            }
            val phase = when {
                state == ConnectionState.CONNECTED -> ConnectionPhase.CONNECTED
                // The loop opens every generation the same way, but the first one is not a
                // *re*connect — calling it that is what let a never-connected attempt look like a
                // healthy session dropping, and hid it from the connect screen entirely.
                current.hasConnected -> ConnectionPhase.RECONNECTING
                else -> ConnectionPhase.CONNECTING
            }
            // Note: does not clear `failure`. The loop emits this on every retry, so clearing here
            // would erase the explanation a fraction of a second after showing it.
            _state.value = current.copy(phase = phase)
        }

        override fun onHandshakeStep(step: HandshakeStep) {
            val stage = when (step) {
                HandshakeStep.OPENING_MUX -> ConnectStage.OpeningStreams
                HandshakeStep.AWAITING_READY -> ConnectStage.Verifying
            }
            _state.value = _state.value.copy(stage = stage)
        }

        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            Log.w("ConnectionManager", "Generation $attempt failed: $failure")
            val host = activeHost
            _state.value = _state.value.copy(
                failure = ConnectFailure.from(failure),
                attempts = attempt,
            )
        }
    }

    val connectedApi: DshApiClient? get() = api

    /** Effective origin for pairing while the active mesh/SSH relay is alive. */
    fun pairingBaseUrl(config: HostConfig): String =
        activeBaseUrl.takeIf { activeHost?.id == config.id } ?: config.baseUrl

    /**
     * Start driving [config]. Progress and failure arrive through [state], not a callback.
     *
     * There used to be a 2500ms timer here that reported failure if the phase was still CONNECTING.
     * It could never fire: the loop's first act is to publish RECONNECTING, so the phase had always
     * moved on by the time the timer checked. The result was a Connect button that stayed disabled
     * forever with nothing on screen. The loop now reports each failed generation directly, which
     * is both sooner and specific.
     */
    suspend fun connect(
        config: HostConfig,
        afterTransportReady: suspend (baseUrl: String) -> Unit = {},
    ) {
        // Keep a pending mesh node alive: ZeroTier authorization is attached to that node identity,
        // and the transport manager reuses it when the user retries after approval.
        if (activeHost?.id != config.id || _state.value.authorizationPending == null) disconnect()
        teardownJob?.join()
        val epoch = lifecycleEpoch
        lifecycleMutex.withLock { connectLocked(config, afterTransportReady, epoch) }
    }

    private suspend fun connectLocked(
        config: HostConfig,
        afterTransportReady: suspend (baseUrl: String) -> Unit,
        epoch: Long,
    ) {
        activeHost = config
        pendingTransportReady = afterTransportReady
        val pending = _state.value.takeIf {
            activeHost == config && it.authorizationPending != null
        }
        _state.value = pending?.copy(
            phase = ConnectionPhase.CONNECTING,
            stage = ConnectStage.OpeningStreams,
        ) ?: ConnectionUiState(
            phase = ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
        )
        try {
            activeBaseUrl = startTransports(config)
            if (epoch != lifecycleEpoch) return
            _state.value = _state.value.copy(authorizationPending = null, tailscaleLoginUrl = null)
            afterTransportReady(activeBaseUrl!!)
            if (epoch != lifecycleEpoch) return
            pendingTransportReady = null
            api = clientFactory.clientFor(config, baseUrl = activeBaseUrl!!)
            val loop = ConnectionLoop(muxFactory(config, activeBaseUrl!!), sinks, LoopConfig())
            this.loop = loop
            loop.start()
        } catch (error: Throwable) {
            if (epoch != lifecycleEpoch) return
            sshTunnel.stop()
            if (error is MeshAuthorizationPending) {
                hostsStore.upsertHost(config)
                _state.value = ConnectionUiState(
                    phase = ConnectionPhase.DISCONNECTED,
                    host = config,
                    stage = ConnectStage.Idle,
                    authorizationPending = error.message.orEmpty(),
                    tailscaleLoginUrl = (error as? TailscaleLoginRequired)?.loginUrl,
                )
                return
            }
            meshTransport.stop()
            pendingTransportReady = null
            activeBaseUrl = null
            activeHost = null
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.DISCONNECTED,
                host = config,
                stage = ConnectStage.Idle,
                failure = ConnectFailure.Other(error.message ?: "Unable to start private-network transport"),
            )
            return
        }
        hostsStore.upsertHost(config)
    }

    fun disconnect() {
        lifecycleEpoch++
        loop?.stop()
        loop = null
        api = null
        activeBaseUrl = null
        pendingTransportReady = null
        generation = null
        activeHost = null
        val previousTeardown = teardownJob
        teardownJob = scope.launch {
            previousTeardown?.join()
            lifecycleMutex.withLock {
                sshTunnel.stop()
                meshTransport.stop()
            }
        }
        stopService()
        _state.value = ConnectionUiState()
    }

    /**
     * Recreate mesh and SSH layers after an established carrier dies.
     *
     * ConnectionLoop deliberately owns WebSocket retry, but it cannot make a stale loopback relay
     * usable again. Serialize one full renewal per outage so its own retries never race teardown.
     */
    /** Publish the dead-carrier transition before potentially slow relay teardown begins. */
    private fun markCarrierRecoveryNeeded() {
        val current = _state.value
        if (current.phase != ConnectionPhase.CONNECTING) {
            _state.value = current.copy(
                phase = ConnectionPhase.RECONNECTING,
                stage = ConnectStage.OpeningStreams,
                failure = null,
            )
        }
    }

    private fun recoverTransportAfterCarrierLoss() {
        if (transportRecoveryInFlight) {
            Log.d("ConnectionManager", "Transport recovery already in flight")
            return
        }
        Log.d("ConnectionManager", "Starting transport recovery")
        transportRecoveryInFlight = true
        reconnectIfNeeded(onFinished = {
            transportRecoveryInFlight = false
            Log.d("ConnectionManager", "Transport recovery finished")
        })
    }

    fun reconnectIfNeeded(onFinished: () -> Unit = {}) {
        val host = activeHost ?: run {
            onFinished()
            return
        }
        val epoch = lifecycleEpoch
        loop?.stop()
        scope.launch {
            try {
                teardownJob?.join()
                lifecycleMutex.withLock {
                    if (epoch != lifecycleEpoch || activeHost?.id != host.id) return@withLock
                    try {
                        // Reuse a live SSH forward when possible; SshTunnelManager validates the
                        // authenticated forward and replaces it only when its carrier is unusable.
                        activeBaseUrl = reconnectTransports(host)
                        if (epoch != lifecycleEpoch) return@withLock
                        api = clientFactory.clientFor(host, baseUrl = activeBaseUrl!!)
                        loop = ConnectionLoop(muxFactory(host, activeBaseUrl!!), sinks, LoopConfig()).also { it.start() }
                    } catch (error: Throwable) {
                        if (epoch != lifecycleEpoch) return@withLock
                        if (error is MeshAuthorizationPending) {
                            hostsStore.upsertHost(host)
                            _state.value = _state.value.copy(
                                phase = ConnectionPhase.DISCONNECTED,
                                stage = ConnectStage.Idle,
                                authorizationPending = error.message.orEmpty(),
                            )
                            return@withLock
                        }
                        meshTransport.stop()
                        activeBaseUrl = null
                        _state.value = _state.value.copy(
                            phase = ConnectionPhase.DISCONNECTED,
                            stage = ConnectStage.Idle,
                            failure = ConnectFailure.Other(error.message ?: "Unable to restart private-network transport"),
                        )
                    }
                }
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Called as the activity returns to the foreground. It skips the normal reconnect-loop delay:
     * if the carrier was suspended or the underlying mobile network changed while backgrounded,
     * immediately rebuild the stale relay path. The cooldown absorbs duplicate activity resumes.
     */
    fun recoverForForeground() {
        val current = _state.value
        Log.d("ConnectionManager", "Foreground recovery requested: phase=${current.phase}, active=${activeHost != null}, inFlight=$transportRecoveryInFlight")
        if (activeHost == null || current.phase == ConnectionPhase.CONNECTING || transportRecoveryInFlight) return
        // A carrier can disappear while Android keeps the app's logical state CONNECTED. A
        // suspended background carrier may instead leave the loop in DISCONNECTED/RECONNECTING
        // without a NetworkCallback. In both cases publish the recovery state before the relay
        // work starts, so the UI never presents that stale green connection.
        markCarrierRecoveryNeeded()
        val now = System.currentTimeMillis()
        if (now - lastForegroundRecoveryAtMs < FOREGROUND_RECOVERY_COOLDOWN_MS) return
        lastForegroundRecoveryAtMs = now
        recoverTransportAfterCarrierLoss()
    }

    /** Retry the retained mesh identity after an embedded authorization page completes. */
    suspend fun resumeAuthorization() {
        authorizationResumeMutex.withLock {
            if (_state.value.authorizationPending == null) return
            val host = activeHost ?: return
            val afterTransportReady = pendingTransportReady ?: {}
            connect(host, afterTransportReady)
        }
    }

    /**
     * A per-generation mux builder for [host].
     *
     * The loop calls this once per attempt and owns the socket's lifetime, so the credential is
     * re-read on every reconnect rather than baked in once. That matters for the same reason
     * [reconnectIfNeeded] rebuilds its client: a relay token can rotate while the app is
     * backgrounded, and a socket built with the old one is refused at the upgrade.
     */
    private fun muxFactory(host: HostConfig, baseUrl: String): suspend () -> RemoteStreamMux = {
        clientFactory.muxFor(host, baseUrl)
    }

    private suspend fun startTransports(config: HostConfig): String {
        val meshRelay = meshTransport.start(config)
        return finishTransportStart(config, meshRelay)
    }

    private suspend fun reconnectTransports(config: HostConfig): String {
        val startedAt = System.nanoTime()
        Log.d("ConnectionManager", "Reconnect transport start for ${config.id}")
        val meshRelay = meshTransport.reconnect(config)
        Log.d("ConnectionManager", "Mesh transport ready in ${elapsedMs(startedAt)}ms")
        val baseUrl = finishTransportStart(config, meshRelay)
        Log.d("ConnectionManager", "SSH/API relay ready in ${elapsedMs(startedAt)}ms at $baseUrl")
        return baseUrl
    }

    private fun elapsedMs(startedAt: Long): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private suspend fun finishTransportStart(config: HostConfig, meshRelay: MeshRelay?): String {
        if (!config.sshEnabled) return meshRelay?.baseUrl ?: config.baseUrl
        require(!config.useTls) { "TLS cannot be combined with the local SSH relay" }
        val sshHost = meshRelay?.host ?: config.host
        val sshPort = meshRelay?.port ?: config.sshPort
        return sshTunnel.start(config, sshHost, sshPort).baseUrl
    }


    private fun maybeStartService() {
        val settings = runBlockingRead { hostsStore.settingsOnce() }
        if (settings.keepConnectedInBackground) startService()
    }

    private fun startService() {
        val intent = Intent(context, ConnectionService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService() {
        context.stopService(Intent(context, ConnectionService::class.java))
    }

    private fun <T> runBlockingRead(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    private companion object {
        const val FOREGROUND_RECOVERY_COOLDOWN_MS = 1_000L
    }
}
