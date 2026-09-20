package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import dev.dsh.mobile.mesh.ui.shouldRearmConnectionRecoveryOverlay
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    /** True while the app has returned from background but the live carrier is not revalidated. */
    val foregroundCheckPending: Boolean = false,
    /** Global input fence while a background/lock-screen recovery rebuilds the connection. */
    val recoveryOverlayVisible: Boolean = false,
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
    private val networkTracker = DefaultNetworkTracker(connectivity.activeNetwork)
    private val networkRecoveryGate = NetworkRecoveryGate()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!appInForeground || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            if (networkRecoveryGate.isPending() && shouldStartNetworkRecovery(
                    isActiveNetwork = connectivity.activeNetwork == network,
                    hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                )) {
                Log.d("ConnectionManager", "Validated replacement network available: $network")
                markCarrierRecoveryNeeded()
                startPendingNetworkRecovery()
            }
        }

        override fun onAvailable(network: Network) {
            val connected = _state.value.phase == ConnectionPhase.CONNECTED
            val previous = networkTracker.current()
            networkTracker.onAvailable(network, connected)
            defaultNetwork = networkTracker.current()
            networkLostWhileConnected = networkTracker.hasRecoveryNeeded()
            if (networkLostWhileConnected) networkRecoveryGate.markPending()
            // A replacement network is also the event that unblocks a *stranded* recovery: the quick
            // attempts may already have run against the dead carrier. Re-arm while a desired host is
            // still unconnected, otherwise the app stays disconnected until the user intervenes.
            if (shouldReArmOnReplacementNetwork(
                    connected = connected,
                    networkChanged = previous != network,
                    hasDesiredHost = activeHost != null || suspendedHost != null,
                )) networkRecoveryGate.markPending()
            Log.d("ConnectionManager", "Default network available: $network (previous=$previous, dirty=${networkRecoveryGate.isPending()})")
            if (appInForeground && networkRecoveryGate.isPending()) {
                markCarrierRecoveryNeeded()
                if (shouldStartNetworkRecovery(
                        isActiveNetwork = connectivity.activeNetwork == network,
                        hasInternetCapability = connectivity.getNetworkCapabilities(network)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    )) {
                    startPendingNetworkRecovery()
                } else {
                    Log.d("ConnectionManager", "Replacement network is not internet-capable yet; keeping recovery pending")
                }
            }
        }

        override fun onLost(network: Network) {
            val before = networkTracker.current()
            networkTracker.onLost(network, _state.value.phase == ConnectionPhase.CONNECTED)
            defaultNetwork = networkTracker.current()
            networkLostWhileConnected = networkTracker.hasRecoveryNeeded()
            if (networkLostWhileConnected) networkRecoveryGate.markPending()
            if (before != network) {
                Log.d("ConnectionManager", "Ignoring stale network lost: $network (current=$before)")
            } else {
                Log.d("ConnectionManager", "Default network lost: $network")
            }
        }
    }

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var loop: ConnectionLoop? = null
    private var api: DshApiClient? = null
    private val eventApis = java.util.concurrent.ConcurrentHashMap<String, DshApiClient>()
    private var activeHost: HostConfig? = null
    private var activeBaseUrl: String? = null
    private var pendingTransportReady: (suspend (String) -> Unit)? = null
    private val authorizationResumeMutex = Mutex()
    /** Serializes every mutation of the singleton mesh/SSH resource stack. */
    private val lifecycleMutex = Mutex()
    /** Makes token validation and API/loop publication atomic against retirement. */
    private val publicationLock = Any()
    /** Protects replacement of the one manager-owned operation job. */
    private val operationLock = Any()
    private data class ConnectionIntent(
        val host: HostConfig,
        val afterTransportReady: suspend (String) -> Unit,
    )
    private val lifecycle = ConnectionLifecycleCoordinator<ConnectionIntent>()
    private var teardownJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var transportRecoveryInFlight = false
    @Volatile private var networkLostWhileConnected = false
    @Volatile private var defaultNetwork: Network? = null
    @Volatile private var lifecycleEpoch = 0L
    private val recoveryLock = Any()
    private val loopFence = RecoveryCallbackFence()
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var recoveryRetryJob: Job? = null
    @Volatile private var appInForeground = false
    @Volatile private var backgroundedAtMs = 0L
    private val lifecycleTransitionLock = Any()
    @Volatile private var keepConnectedInBackground = false
    @Volatile private var foregroundProbeJob: Job? = null
    /** Latest desired host retained across a background-disabled suspension. */
    @Volatile private var suspendedHost: HostConfig? = null
    private var suspendedTransportReady: (suspend (String) -> Unit)? = null
    @Volatile private var suspendedForBackground = false

    init {
        // SSHJ only exposes a forwarder's terminal event by returning from listen(). Turn that into
        // the same state transition as a mux carrier failure instead of waiting for a timer, ping,
        // or the next user request to discover a dead local relay.
        sshTunnel.onRelayTerminated = { token -> onSshRelayTerminated(token) }
        runCatching {
            defaultNetwork = connectivity.activeNetwork
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.w("ConnectionManager", "Unable to register network callback", it) }
        // Apply the background-retention toggle immediately instead of waiting for a later
        // handshake. This also tears down the foreground-service notification when it is disabled.
        scope.launch {
            hostsStore.settings.collect { settings ->
                keepConnectedInBackground = settings.keepConnectedInBackground
                lifecycle.setRetainInBackground(settings.keepConnectedInBackground)
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

    private fun sinksFor(token: RecoveryCallbackFence.Token): LoopSinks = object : LoopSinks {
        override fun onEventFrame(frame: RemoteEventFrame) {
            loopFence.runIfCurrent(token) { eventFrames.tryEmit(frame) }
        }

        override fun onConnected(generation: HostGeneration) {
            loopFence.runIfCurrent(token) {
            this@ConnectionManager.generation = generation
            eventApis[generation.clientId] = api ?: return@runIfCurrent
            val host = activeHost
            Log.d("ConnectionManager", "Connected generation published for ${host?.id}")
            if (host != null) scope.launch { hostsStore.touchHost(host.host, host.port) }
            _state.value = ConnectionUiState(
                phase = ConnectionPhase.CONNECTED,
                host = activeHost,
                description = generation.description,
                stage = ConnectStage.Connected,
                failure = null,
                attempts = 0,
                hasConnected = true,
                recoveryOverlayVisible = false,
            )
            maybeStartService()
            }
        }

        override fun onStateChange(state: ConnectionState) {
            loopFence.runIfCurrent(token) {
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
                    // A foreground carrier failure must recover immediately. Background recovery is
                    // still deferred to onStart, where the network path is stable and serialized.
                    if (appInForeground) recoverTransportAfterCarrierLoss()
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
            _state.value = current.copy(
                phase = phase,
                foregroundCheckPending = current.foregroundCheckPending ||
                    (current.hasConnected && phase == ConnectionPhase.RECONNECTING && appInForeground),
                recoveryOverlayVisible = current.recoveryOverlayVisible ||
                    (current.hasConnected && phase == ConnectionPhase.RECONNECTING),
            )
            }
        }

        override fun onHandshakeStep(step: HandshakeStep) {
            loopFence.runIfCurrent(token) {
            val stage = when (step) {
                HandshakeStep.OPENING_MUX -> ConnectStage.OpeningStreams
                HandshakeStep.AWAITING_READY -> ConnectStage.Verifying
            }
            _state.value = _state.value.copy(stage = stage)
            }
        }

        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            loopFence.runIfCurrent(token) {
            Log.w("ConnectionManager", "Generation $attempt failed: $failure")
            _state.value = _state.value.copy(
                failure = ConnectFailure.from(failure),
                attempts = attempt,
            )
            // A loop-level mux retry cannot revive a stale ZeroTier/SSH relay. Renew the physical
            // carrier too, including while the foreground service retains the process in background.
            if (shouldRenewCarrierAfterGenerationFailure(
                    hasActiveHost = activeHost != null,
                    appInForeground = appInForeground,
                    retainInBackground = keepConnectedInBackground,
                    recoveryInFlight = synchronized(recoveryLock) { transportRecoveryInFlight },
                )) {
                markCarrierRecoveryNeeded()
                recoverTransportAfterCarrierLoss()
            }
            }
        }
    }

    val connectedApi: DshApiClient? get() = api

    fun apiForEvent(clientId: String): DshApiClient? = eventApis[clientId]

    fun bindEventApi(clientId: String, client: DshApiClient) {
        eventApis[clientId] = client
    }


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
        val target = lifecycle.request(ConnectionIntent(config, afterTransportReady))
        suspendedHost = config
        suspendedTransportReady = afterTransportReady
        suspendedForBackground = !lifecycle.mayRun()
        if (lifecycle.mayRun()) replaceOperation(target, reconnect = false)
    }

    private fun replaceOperation(
        target: ConnectionLifecycleCoordinator.Target<ConnectionIntent>,
        reconnect: Boolean,
        attempt: Int = 0,
        preservePendingIdentity: Boolean = false,
    ) {
        val job: Job
        synchronized(operationLock) {
            val previousOperation = connectJob
            previousOperation?.cancel()
            if (previousOperation?.isActive == true) meshTransport.cancelTailscaleStart()
            cancelAuxiliaryOperations()
            retirePublishedConnection()
            val previousTeardown = teardownJob
            job = scope.launch {
                previousOperation?.join()
                previousTeardown?.join()
                lifecycleMutex.withLock {
                    if (!lifecycle.accepts(target.token)) return@withLock
                    sshTunnel.stop()
                    if (!reconnect && shouldStopMeshBeforeConnect(
                            activeTransport = meshTransport.activeTransport(),
                            nextTransport = target.value.host.meshTransport,
                            preservePendingIdentity = preservePendingIdentity,
                        )) meshTransport.stop()
                }
                if (!lifecycle.accepts(target.token)) return@launch
                runConnectionOperation(target, reconnect, attempt, preservePendingIdentity)
            }
            connectJob = job
        }
        job.invokeOnCompletion {
            synchronized(operationLock) {
                if (connectJob === job) connectJob = null
            }
            // A handover noticed while this operation was already building its relay must not be
            // dropped: that operation dialled the retired path, so re-arm recovery now the slot is free.
            synchronized(recoveryLock) {
                transportRecoveryInFlight = false
            }
            if (networkRecoveryGate.isPending() && appInForeground) {
                val activeNetwork = connectivity.activeNetwork
                val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                if (shouldStartNetworkRecovery(
                        isActiveNetwork = activeNetwork != null,
                        hasInternetCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    )) startPendingNetworkRecovery()
                else Log.d("ConnectionManager", "Pending recovery remains deferred until network validation")
            }
        }
    }

    private suspend fun runConnectionOperation(
        target: ConnectionLifecycleCoordinator.Target<ConnectionIntent>,
        reconnect: Boolean,
        attempt: Int,
        preservePendingIdentity: Boolean,
    ) {
        val intent = target.value
        val config = intent.host
        val epoch = lifecycleEpoch
        activeHost = config
        pendingTransportReady = intent.afterTransportReady
        _state.value = ConnectionUiState(
            phase = if (reconnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
            host = config,
            stage = ConnectStage.OpeningStreams,
            hasConnected = _state.value.hasConnected,
            foregroundCheckPending = reconnect,
            recoveryOverlayVisible = reconnect && _state.value.hasConnected,
            authorizationPending = _state.value.authorizationPending,
            tailscaleLoginUrl = _state.value.tailscaleLoginUrl,
        )
        suspend fun handleOperationFailure(error: Throwable) {
            if (!lifecycle.accepts(target.token)) {
                cleanupResources()
                return
            }
            if (error is MeshAuthorizationPending) {
                hostsStore.upsertHost(config)
                // Keep the retained tsnet identity and its login URL. A pending authorization is
                // not a failed connection and must not enter the reconnect loop; the ViewModel's
                // single polling job is the only code allowed to request a resume.
                lifecycle.pauseForAuthorization()
                _state.value = ConnectionUiState(
                    phase = ConnectionPhase.DISCONNECTED,
                    host = config,
                    stage = ConnectStage.Idle,
                    hasConnected = _state.value.hasConnected,
                    recoveryOverlayVisible = false,
                    authorizationPending = error.message.orEmpty(),
                    tailscaleLoginUrl = (error as? TailscaleLoginRequired)?.loginUrl,
                )
                return
            }
            val willRetry = reconnect && lifecycle.mayRun() && error !is MeshAuthorizationPending
            Log.w(
                "ConnectionManager",
                "Connection operation failed (reconnect=$reconnect, timeout=${error is TimeoutCancellationException}): ${error.message}",
            )
            activeBaseUrl = null
            api = null
            _state.value = ConnectionUiState(
                phase = if (willRetry) ConnectionPhase.RECONNECTING else ConnectionPhase.DISCONNECTED,
                host = config,
                stage = if (willRetry) ConnectStage.OpeningStreams else ConnectStage.Idle,
                failure = ConnectFailure.Other(error.message ?: "Unable to start private-network transport"),
                hasConnected = _state.value.hasConnected,
                recoveryOverlayVisible = willRetry && _state.value.hasConnected,
                authorizationPending = _state.value.authorizationPending,
                tailscaleLoginUrl = _state.value.tailscaleLoginUrl,
            )
            cleanupResources()
            if (willRetry) scheduleRetry(
                (attempt + 1).coerceAtMost(FOREGROUND_RECOVERY_FAST_ATTEMPTS),
                target.token,
            )
        }
        try {
            val transportStartedAt = System.nanoTime()
            val baseUrl = lifecycleMutex.withLock {
                val timeoutMs = transportOperationTimeoutMs(config, preservePendingIdentity)
                withTimeout(timeoutMs) {
                    if (reconnect || preservePendingIdentity) reconnectTransports(config) else startTransports(config)
                }
            }
            val transportLatencyMs = heartbeatLatencySample(transportStartedAt, System.nanoTime())
            Log.d("ConnectionManager", "Transport stack returned; entering callback boundary reconnect=$reconnect preserve=$preservePendingIdentity latencyMs=$transportLatencyMs")
            val acceptsTransport = lifecycle.accepts(target.token)
            Log.d("ConnectionManager", "Transport acceptance=$acceptsTransport target=${target.token} epoch=$lifecycleEpoch")
            Log.d("ConnectionManager", "Transport stack returned baseUrl=$baseUrl accepts=$acceptsTransport reconnect=$reconnect")
            if (!acceptsTransport) {
                Log.w("ConnectionManager", "Transport ready discarded because lifecycle target is stale")
                cleanupResources()
                return
            }
            activeBaseUrl = baseUrl
            Log.d("ConnectionManager", "Transport ready callback about to run baseUrl=$baseUrl reconnect=$reconnect")
            _state.value = _state.value.copy(authorizationPending = null, tailscaleLoginUrl = null)
            if (shouldRunTransportReadyCallback(reconnect, preservePendingIdentity)) {
                withTimeout(TRANSPORT_READY_CALLBACK_TIMEOUT_MS) {
                    intent.afterTransportReady(baseUrl)
                }
                Log.d("ConnectionManager", "Transport ready callback finished")
            } else {
                Log.d("ConnectionManager", "Transport ready callback skipped for ordinary carrier recovery")
            }
            if (!lifecycle.accepts(target.token)) {
                cleanupResources()
                return
            }
            val nextApi = clientFactory.clientFor(config, baseUrl = baseUrl)
            synchronized(publicationLock) {
                if (!lifecycle.accepts(target.token)) return
                pendingTransportReady = null
                api = nextApi
                eventApis.clear()
                val token = loopFence.next()
                loop = ConnectionLoop(muxFactory(config, baseUrl, transportLatencyMs), sinksFor(token), LoopConfig()).also { it.start() }
            }
            hostsStore.upsertHost(config)
            hostsStore.setActiveConnectionId(config.id)
        } catch (error: CancellationException) {
            cleanupResources()
            throw error
        } catch (error: Throwable) {
            handleOperationFailure(error)
        } finally {
            if (lifecycle.accepts(target.token)) {
                _state.value = _state.value.copy(
                    foregroundCheckPending = false,
                    recoveryOverlayVisible = false,
                )
            }
            Log.d("ConnectionManager", "Connection operation finished (reconnect=$reconnect, epoch=$epoch)")
        }
    }

    private suspend fun cleanupResources() {
        lifecycleMutex.withLock {
            sshTunnel.stop()
            meshTransport.stop()
        }
    }

    fun disconnect() {
        lifecycle.disconnect()
        scope.launch { hostsStore.setActiveConnectionId(null) }
        suspendedHost = null
        suspendedTransportReady = null
        suspendedForBackground = false
        stopConnection()
    }

    private fun cancelAuxiliaryOperations() {
        lifecycleEpoch++
        foregroundProbeJob?.cancel()
        foregroundProbeJob = null
        recoveryRetryJob?.cancel()
        recoveryRetryJob = null
        synchronized(recoveryLock) {
            recoveryJob?.cancel()
            recoveryJob = null
            transportRecoveryInFlight = false
        }
    }

    private fun retirePublishedConnection() {
        synchronized(publicationLock) {
            loopFence.invalidate()
            loop?.stop()
            loop = null
            api = null
            activeBaseUrl = null
            generation = null
            eventApis.clear()
            activeHost = null
        }
        stopService()
    }

    private fun stopConnection() {
        networkRecoveryGate.clear()
        networkTracker.consumeRecoveryNeeded()
        networkLostWhileConnected = false
        synchronized(operationLock) {
            val previousOperation = connectJob
            previousOperation?.cancel()
            cancelAuxiliaryOperations()
            retirePublishedConnection()
            pendingTransportReady = null
            val previousTeardown = teardownJob
            teardownJob = scope.launch {
                previousOperation?.join()
                previousTeardown?.join()
                lifecycleMutex.withLock {
                    sshTunnel.stop()
                    meshTransport.stop()
                }
            }
            connectJob = null
        }
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
                recoveryOverlayVisible = current.hasConnected,
            )
        } else if (current.hasConnected && !current.recoveryOverlayVisible) {
            _state.value = current.copy(recoveryOverlayVisible = true)
        }
    }

    private fun recoverTransportAfterCarrierLoss() = reconnectIfNeeded()

    /**
     * The SSH forwarder has an authoritative terminal event: its listener returned. Do not wait for
     * an HTTP/WebSocket timeout to infer this locally-observable relay failure.
     */
    private fun onSshRelayTerminated(token: Long) {
        val current = _state.value
        if (token != sshTunnel.activeRelayToken || activeHost?.sshEnabled != true || current.phase != ConnectionPhase.CONNECTED) return
        Log.w("ConnectionManager", "Active SSH relay terminated; scheduling transport recovery")
        generation = null
        markCarrierRecoveryNeeded()
        if (appInForeground) recoverTransportAfterCarrierLoss()
    }

    /**
     * Replace a dead relay through exactly one serialized recovery job.
     *
     * onResume, network callbacks and WorkManager can all notice the same dead carrier. They must
     * converge on the same job: two concurrent libzt/SSH replacements can tear down the other's
     * socket while it is handshaking and were the source of foreground crashes.
     */
    fun reconnectIfNeeded() {
        if (_state.value.authorizationPending != null) {
            Log.d("ConnectionManager", "Reconnect skipped during authorization")
            return
        }
        startRecovery(0)
    }

    private fun startPendingNetworkRecovery() {
        val canStart = synchronized(operationLock) { connectJob?.isActive != true }
        if (!networkRecoveryGate.consumeIfCanStart(canStart)) {
            Log.d("ConnectionManager", "Network handover recovery remains pending until operation slot is free")
            return
        }
        networkTracker.consumeRecoveryNeeded()
        networkLostWhileConnected = false
        startRecovery(0)
        // startRecovery can still lose a narrow race to a newly published operation. Re-arm rather
        // than dropping the replacement-network event.
        if (synchronized(operationLock) { connectJob?.isActive != true }) networkRecoveryGate.markPending()
    }

    private fun startRecovery(attempt: Int) {
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (!shouldStartNetworkRecovery(
                isActiveNetwork = activeNetwork != null && connectivity.activeNetwork == activeNetwork,
                hasInternetCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            )) {
            Log.d("ConnectionManager", "Recovery deferred until active network is Internet-capable")
            networkRecoveryGate.markPending()
            return
        }
        if (recoveryRetryJob?.isActive == true) {
            Log.d("ConnectionManager", "Transport recovery retry already scheduled")
            return
        }
        if (synchronized(operationLock) { connectJob?.isActive == true }) {
            Log.d("ConnectionManager", "Connection operation already in flight")
            return
        }
        val target = lifecycle.retryToken() ?: return
        synchronized(recoveryLock) {
            if (transportRecoveryInFlight) {
                Log.d("ConnectionManager", "Transport recovery already scheduled")
                return
            }
            transportRecoveryInFlight = true
        }
        Log.d("ConnectionManager", "Starting tokened transport recovery (attempt ${attempt + 1})")
        replaceOperation(target, reconnect = true, attempt = attempt)
    }

    private fun scheduleRetry(
        attempt: Int,
        failedToken: ConnectionLifecycleCoordinator.Token,
    ) {
        recoveryRetryJob?.cancel()
        recoveryRetryJob = scope.launch {
            kotlinx.coroutines.delay(recoveryRetryDelayMs(attempt))
            val current = lifecycle.current() ?: return@launch
            if (!lifecycle.mayRun() || current.token != failedToken) return@launch
            if (_state.value.authorizationPending != null) {
                Log.d("ConnectionManager", "Scheduled recovery skipped during authorization")
                return@launch
            }
            recoveryRetryJob = null
            startRecovery(attempt)
        }
    }

    /**
     * Called as the activity returns to the foreground. It skips the normal reconnect-loop delay:
     * if the carrier was suspended or the underlying mobile network changed while backgrounded,
     * immediately rebuild the stale relay path. The cooldown absorbs duplicate activity resumes.
     */
    fun recoverForForeground() {
        synchronized(lifecycleTransitionLock) {
            if (!shouldHandleLifecycleTransition(appInForeground, targetForeground = true)) return
            appInForeground = true
        }
        // Returning from Google sign-in can recreate the Activity while tsnet is still waiting for
        // authorization. Re-publish the retained login URL and keep the pending identity alive; do
        // not let ordinary foreground recovery replace it with the settings screen.
        if (shouldDeferForegroundRecoveryForAuthorization(
                authorizationPending = _state.value.authorizationPending != null,
                loginUrl = _state.value.tailscaleLoginUrl,
            )) {
            Log.d("ConnectionManager", "Foreground recovery deferred during authorization")
            // The WebView may complete Google sign-in while this Activity is stopped. Kick one
            // immediate native status check on return instead of waiting for the ViewModel polling
            // coroutine to be recreated; a successful check clears the URL and closes the dialog.
            if (shouldResumeAuthorizationOnForeground(
                    authorizationPending = _state.value.authorizationPending != null,
                    loginUrl = _state.value.tailscaleLoginUrl,
                )) {
                scope.launch { resumeAuthorization() }
            }
            return
        }
        val currentBeforeResume = _state.value
        val currentPhaseBeforeResume = currentBeforeResume.phase
        val backgroundDurationBeforeResume = backgroundDurationSinceLastStopMs()
        // A very short background transition can clear the presentation latch in onStop before the
        // transport loop publishes RECONNECTING. Re-arm the global input fence from the authoritative
        // phase/gate snapshot so a yellow reconnecting status can never appear without the spinner.
        if (currentBeforeResume.hasConnected && (
                currentPhaseBeforeResume != ConnectionPhase.CONNECTED ||
                    currentBeforeResume.foregroundCheckPending ||
                    networkRecoveryGate.isPending() ||
                    networkLostWhileConnected ||
                    (currentPhaseBeforeResume == ConnectionPhase.CONNECTED &&
                        backgroundDurationBeforeResume >= FOREGROUND_VERIFY_AFTER_MS)
            )) {
            // Show the input fence immediately, but do not set foregroundCheckPending before
            // choosing the action below: foregroundRecoveryAction treats that flag as an already
            // running probe and would return NONE, leaving a retained CONNECTED carrier stuck under
            // the spinner after a long background stay.
            _state.value = currentBeforeResume.copy(
                recoveryOverlayVisible = true,
                foregroundCheckPending = currentBeforeResume.foregroundCheckPending ||
                    currentPhaseBeforeResume != ConnectionPhase.CONNECTED,
            )
            Log.d("ConnectionManager", "Foreground resume re-armed recovery overlay")
        }
        val resumedTarget = lifecycle.foreground()
        if (resumedTarget != null && currentPhaseBeforeResume != ConnectionPhase.CONNECTED) {
            // A retained service may still be completing the same recovery while the Activity is
            // recreated. Do not cancel its operation and wait behind its teardown; only re-arm when
            // there is no live operation to own the carrier.
            suspendedForBackground = false
            if (synchronized(operationLock) { connectJob?.isActive == true }) {
                // onAppBackgrounded() clears this presentation latch while retaining the transport.
                // Re-arm it before returning so the existing session is visibly blocked for the
                // remainder of the in-flight foreground recovery.
                if (shouldRearmConnectionRecoveryOverlay(
                        hasConnected = _state.value.hasConnected,
                        recoveryInFlight = true,
                    )) {
                    _state.value = _state.value.copy(
                        foregroundCheckPending = true,
                        recoveryOverlayVisible = true,
                    )
                }
                Log.d("ConnectionManager", "Foreground recovery already in flight; keeping current operation")
                return
            }
            Log.d("ConnectionManager", "Foreground resume renews connection for ${resumedTarget.value.host.id}")
            replaceOperation(resumedTarget, reconnect = true)
            return
        }
        if (connectJob?.isActive != true && currentPhaseBeforeResume == ConnectionPhase.DISCONNECTED) {
            // A manual connect requested while the Activity was still starting can be stranded when
            // lifecycle.mayRun() was false. Re-arm the latest desired intent on the first foreground.
            val pending = lifecycle.current()
            if (pending != null) {
                Log.d("ConnectionManager", "Foreground resumes pending connection for ${pending.value.host.id}")
                replaceOperation(pending, reconnect = false)
                suspendedForBackground = false
                return
            }
        }
        if (suspendedForBackground || (resumedTarget != null && activeHost == null && connectJob?.isActive != true)) {
            suspendedForBackground = false
            if (resumedTarget != null) {
                Log.d("ConnectionManager", "Foreground resume starts latest desired connection for ${resumedTarget.value.host.id}")
                replaceOperation(resumedTarget, reconnect = false)
            }
            return
        }
        val current = _state.value
        val now = System.currentTimeMillis()
        val backgroundDuration = (now - backgroundedAtMs).coerceAtLeast(0L)
        val action = foregroundRecoveryAction(
            ForegroundRecoveryFacts(
                phase = current.phase,
                hasActiveHost = activeHost != null,
                recoveryInFlight = connectJob?.isActive == true || foregroundProbeJob?.isActive == true,
                backgroundDurationMs = backgroundDuration,
                networkChanged = networkLostWhileConnected || networkRecoveryGate.isPending(),
                foregroundCheckPending = current.foregroundCheckPending,
            ),
        )
        Log.d("ConnectionManager", "Foreground recovery requested: phase=${current.phase}, backgroundMs=$backgroundDuration, action=$action")
        if (current.foregroundCheckPending) return
        if (action == ForegroundRecoveryAction.VERIFY) {
            _state.value = current.copy(foregroundCheckPending = true)
        }
        when (action) {
            ForegroundRecoveryAction.NONE -> {
                if (current.phase == ConnectionPhase.CONNECTING && activeHost != null && !current.foregroundCheckPending) {
                    startRecovery(0)
                }
                return
            }
            ForegroundRecoveryAction.RECOVER -> {
                // Never cooldown a stranded reconnect. onStop cancels its delayed retry; onStart is
                // the authoritative signal that must re-arm recovery when no job is currently alive.
                if (!shouldStartForegroundRecovery(
                        action,
                        connectJob?.isActive == true,
                    )) return
                _state.value = _state.value.copy(foregroundCheckPending = true)
                markCarrierRecoveryNeeded()
                if (networkRecoveryGate.isPending()) {
                    startPendingNetworkRecovery()
                } else {
                    networkTracker.consumeRecoveryNeeded()
                    networkLostWhileConnected = false
                    recoverTransportAfterCarrierLoss()
                }
            }
            ForegroundRecoveryAction.VERIFY -> {
                val expectedGeneration = generation
                val expectedApi = api
                val expectedHostId = activeHost?.id
                val expectedEpoch = lifecycleEpoch
                foregroundProbeJob = scope.launch {
                    val mux = expectedGeneration?.mux
                    val carrierOpen = mux != null && !mux.isClosed
                    val result = if (carrierOpen) runCatching {
                        kotlinx.coroutines.withTimeout(FOREGROUND_PROBE_TIMEOUT_MS) {
                            expectedApi?.connectionProbe()
                        }
                    }.getOrNull() else null
                    val reachedHost = foregroundProbeReachedHost(carrierOpen, result)
                    if (!appInForeground || expectedEpoch != lifecycleEpoch || activeHost?.id != expectedHostId || generation !== expectedGeneration) return@launch
                    if (reachedHost) {
                        Log.d("ConnectionManager", "Foreground end-to-end probe succeeded")
                        _state.value = _state.value.copy(
                    foregroundCheckPending = false,
                    recoveryOverlayVisible = false,
                )
                    } else {
                        Log.w("ConnectionManager", "Foreground end-to-end probe failed; renewing transport")
                        _state.value = _state.value.copy(
                            foregroundCheckPending = true,
                            recoveryOverlayVisible = true,
                        )
                        generation = null
                        markCarrierRecoveryNeeded()
                        recoverTransportAfterCarrierLoss()
                    }
                }.also { job ->
                    job.invokeOnCompletion {
                        synchronized(recoveryLock) {
                            if (foregroundProbeJob === job) foregroundProbeJob = null
                        }
                    }
                }
            }
        }
    }

    private fun backgroundDurationSinceLastStopMs(): Long =
        (System.currentTimeMillis() - backgroundedAtMs).coerceAtLeast(0L)

    /** Mark carrier callbacks as backgrounded; foreground recovery is resumed explicitly on resume. */
    fun onAppBackgrounded() {
        synchronized(lifecycleTransitionLock) {
            if (!shouldHandleLifecycleTransition(appInForeground, targetForeground = false)) return
            appInForeground = false
        }
        lifecycle.background()
        backgroundedAtMs = System.currentTimeMillis()
        foregroundProbeJob?.cancel()
        foregroundProbeJob = null
        if (backgroundConnectionAction(keepConnectedInBackground) == BackgroundConnectionAction.SUSPEND &&
            !shouldPreserveAuthorizationOnBackground(
                authorizationPending = _state.value.authorizationPending != null,
                loginUrl = _state.value.tailscaleLoginUrl,
            )) {
            recoveryRetryJob?.cancel()
            recoveryRetryJob = null
            // Do not let a startup/recovery coroutine retain lifecycleMutex while Android suspends
            // its network path. Invalidate it, tear every carrier down, then restart exactly the
            // latest desired host from a clean generation on foreground.
            val desiredHost = activeHost ?: suspendedHost
            val desiredCallback = pendingTransportReady ?: suspendedTransportReady
            Log.d("ConnectionManager", "Background retention disabled; suspending active connection")
            stopConnection()
            suspendedHost = desiredHost
            suspendedTransportReady = desiredCallback
            suspendedForBackground = desiredHost != null
        } else {
            _state.value = _state.value.copy(
                foregroundCheckPending = false,
                recoveryOverlayVisible = false,
            )
        }
    }

    /** Retry the retained mesh identity after an embedded authorization page completes. */
    suspend fun resumeAuthorization() {
        authorizationResumeMutex.withLock {
            if (!shouldStartAuthorizationResume(
                    authorizationPending = _state.value.authorizationPending != null,
                    lifecycleMayRun = lifecycle.mayRun(),
                    operationInFlight = synchronized(operationLock) { connectJob?.isActive == true },
                )) return
            lifecycle.resumeAfterAuthorization()
            val target = lifecycle.retryToken() ?: return
            _state.value = _state.value.copy(
                phase = ConnectionPhase.CONNECTING,
                stage = ConnectStage.OpeningStreams,
            )
            replaceOperation(target, reconnect = false, preservePendingIdentity = true)
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
    private fun muxFactory(
        host: HostConfig,
        baseUrl: String,
        initialLatencyMs: Long? = null,
    ): suspend () -> RemoteStreamMux = {
        clientFactory.muxFor(host, baseUrl, initialLatencyMs)
    }

    private suspend fun startTransports(config: HostConfig): String {
        Log.d("ConnectionManager", "Starting transports host=${config.host}:${config.port} mesh=${config.meshTransport} ssh=${config.sshEnabled}")
        val meshRelay = meshTransport.start(config)
        Log.d("ConnectionManager", "Mesh transport returned relay=${meshRelay?.host}:${meshRelay?.port}")
        return finishTransportStart(config, meshRelay)
    }

    private suspend fun reconnectTransports(config: HostConfig): String {
        val timing = RecoveryTiming()
        val startedAt = timing.start("transport-total")
        Log.d("ConnectionManager", "Reconnect transport start for ${config.id}")
        // A backgrounded TCP carrier can look alive to both libzt and SSH while no longer moving
        // bytes. Reusing that relay only makes the new mux time out forever. Stop the Java relay
        // endpoints first, then create fresh sockets against the retained ZeroTier node identity.
        // ZeroTierConnector.stop deliberately retains that process-global node, so this is not the
        // unsafe native NodeService teardown that previously crashed Pixel 3.
        val sshStopStartedAt = timing.start("ssh-stop")
        sshTunnel.stop()
        timing.phase("ssh-stop", sshStopStartedAt, "ok")
        val meshStartedAt = timing.start("mesh-reconnect")
        val meshRelay = try {
            meshTransport.reconnect(config).also {
                timing.phase("mesh-reconnect", meshStartedAt, "ok", "transport=${config.meshTransport ?: "direct"}")
            }
        } catch (error: Throwable) {
            timing.phase("mesh-reconnect", meshStartedAt, "error", "type=${error::class.simpleName}")
            throw error
        }
        Log.d("ConnectionManager", "Mesh transport ready in ${elapsedMs(startedAt)}ms")
        val sshStartedAt = timing.start("ssh-start")
        val baseUrl = try {
            finishTransportStart(config, meshRelay, recovery = true).also {
                timing.phase("ssh-start", sshStartedAt, "ok")
            }
        } catch (error: Throwable) {
            timing.phase("ssh-start", sshStartedAt, "error", "type=${error::class.simpleName}")
            timing.phase("transport-total", startedAt, "error", "type=${error::class.simpleName}")
            throw error
        }
        Log.d("ConnectionManager", "SSH/API relay ready in ${elapsedMs(startedAt)}ms at $baseUrl")
        timing.phase("transport-total", startedAt, "ok")
        return baseUrl
    }

    private fun elapsedMs(startedAt: Long): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private suspend fun finishTransportStart(
        config: HostConfig,
        meshRelay: MeshRelay?,
        recovery: Boolean = false,
    ): String {
        if (!config.sshEnabled) return meshRelay?.baseUrl ?: config.baseUrl
        require(!config.useTls) { "TLS cannot be combined with the local SSH relay" }
        val sshHost = meshRelay?.host ?: config.host
        val sshPort = meshRelay?.port ?: config.sshPort
        return sshTunnel.start(config, sshHost, sshPort, recovery = recovery).baseUrl
    }


    private fun maybeStartService() {
        if (keepConnectedInBackground) startService()
    }

    private fun startService() {
        val intent = Intent(context, ConnectionService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopService() {
        context.stopService(Intent(context, ConnectionService::class.java))
    }

    private companion object {
        /** Bound the resume probe so fake green is replaced promptly, even for a black-holed TCP path. */
        const val FOREGROUND_PROBE_TIMEOUT_MS = 1_500L
        const val FOREGROUND_RECOVERY_TRANSPORT_TIMEOUT_MS = 10_000L
        const val AUTHORIZATION_RESUME_TIMEOUT_MS = ConnectionTimeoutPolicy.authorizationResumeMs
        const val TRANSPORT_READY_CALLBACK_TIMEOUT_MS = ConnectionTimeoutPolicy.transportReadyCallbackMs
    }
}
