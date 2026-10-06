package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * libzt-backed, app-private transport. Android routes are unchanged: OkHttp connects to a random
 * loopback port and every accepted stream is dialled with [ZeroTierSocket].
 */
@Singleton
class ZeroTierConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val planets: ZeroTierPlanetStore,
) : MeshConnector {
    private val executor = Executors.newCachedThreadPool()
    private val lock = Any()
    private var node: ZeroTierNode? = null
    private var relay: ZeroTierRelay? = null
    private var nodeNetworkId: String? = null
    private var readiness: ZeroTierReadiness? = null
    private val generation = ZeroTierGeneration()

    private fun signal(state: ZeroTierReadiness, id: Long, eventCode: Int) {
        when (eventCode) {
            ZeroTierNative.ZTS_EVENT_NODE_ONLINE -> state.nodeOnline()
            ZeroTierNative.ZTS_EVENT_NODE_OFFLINE, ZeroTierNative.ZTS_EVENT_NODE_DOWN -> state.nodeOffline()
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4,
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP6,
            ZeroTierNative.ZTS_EVENT_NETWORK_OK -> state.networkReady(id)
            ZeroTierNative.ZTS_EVENT_NETWORK_DOWN -> state.networkUnavailable(id)
            ZeroTierNative.ZTS_EVENT_NETWORK_ACCESS_DENIED -> state.networkError(
                id, MeshAuthorizationPending("ZeroTier network access denied"),
            )
            ZeroTierNative.ZTS_EVENT_NETWORK_NOT_FOUND -> state.networkError(
                id, IllegalStateException("ZeroTier network not found"),
            )
        }
    }

    override suspend fun start(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.ZERO_TIER) { "Not a ZeroTier host" }
        val networkIdText = config.zeroTierNetworkId?.lowercase()
            ?: throw IllegalArgumentException("A ZeroTier network ID is required")
        require(networkIdText.matches(Regex("[0-9a-f]{16}"))) {
            "ZeroTier network ID must contain exactly 16 hexadecimal characters"
        }
        val networkId = java.lang.Long.parseUnsignedLong(networkIdText, 16)
        val timing = ZeroTierTiming()
        val selectStart = timing.start("node-select")
        // Only native initialization and relay ownership are serialized. No coroutine suspends
        // while holding this monitor, so stop and subsequent starts can invalidate a pending wait.
        var reused = false
        val (current, state, token) = synchronized(lock) {
            coroutineContext.ensureActive()
            val existing = node
            reused = existing != null
            val pair = if (existing != null) {
                // libzt has a process-global service. Reinitializing it for different storage or
                // roots while sockets/callbacks remain live is not a safe network switch.
                require(nodeNetworkId == networkIdText) {
                    "Switching ZeroTier networks requires restarting the app process"
                }
                Log.d(TAG, "Reusing ZeroTier node for network $networkIdText (online=${isServiceOnline()})")
                existing to checkNotNull(readiness)
            } else {
                // A ZeroTier identity belongs to the network, not a particular DSH host.
                val storage = java.io.File(context.noBackupFilesDir, "zerotier/networks/$networkIdText").apply { mkdirs() }
                val roots = java.io.File(storage, "roots")
                val planetId = config.zeroTierPlanetId
                val planet = planetId?.let(planets::resolve)
                if (planetId != null && planet == null) throw IOException("Configured ZeroTier planet is missing")
                if (planet == null) roots.delete() else planet.copyTo(roots, overwrite = true)
                val nextNode = ZeroTierNode()
                val nextReadiness = ZeroTierReadiness()
                val initStart = timing.start("node-init-storage")
                checkResult(nextNode.initFromStorage(storage.absolutePath), "initialize ZeroTier")
                timing.phase("node-init-storage", initStart, "ok")
                val rootsStart = timing.start("node-init-roots")
                checkResult(nextNode.initAllowRootsCache(planet == null), "configure ZeroTier")
                timing.phase("node-init-roots", rootsStart, "ok", detail = "rootsCache=${planet == null}")
                val handlerStart = timing.start("node-init-handler")
                checkResult(nextNode.initSetEventHandler(object : ZeroTierEventListener {
                    override fun onZeroTierEvent(id: Long, eventCode: Int) {
                        signal(nextReadiness, id, eventCode)
                    }
                }), "register ZeroTier event handler")
                timing.phase("node-init-handler", handlerStart, "ok")
                val startStart = timing.start("node-start")
                checkResult(nextNode.start(), "start ZeroTier")
                timing.phase("node-start", startStart, "ok", detail = "nodeId=${java.lang.Long.toUnsignedString(nextNode.id, 16)}")
                node = nextNode
                readiness = nextReadiness
                nodeNetworkId = networkIdText
                nextNode to nextReadiness
            }
            Triple(pair.first, pair.second, generation.next())
        }
        timing.phase("node-select", selectStart, "ok", detail = "reused=$reused")
        val readyStart = timing.start("await-transport-ready")
        val (readySignal, dialedTarget) = awaitTransportReady(
            current,
            state,
            networkId,
            runCatching { resolveTarget(config) }.getOrNull(),
        )
        timing.phase(
            "await-transport-ready",
            readyStart,
            if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) "timeout" else "ok",
            detail = "signal=$readySignal online=${isServiceOnline()}",
        )
        check(readySignal != ZERO_TIER_TIMEOUT_SIGNAL) {
            "ZeroTier node did not come online; check internet access"
        }
        val joinStart = timing.start("join")
        var joined = false
        synchronized(lock) {
            coroutineContext.ensureActive()
            generation.checkCurrent(token)
            joined = !hasAddress(networkId)
            if (joined) checkResult(current.join(networkId), "join ZeroTier network")
        }
        timing.phase("join", joinStart, "ok", detail = "joined=$joined")
        val addressStart = timing.start("await-address")
        awaitAddress(current, networkId, state)
        timing.phase(
            "await-address",
            addressStart,
            "ok",
            detail = "transportReady=${transportReady(networkId)}",
        )
        val relayStart = timing.start("relay-open")
        val opened = synchronized(lock) {
            coroutineContext.ensureActive()
            generation.checkCurrent(token)
            relayFor(config, timing, dialedTarget)
        }
        timing.phase("relay-open", relayStart, "ok", detail = "port=${opened.port}")
        opened
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
        generation.next()
        relay?.close()
        relay = null
        // Keep readiness attached to the retained process-global node; its native callback cannot be
        // unregistered and must continue completing waits after a relay-only renewal.
        // libzt's NodeService owns callbacks for TCP sockets after Java has stopped using their
        // streams. Stopping its process-global service while one is draining races that callback
        // against NodeService destruction (a Pixel 3 FORTIFY abort in phyOnTcpClose). Retain the
        // node for this app process instead; reconnect creates a fresh loopback relay against the
        // still-authorized node. Android tears the native process state down atomically on process
        // exit, which is the only safe full-service shutdown boundary.
    }

    /** Close only the Java loopback relay; keep the native node and its authorization alive. */
    suspend fun renewRelay(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.ZERO_TIER) { "Not a ZeroTier host" }
        val timing = ZeroTierTiming()
        val networkId = java.lang.Long.parseUnsignedLong(checkNotNull(config.zeroTierNetworkId), 16)
        val selectStart = timing.start("node-select")
        val (current, state, token) = synchronized(lock) {
            coroutineContext.ensureActive()
            val networkIdText = config.zeroTierNetworkId?.lowercase()
            require(networkIdText != null && networkIdText == nodeNetworkId) {
                "ZeroTier relay renewal requires the retained network"
            }
            Triple(
                checkNotNull(node) { "ZeroTier node is not started" },
                checkNotNull(readiness) { "ZeroTier readiness is unavailable" },
                generation.next(),
            )
        }
        timing.phase("node-select", selectStart, "ok", detail = "reused=true")
        val readyStart = timing.start("await-transport-ready")
        val (readySignal, dialedTarget) = awaitTransportReady(
            current,
            state,
            networkId,
            runCatching { resolveTarget(config) }.getOrNull(),
        )
        timing.phase(
            "await-transport-ready",
            readyStart,
            if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) "timeout" else "ok",
            detail = "signal=$readySignal online=${isServiceOnline()}",
        )
        check(readySignal != ZERO_TIER_TIMEOUT_SIGNAL) {
            "ZeroTier node did not come online; check internet access"
        }
        val addressStart = timing.start("await-address")
        awaitAddress(current, networkId, state)
        timing.phase(
            "await-address",
            addressStart,
            "ok",
            detail = "transportReady=${transportReady(networkId)}",
        )
        val relayStart = timing.start("relay-open")
        val opened = synchronized(lock) {
            coroutineContext.ensureActive()
            generation.checkCurrent(token)
            relay?.close()
            relay = null
            relayFor(config, timing, dialedTarget)
        }
        timing.phase("relay-open", relayStart, "ok", detail = "port=${opened.port}")
        opened
    }

    /**
     * Wait until the mesh can carry this connection, ending on a real dial to the target or on
     * libzt's online report, whichever proves readiness first.
     *
     * The successful dial is returned instead of being dropped: the relay still has to open its own
     * stream for the SSH tunnel, and handing it the connection this wait already proved removes a
     * second trip to the host. Sampling keeps a device log able to attribute a slow recovery to one
     * signal, because the node report and the reachable target used to be two undifferentiated parts
     * of a single total.
     */
    private suspend fun awaitTransportReady(
        current: ZeroTierNode,
        state: ZeroTierReadiness,
        networkId: Long,
        target: Pair<List<String>, Int>?,
    ): Pair<String, DialedTarget?> {
        val startedAt = System.nanoTime()
        // A libzt connect blocks for up to 30 seconds, so a dial runs on the connector executor and
        // reports back through the callback. The wait never depends on that thread finishing.
        val permits = StaggeredDialPermits(SECOND_DIAL_AFTER_MILLIS)
        val dialed = SingleClaim<DialedTarget> { (socket, _) -> runCatching { socket.close() } }
        var completedWait = false
        try {
            val signal = awaitZeroTierTransportReady(
                awaitNodeOnline = {
                    state.awaitNodeOnline(ONLINE_TIMEOUT_SECONDS, TimeUnit.SECONDS) { current.isOnline() }
                },
                scheduleDial = { onConnected ->
                    val slot = if (target != null) permits.tryAcquire() else null
                    if (slot != null && target != null) {
                        val (addresses, port) = target
                        Log.d(TAG, "remote-dial started slot=$slot elapsedMs=${elapsedMsSince(startedAt)}")
                        runCatching {
                            executor.execute {
                                try {
                                    val connection = dialTarget(addresses, port, startedAt)
                                    if (connection != null) {
                                        Log.d(TAG, "remote-dial winner slot=$slot elapsedMs=${elapsedMsSince(startedAt)}")
                                        dialed.publish(connection)
                                        onConnected()
                                    }
                                } finally {
                                    permits.release()
                                }
                            }
                        }.onFailure { permits.release() }
                    }
                },
                dialAttempts = DIAL_ATTEMPTS,
                timeoutMillis = READY_TIMEOUT_MILLIS,
                retryIntervalMillis = DIAL_RETRY_INTERVAL_MILLIS,
                onSample = { note ->
                    val inFlight = permits.inFlight
                    Log.d(
                        TAG,
                        "sample $note elapsedMs=${elapsedMsSince(startedAt)} online=${isServiceOnline()}" +
                            " transportReady=${transportReady(networkId)} address=${hasAddress(networkId)}" +
                            " dialInFlight=${inFlight > 0} concurrent=$inFlight",
                    )
                },
            )
            completedWait = true
            return signal to dialed.claim()
        } finally {
            // A background transition cancels the wait but cannot interrupt libzt's blocking
            // connect. Claim now so any socket that arrives late is closed instead of leaked.
            if (!completedWait) dialed.claim()?.first?.let { runCatching { it.close() } }
        }
    }

    /** Dial the target over ZeroTier and keep the socket that proves the mesh carried the connection. */
    private fun dialTarget(
        addresses: List<String>,
        remotePort: Int,
        startedAt: Long,
    ): DialedTarget? {
        for (address in addresses) {
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull()
                ?: continue
            val connected = runCatching { socket.connect(address, remotePort) }.isSuccess
            if (!connected) {
                runCatching { socket.close() }
                Log.d(
                    TAG,
                    "remote-dial result=failed target=$address:$remotePort" +
                        " elapsedMs=${elapsedMsSince(startedAt)} online=${isServiceOnline()}",
                )
                continue
            }
            Log.d(
                TAG,
                "remote-dial result=connected target=$address:$remotePort" +
                    " elapsedMs=${elapsedMsSince(startedAt)} online=${isServiceOnline()}",
            )
            return DialedTarget(socket, address)
        }
        return null
    }

    private fun resolveTarget(config: HostConfig): Pair<List<String>, Int> {
        val addresses = InetAddress.getAllByName(config.host).mapNotNull { it.hostAddress }.distinct()
        require(addresses.isNotEmpty()) { "ZeroTier server name did not resolve" }
        return addresses to (if (config.sshEnabled) config.sshPort else config.port)
    }

    private fun isServiceOnline(): Boolean = runCatching {
        ZeroTierNative.zts_node_is_online() == 1
    }.getOrDefault(false)

    private fun hasAddress(networkId: Long): Boolean =
        runCatching {
            ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET) == 1 ||
                ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET6) == 1
        }.getOrDefault(false)

    /** libzt reports network transport readiness from its assigned-address count, not from a probe. */
    private fun transportReady(networkId: Long): Boolean = runCatching {
        ZeroTierNative.zts_net_transport_is_ready(networkId) == 1
    }.getOrDefault(false)

    private fun elapsedMsSince(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private suspend fun awaitAddress(current: ZeroTierNode, networkId: Long, state: ZeroTierReadiness) {
        if (state.awaitNetworkAddress(networkId, 15, TimeUnit.SECONDS) { hasAddress(networkId) }) return
        val nodeId = java.lang.Long.toUnsignedString(current.id, 16).padStart(10, '0')
        throw MeshAuthorizationPending(
            "Authorize ZeroTier node $nodeId in the network controller, then tap Connect again.",
        )
    }

    private fun relayFor(config: HostConfig, timing: ZeroTierTiming, dialed: DialedTarget?): MeshRelay {
        val resolveStart = timing.start("relay-resolve")
        val (addresses, remotePort) = resolveTarget(config)
        Log.d(TAG, "Resolved ZeroTier relay target ${config.host} -> ${addresses.joinToString()}")
        timing.phase("relay-resolve", resolveStart, "ok", detail = "target=${addresses.joinToString()}:$remotePort")
        // The relay only forwards to this target, so a reused relay can take the connection too.
        relay?.takeIf { it.canReuse(addresses, remotePort) }?.let {
            it.adopt(dialed)
            Log.d(TAG, "Reusing ZeroTier relay at ${it.localPort} to ${addresses.joinToString()}:$remotePort")
            return it.relay
        }
        relay?.close()
        Log.d(TAG, "Opening ZeroTier relay to ${addresses.joinToString()}:$remotePort")
        val bindStart = timing.start("relay-bind")
        val nextRelay = ZeroTierRelay(addresses, remotePort, executor, dialed).also { it.start() }
        timing.phase("relay-bind", bindStart, "ok", detail = "port=${nextRelay.localPort}")
        relay = nextRelay
        return nextRelay.relay
    }

    private fun checkResult(code: Int, operation: String) {
        if (code < 0) throw IOException("Unable to $operation (libzt error $code)")
    }

    private companion object {
        const val TAG = "ZeroTierConnector"
        const val ONLINE_TIMEOUT_SECONDS = 30L
        const val READY_TIMEOUT_MILLIS = 30_000L
        const val DIAL_ATTEMPTS = 6
        const val DIAL_RETRY_INTERVAL_MILLIS = 300L
        const val SECOND_DIAL_AFTER_MILLIS = 1_200L
    }
}

/** Per-attempt ZeroTier phase timings, so a device log line attributes the wait to one phase. */
internal class ZeroTierTiming(
    private val id: String = java.util.UUID.randomUUID().toString().take(8),
) {
    private val startedAt = System.nanoTime()

    fun start(name: String): Long {
        val now = System.nanoTime()
        Log.d(
            TAG,
            "ztAttempt=$id phase=$name start totalMs=${TimeUnit.NANOSECONDS.toMillis(now - startedAt)}",
        )
        return now
    }

    fun phase(name: String, phaseStartedAt: Long, result: String, detail: String = "") {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedAt)
        val total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val suffix = if (detail.isBlank()) "" else " detail=$detail"
        Log.d(TAG, "ztAttempt=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result$suffix")
    }

    private companion object {
        const val TAG = "ZeroTierTiming"
    }
}

/** Called under the connector lock; invalidates suspended operations before they can publish. */
internal class ZeroTierGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun checkCurrent(token: Long) {
        check(value == token) { "ZeroTier connection was superseded" }
    }
}

/** Native callbacks are hints, never proof of current connectivity or an assigned address. */
internal class ZeroTierReadiness {
    private var online = CompletableDeferred<Unit>()
    private val networks = mutableMapOf<Long, CompletableDeferred<Unit>>()

    @Synchronized fun nodeOnline() { online.complete(Unit) }
    @Synchronized fun nodeOffline() {
        online.complete(Unit) // Wake waiters bound to the retired event before replacing it.
        online = CompletableDeferred()
    }
    @Synchronized fun networkReady(id: Long) {
        val event = networks[id]?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>()
        networks[id] = event
        event.complete(Unit)
    }
    @Synchronized fun networkUnavailable(id: Long) {
        networks[id]?.complete(Unit)
        networks[id] = CompletableDeferred()
    }
    @Synchronized fun networkError(id: Long, error: Throwable) {
        val event = networks[id]?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>()
        networks[id] = event
        event.completeExceptionally(error)
    }

    private fun onlineEvent(): CompletableDeferred<Unit> = synchronized(this) { online }
    private fun networkEvent(id: Long): CompletableDeferred<Unit> = synchronized(this) {
        networks.getOrPut(id) { CompletableDeferred() }
    }

    private fun consumeOnlineEvent(event: CompletableDeferred<Unit>) = synchronized(this) {
        if (online === event) online = CompletableDeferred()
    }
    private fun consumeNetworkEvent(id: Long, event: CompletableDeferred<Unit>) = synchronized(this) {
        if (networks[id] === event) networks[id] = CompletableDeferred()
    }

    suspend fun awaitNodeOnline(timeout: Long, unit: TimeUnit, isOnline: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            coroutineContext.ensureActive()
            if (isOnline()) return true
            val event = onlineEvent()
            if (isOnline()) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            if (withTimeoutOrNull(remaining.coerceAtLeast(1) / 1_000_000 + 1) { event.await(); true } != true) break
            consumeOnlineEvent(event)
        }
        coroutineContext.ensureActive()
        return isOnline()
    }

    suspend fun awaitNetworkAddress(id: Long, timeout: Long, unit: TimeUnit, hasAddress: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            coroutineContext.ensureActive()
            if (hasAddress()) return true
            val event = networkEvent(id)
            if (hasAddress()) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                if (withTimeoutOrNull(remaining.coerceAtLeast(1) / 1_000_000 + 1) { event.await(); true } != true) break
                consumeNetworkEvent(id, event)
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                if (error is CancellationException) throw error
                if (hasAddress()) return true
                // Consume this failed readiness epoch after its waiter observes it. A later native
                // READY callback is not guaranteed to be preceded by NETWORK_DOWN, so leaving the
                // exceptional deferred installed would replay stale authorization failure forever.
                consumeNetworkEvent(id, event)
                throw error
            }
        }
        coroutineContext.ensureActive()
        return hasAddress()
    }
}

/** A ZeroTier TCP connection that already reached the relay's target, with the address it used. */
private typealias DialedTarget = Pair<ZeroTierSocket, String>

private class ZeroTierRelay(
    private val addresses: List<String>,
    private val remotePort: Int,
    private val executor: ExecutorService,
    dialed: DialedTarget? = null,
) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = ConcurrentHashMap.newKeySet<ZeroTierForwardWorker>()
    private val pending = SingleClaim<DialedTarget> { (socket, _) -> runCatching { socket.close() } }
        .apply { dialed?.let(::publish) }
    private val nextWorkerId = AtomicLong()
    @Volatile private var running = false
    val localPort: Int get() = server.localPort
    val relay: MeshRelay get() = MeshRelay("127.0.0.1", localPort)

    fun canReuse(expectedAddresses: List<String>, expectedPort: Int): Boolean =
        running && addresses == expectedAddresses && remotePort == expectedPort

    /** Offer a connection that already reached this relay's target to the next forwarded stream. */
    fun adopt(dialed: DialedTarget?) {
        dialed?.let(pending::publish)
    }

    fun start() {
        running = true
        try {
            executor.execute {
                try {
                    while (running) {
                        val local = try { server.accept() } catch (_: IOException) { break }
                        val acceptedAt = System.nanoTime()
                        val clientPort = runCatching { local.port }.getOrDefault(-1)
                        val relayPort = runCatching { local.localPort }.getOrDefault(-1)
                        Log.d(TAG, "relay accepted clientPort=$clientPort relayPort=$relayPort peer=${runCatching { local.inetAddress?.hostAddress }.getOrNull() ?: "unknown"} targetPort=$remotePort")
                        try {
                            executor.execute { forward(local, acceptedAt, clientPort) }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            runCatching { local.close() }
                            break
                        }
                    }
                } catch (error: Throwable) {
                    // This is an executor thread, not a coroutine: contain unexpected socket/JNI errors
                    // so a relay lifecycle race cannot terminate the application process.
                    Log.w(TAG, "ZeroTier relay accept loop failed", error)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            running = false
            runCatching { server.close() }
        }
    }

    private fun forward(local: Socket, acceptedAt: Long, clientPort: Int) {
        val workerId = nextWorkerId.incrementAndGet()
        val startedAt = acceptedAt
        var remote: ZeroTierSocket? = null
        var handedToWorker = false
        try {
            val connected = connect() ?: return
            remote = connected.first
            val connectedRemote = connected.first
            // Enable TCP keepalive as an additional safety net for long-lived forwarded sockets.
            val keepAliveConfigured = runCatching {
                connectedRemote.setKeepAliveEnabled(true)
                connectedRemote.setTcpKeepIdle(TCP_KEEP_IDLE_SECONDS)
                connectedRemote.getKeepAlive()
            }.getOrDefault(false)
            Log.d(TAG, "ZeroTier TCP keepalive configured=$keepAliveConfigured idleSeconds=$TCP_KEEP_IDLE_SECONDS")
            val targetAddress = connected.second
            val localPort = runCatching { local.port }.getOrDefault(-1)
            Log.d(TAG, "relay worker=$workerId connected clientPort=$clientPort localPort=$localPort target=$targetAddress:$remotePort connectMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}")
            // Patched libzt reports the timeout as SocketTimeoutException and a peer close as EOF.
            // The timeout bounds the read's wake-up after relay shutdown without racing native close.
            connectedRemote.setSoTimeout(NATIVE_READ_POLL_MILLIS)
            val worker = ZeroTierForwardWorker(
                local = local,
                remoteInput = connectedRemote.inputStream,
                remoteOutput = connectedRemote.outputStream,
                closeRemote = { connectedRemote.close() },
                shutdownRemoteOutput = { connectedRemote.shutdownOutput() },
                executor = executor,
                onRequestClassified = { requestClass ->
                    Log.d(TAG, "relay worker=$workerId requestClass=$requestClass clientPort=$clientPort")
                },
                onResponseStatus = { status ->
                    Log.d(TAG, "relay worker=$workerId responseStatus=$status clientPort=$clientPort")
                },
                onRpcEndpoint = { endpoint ->
                    Log.d(TAG, "relay worker=$workerId rpcEndpoint=$endpoint clientPort=$clientPort")
                },
                onFinished = {
                    workers.remove(it)
                    val stats = it.diagnostics
                    Log.d(TAG, "relay worker=$workerId finished clientPort=$clientPort localPort=$localPort target=$targetAddress:$remotePort elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)} requestClass=${stats.requestClass} endpoint=${stats.rpcEndpoint} responseStatus=${stats.responseStatus.takeIf { status -> status >= 0 } ?: "none"} localToNative=read:${stats.localToRemoteReadBytes},written:${stats.localToRemoteBytes},end:${stats.localToRemoteEnd} nativeToLocal=read:${stats.remoteToLocalReadBytes},written:${stats.remoteToLocalBytes},end:${stats.remoteToLocalEnd}")
                },
            )
            workers.add(worker)
            handedToWorker = true
            if (running) worker.start() else worker.close()
        } catch (error: Throwable) {
            // A libzt socket can fail while its relay is being replaced. This executor owns no
            // coroutine exception handler, so never let a native/JNI failure escape and kill the app.
            Log.w(TAG, "ZeroTier forwarding worker setup failed", error)
        } finally {
            if (!handedToWorker) {
                runCatching { local.close() }
                runCatching { remote?.close() }
            }
        }
    }

    private fun connect(): Pair<ZeroTierSocket, String>? {
        // A readiness dial already proved this path, so reuse it instead of paying for a second
        // connect to the host. Any later stream, and any adoption that is never used, falls back.
        pending.claim()?.let { (socket, address) ->
            Log.d(TAG, "ZeroTier relay adopted a proven connection to $address:$remotePort")
            return socket to address
        }
        addresses.forEach { address ->
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull() ?: return@forEach
            try {
                socket.connect(address, remotePort)
                Log.d(TAG, "ZeroTier relay connected to $address:$remotePort")
                return socket to address
            } catch (error: IOException) {
                Log.w(TAG, "ZeroTier relay failed to connect to $address:$remotePort", error)
                runCatching { socket.close() }
            }
        }
        return null
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        // An unclaimed connection to the host would otherwise stay open with no reader on either side.
        pending.claim()?.let { (socket, _) -> runCatching { socket.close() } }
        // Do not close libzt here: a worker may still be inside a native read/write, and doing so
        // concurrently produced a Pixel 3 SIGSEGV. Closing each loopback endpoint makes its peer
        // copy return. The worker then waits for both directions and is the sole owner that closes
        // the native socket after no native I/O remains.
        workers.toList().forEach(ZeroTierForwardWorker::close)
    }

    private companion object {
        const val TAG = "ZeroTierRelay"
        const val NATIVE_READ_POLL_MILLIS = 250
        const val TCP_KEEP_IDLE_SECONDS = 30
    }
}

internal class ZeroTierForwardWorker(
    private val local: Socket,
    private val remoteInput: InputStream,
    private val remoteOutput: OutputStream,
    private val closeRemote: () -> Unit,
    private val executor: ExecutorService,
    private val onFinished: (ZeroTierForwardWorker) -> Unit,
    private val shutdownRemoteOutput: () -> Unit = {},
    private val onRequestClassified: (String) -> Unit = {},
    private val onResponseStatus: (Int) -> Unit = {},
    private val onRpcEndpoint: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finished = CountDownLatch(2)
    private val remoteClosed = AtomicBoolean(false)
    private val localToRemoteBytes = AtomicLong()
    private val localToRemoteReadBytes = AtomicLong()
    private val remoteToLocalBytes = AtomicLong()
    private val remoteToLocalReadBytes = AtomicLong()
    private val localToRemoteEnd = AtomicReference("active")
    private val remoteToLocalEnd = AtomicReference("active")
    private val requestClassification = AtomicReference("unclassified")
    private val responseStatus = AtomicReference(-1)
    private val requestEndpoint = AtomicReference("unknown")

    data class Diagnostics(
        val requestClass: String,
        val rpcEndpoint: String,
        val responseStatus: Int,
        val localToRemoteBytes: Long,
        val localToRemoteReadBytes: Long,
        val remoteToLocalBytes: Long,
        val remoteToLocalReadBytes: Long,
        val localToRemoteEnd: String,
        val remoteToLocalEnd: String,
    )

    val diagnostics: Diagnostics get() = Diagnostics(
        requestClassification.get(), requestEndpoint.get(), responseStatus.get(), localToRemoteBytes.get(), localToRemoteReadBytes.get(),
        remoteToLocalBytes.get(), remoteToLocalReadBytes.get(),
        localToRemoteEnd.get(), remoteToLocalEnd.get(),
    )

    companion object {
        fun readFailureReason(error: IOException): String = "read:${error.javaClass.simpleName}:${safeSocketErrorCategory(error)}"

        fun writeFailureReason(error: Throwable): String = "write:${error.javaClass.simpleName}:${safeSocketErrorCategory(error)}"

        fun safeSocketErrorCategory(error: Throwable): String {
            val message = error.message.orEmpty()
            val nativeErrno = Regex("errno=(-?\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()?.let { kotlin.math.abs(it) }
            return when {
                message.contains("temporarily unavailable", ignoreCase = true) || nativeErrno == 11 -> "EAGAIN"
                nativeErrno == 4 -> "EINTR"
                message.contains("service unavailable", ignoreCase = true) || nativeErrno == 200 -> "SERVICE"
                message.contains("connection reset", ignoreCase = true) || nativeErrno == 104 -> "RESET"
                message.contains("broken pipe", ignoreCase = true) || nativeErrno == 32 -> "BROKEN_PIPE"
                message.contains("timed out", ignoreCase = true) || nativeErrno == 110 -> "TIMEOUT"
                else -> "OTHER"
            }
        }

        private val SAFE_ROUTES = mapOf(
            "/api/session/list" to "SESSION_LIST",
            "/api/session/projections" to "SESSION_PROJECTIONS",
            "/api/session/modelCatalog" to "SESSION_MODEL_CATALOG",
            "/api/session/page" to "SESSION_PAGE",
            "/api/session/follow" to "SESSION_FOLLOW",
            "/api/session/search" to "SESSION_SEARCH",
            "/api/host/describe" to "HOST_DESCRIBE",
            "/api/health" to "HEALTH",
        )

        /** Classify only a bounded request-line prefix; headers, query strings and bodies are never logged. */
        private fun ByteArray.indexOfCrLf(): Int {
            for (index in 0 until size - 1) {
                if (this[index] == 13.toByte() && this[index + 1] == 10.toByte()) return index
            }
            return -1
        }

        fun requestClass(classification: String): String = when {
            classification == "GET /api/remote.mux" -> "GET_REMOTE_MUX"
            classification.startsWith("POST /api/session/") -> "POST_SESSION_API"
            classification.startsWith("POST /api/host/") -> "POST_HOST_API"
            classification == "POST /api/health" -> "POST_HEALTH"
            classification.startsWith("POST /api/") -> "POST_API_OTHER"
            classification == "tls-record" -> "TLS"
            classification.startsWith("GET ") -> "GET_OTHER"
            classification.startsWith("POST ") -> "POST_OTHER"
            classification == "empty" -> "EMPTY"
            classification.startsWith("ascii-") -> "ASCII_OTHER"
            else -> "NON_HTTP"
        }

        fun classifyHttpStatus(bytes: ByteArray): Int? {
            val lineEnd = bytes.indexOfCrLf()
            if (lineEnd < 0) return null
            val line = bytes.copyOfRange(0, lineEnd).toString(Charsets.ISO_8859_1)
            val parts = line.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
            return parts[1].toIntOrNull()?.takeIf { it in 100..599 }
        }

        fun classifyHttpPreface(bytes: ByteArray, complete: Boolean): String? {
            if (bytes.isEmpty()) return if (complete) "empty" else null
            val first = bytes[0].toInt() and 0xff
            if (first == 0x16) return "tls-record"
            if (first == 0x17) return "tls-record"
            if (first !in 0x20..0x7e) return "binary-other"
            val text = bytes.toString(Charsets.ISO_8859_1)
            val lineEnd = bytes.indexOfCrLf()
            if (lineEnd < 0 && !complete) return null
            val requestLine = if (lineEnd >= 0) text.substring(0, lineEnd) else text
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size == 3 && parts[0].length in 1..12 && parts[0].all(Char::isLetter) &&
                parts[2].startsWith("HTTP/")) {
                val method = parts[0].uppercase()
                val safeRoute = parts[1].substringBefore('?').let { path ->
                    path.takeIf { it == "/api/remote.mux" || (method == "POST" && SAFE_ROUTES.containsKey(path)) }
                }
                return when {
                    method == "GET" && safeRoute == "/api/remote.mux" -> "GET /api/remote.mux"
                    method == "POST" && safeRoute != null -> "POST ${safeRoute}"
                    else -> "$method other-path"
                }
            }
            return if (first in 0x41..0x5a || first in 0x61..0x7a) "ascii-letter" else "ascii-other"
        }
    }

    fun start() {
        synchronized(stateLock) {
            if (started.get()) return
            // close() and start() must choose one terminal transition under the same lock. The old
            // CAS-before-lock could let close observe "started" while start then returned early,
            // stranding the native socket with neither copy task owning its close.
            if (closing.get()) {
                completeUnstartedLocked()
                return
            }
            val localInput: InputStream
            val localOutput: OutputStream
            try {
                localInput = local.getInputStream()
                localOutput = local.getOutputStream()
            } catch (_: IOException) {
                completeUnstartedLocked()
                return
            }
            started.set(true)
            var submitted = 0
            try {
                executor.execute { copy(localInput, remoteOutput, pollEndOfStream = false, localToRemoteBytes, localToRemoteReadBytes, localToRemoteEnd) }
                submitted = 1
                executor.execute { copy(remoteInput, localOutput, pollEndOfStream = true, remoteToLocalBytes, remoteToLocalReadBytes, remoteToLocalEnd) }
                submitted = 2
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                closing.set(true)
                runCatching { local.close() }
                repeat(2 - submitted) { finished.countDown() }
                if (finished.count == 0L) finish()
            }
        }
    }

    private fun completeUnstartedLocked() {
        if (started.get()) return
        closing.set(true)
        finished.countDown()
        finished.countDown()
        finish()
    }

    private fun copy(input: InputStream, output: OutputStream, pollEndOfStream: Boolean, bytes: AtomicLong, readBytes: AtomicLong, end: AtomicReference<String>) {
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            // The local socket supplies HTTP request bytes; the native socket supplies server responses.
            var preface = if (!pollEndOfStream) ByteArrayOutputStream(128) else null
            var responseLine = if (pollEndOfStream) ByteArrayOutputStream(64) else null
            var responseReported = false
            var prefaceReported = false
            while (!closing.get()) {
                val count = try {
                    input.read(buffer)
                } catch (_: java.net.SocketTimeoutException) {
                    if (pollEndOfStream) continue
                    end.set("local-timeout")
                    break
                } catch (error: IOException) {
                    end.set(readFailureReason(error))
                    break
                }
                if (count > 0) {
                    if (!responseReported && responseLine != null) {
                        val capture = minOf(count, 128 - responseLine.size())
                        responseLine.write(buffer, 0, capture)
                        val status = classifyHttpStatus(responseLine.toByteArray())
                        if (status != null) {
                            responseStatus.set(status)
                            onResponseStatus(status)
                            responseReported = true
                            responseLine = null
                        } else if (responseLine.size() >= 128) {
                            responseReported = true
                            responseLine = null
                        }
                    }
                    if (!prefaceReported && preface != null) {
                        val capture = minOf(count, 256 - preface.size())
                        preface.write(buffer, 0, capture)
                        val bytes = preface.toByteArray()
                        val classified = classifyHttpPreface(bytes, complete = preface.size() >= 256 || bytes.indexOfCrLf() >= 0)
                        if (classified != null) {
                            val requestClass = requestClass(classified)
                            requestClassification.set(requestClass)
                            onRequestClassified(requestClass)
                            if (classified.startsWith("POST /api/")) {
                                val route = classified.removePrefix("POST ")
                                val endpoint = SAFE_ROUTES[route] ?: "OTHER"
                                requestEndpoint.set(endpoint)
                                onRpcEndpoint(endpoint)
                            }
                            prefaceReported = true
                            preface = null
                        }
                    }
                    readBytes.addAndGet(count.toLong())
                    try {
                        output.write(buffer, 0, count)
                        bytes.addAndGet(count.toLong())
                    } catch (error: Throwable) {
                        end.set(writeFailureReason(error))
                        break
                    }
                } else if (count < 0) {
                    end.set(if (pollEndOfStream) "native-eof" else "local-eof")
                    if (!pollEndOfStream) {
                        // Preserve TCP half-close: deliver FIN to the host but keep its response
                        // readable. Closing the whole socket here can truncate POST replies when a
                        // loopback client shuts down its output after sending the request body.
                        runCatching(shutdownRemoteOutput)
                    }
                    break
                }
                // Timeouts are exceptions (not EOF); only a positive read forwards data.
            }
        } finally {
            end.compareAndSet("active", "relay-close")
            finished.countDown()
            // Closing the Java loopback socket is safe from either direction and wakes its peer.
            runCatching { local.close() }
            if (finished.count == 0L) finish()
        }
    }

    override fun close() {
        synchronized(stateLock) {
            closing.set(true)
            if (!started.get()) completeUnstartedLocked()
        }
        runCatching { local.close() }
    }

    private fun finish() {
        if (!remoteClosed.compareAndSet(false, true)) return
        // Both copy tasks have returned, so no thread is inside a libzt read/write anymore.
        runCatching(closeRemote)
        onFinished(this)
    }
}
