package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
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
        synchronized(lock) {
            val networkId = java.lang.Long.parseUnsignedLong(networkIdText, 16)
            val pendingNode = node
            if (shouldReuseZeroTierNode(pendingNode != null, nodeNetworkId == networkIdText)) {
                Log.d(TAG, "Reusing ZeroTier node for network $networkIdText (online=${isServiceOnline()})")
                // libzt owns one process-global NodeService. During foreground churn it may briefly
                // report offline while the same node's service thread is still starting. Calling
                // initFromStorage again in that window returns ZTS_ERR_SERVICE (-2) and can never be
                // repaired by another tap. Always wait on the retained node instead.
                val pendingReadiness = readiness ?: ZeroTierReadiness().also { readiness = it }
                awaitOnline(pendingNode!!, pendingReadiness)
                if (hasAddress(networkId)) return@synchronized relayFor(config)
                awaitAddress(pendingNode, networkId, pendingReadiness)
                return@synchronized relayFor(config)
            }
            stopLocked()
            // A ZeroTier identity belongs to the network, not a particular DSH host. Sharing this
            // storage lets several saved hosts on one network appear as one controller member.
            val storage = java.io.File(context.noBackupFilesDir, "zerotier/networks/$networkIdText").apply { mkdirs() }
            val roots = java.io.File(storage, "roots")
            val planetId = config.zeroTierPlanetId
            val planet = planetId?.let(planets::resolve)
            if (planetId != null && planet == null) {
                throw IOException("Configured ZeroTier planet is missing")
            }
            if (planet == null) roots.delete() else planet.copyTo(roots, overwrite = true)
            val nextNode = ZeroTierNode()
            val nextReadiness = ZeroTierReadiness()
            checkResult(nextNode.initFromStorage(storage.absolutePath), "initialize ZeroTier")
            checkResult(nextNode.initAllowRootsCache(planet == null), "configure ZeroTier")
            checkResult(nextNode.initSetEventHandler(object : ZeroTierEventListener {
                override fun onZeroTierEvent(id: Long, eventCode: Int) {
                    signal(nextReadiness, id, eventCode)
                }
            }), "register ZeroTier event handler")
            readiness = nextReadiness
            checkResult(nextNode.start(), "start ZeroTier")
            node = nextNode
            nodeNetworkId = networkIdText
            awaitOnline(nextNode, nextReadiness)
            checkResult(nextNode.join(networkId), "join ZeroTier network")
            awaitAddress(nextNode, networkId, nextReadiness)
            relayFor(config)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
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
        synchronized(lock) {
            val networkIdText = config.zeroTierNetworkId?.lowercase()
            require(networkIdText != null && networkIdText == nodeNetworkId) {
                "ZeroTier relay renewal requires the retained network"
            }
            val current = checkNotNull(node) { "ZeroTier node is not started" }
            val state = checkNotNull(readiness) { "ZeroTier readiness is unavailable" }
            awaitOnline(current, state)
            awaitAddress(current, java.lang.Long.parseUnsignedLong(networkIdText, 16), state)
            relay?.close()
            relay = null
            relayFor(config)
        }
    }

    private fun awaitOnline(current: ZeroTierNode, state: ZeroTierReadiness) {
        check(state.awaitNodeOnline(30, TimeUnit.SECONDS) { current.isOnline() }) {
            "ZeroTier node did not come online; check internet access"
        }
    }

    private fun isServiceOnline(): Boolean = runCatching {
        ZeroTierNative.zts_node_is_online() == 1
    }.getOrDefault(false)

    private fun hasAddress(networkId: Long): Boolean =
        runCatching {
            ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET) == 1 ||
                ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET6) == 1
        }.getOrDefault(false)

    private fun awaitAddress(current: ZeroTierNode, networkId: Long, state: ZeroTierReadiness) {
        if (state.awaitNetworkAddress(networkId, 15, TimeUnit.SECONDS) { hasAddress(networkId) }) return
        val nodeId = java.lang.Long.toUnsignedString(current.id, 16).padStart(10, '0')
        throw MeshAuthorizationPending(
            "Authorize ZeroTier node $nodeId in the network controller, then tap Connect again.",
        )
    }

    private fun relayFor(config: HostConfig): MeshRelay {
        val addresses = InetAddress.getAllByName(config.host).mapNotNull { it.hostAddress }.distinct()
        Log.d(TAG, "Resolved ZeroTier relay target ${config.host} -> ${addresses.joinToString()}")
        require(addresses.isNotEmpty()) { "ZeroTier server name did not resolve" }
        val remotePort = if (config.sshEnabled) config.sshPort else config.port
        relay?.takeIf { it.canReuse(addresses, remotePort) }?.let {
            Log.d(TAG, "Reusing ZeroTier relay at ${it.localPort} to ${addresses.joinToString()}:$remotePort")
            return it.relay
        }
        relay?.close()
        Log.d(TAG, "Opening ZeroTier relay to ${addresses.joinToString()}:$remotePort")
        val nextRelay = ZeroTierRelay(addresses, remotePort, executor).also { it.start() }
        relay = nextRelay
        return nextRelay.relay
    }

    private fun checkResult(code: Int, operation: String) {
        if (code < 0) throw IOException("Unable to $operation (libzt error $code)")
    }

    private companion object {
        const val TAG = "ZeroTierConnector"
    }
}

/** Native callbacks are hints, never proof of current connectivity or an assigned address. */
internal class ZeroTierReadiness {
    private var online = CompletableFuture<Unit>()
    private val networks = mutableMapOf<Long, CompletableFuture<Unit>>()

    @Synchronized fun nodeOnline() { online.complete(Unit) }
    @Synchronized fun nodeOffline() {
        online.complete(Unit) // Wake waiters bound to the retired event before replacing it.
        online = CompletableFuture()
    }
    @Synchronized fun networkReady(id: Long) {
        val event = networks[id]?.takeUnless { it.isCompletedExceptionally } ?: CompletableFuture<Unit>()
        networks[id] = event
        event.complete(Unit)
    }
    @Synchronized fun networkUnavailable(id: Long) {
        networks[id]?.complete(Unit) // Wake existing waiters; the replacement event waits for a new address.
        networks[id] = CompletableFuture()
    }
    @Synchronized fun networkError(id: Long, error: Throwable) {
        val event = networks[id]?.takeUnless { it.isDone } ?: CompletableFuture<Unit>()
        networks[id] = event
        event.completeExceptionally(error)
    }

    private fun onlineEvent(): CompletableFuture<Unit> = synchronized(this) { online }
    private fun networkEvent(id: Long): CompletableFuture<Unit> = synchronized(this) {
        networks.getOrPut(id) { CompletableFuture() }
    }

    private fun consumeOnlineEvent(event: CompletableFuture<Unit>) = synchronized(this) {
        if (online === event) online = CompletableFuture()
    }
    private fun consumeNetworkEvent(id: Long, event: CompletableFuture<Unit>) = synchronized(this) {
        if (networks[id] === event) networks[id] = CompletableFuture()
    }

    fun awaitNodeOnline(timeout: Long, unit: TimeUnit, isOnline: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!isOnline()) {
            val event = onlineEvent()
            if (isOnline()) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                event.get(remaining, TimeUnit.NANOSECONDS)
                consumeOnlineEvent(event)
            } catch (_: java.util.concurrent.TimeoutException) {
                break
            }
        }
        return isOnline()
    }

    fun awaitNetworkAddress(id: Long, timeout: Long, unit: TimeUnit, hasAddress: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!hasAddress()) {
            val event = networkEvent(id)
            if (hasAddress()) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                event.get(remaining, TimeUnit.NANOSECONDS)
                consumeNetworkEvent(id, event)
            } catch (_: java.util.concurrent.TimeoutException) {
                break
            } catch (error: java.util.concurrent.ExecutionException) {
                if (hasAddress()) return true
                throw (error.cause ?: error)
            }
        }
        return hasAddress()
    }
}

private class ZeroTierRelay(
    private val addresses: List<String>,
    private val remotePort: Int,
    private val executor: ExecutorService,
) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = ConcurrentHashMap.newKeySet<ZeroTierForwardWorker>()
    @Volatile private var running = false
    val localPort: Int get() = server.localPort
    val relay: MeshRelay get() = MeshRelay("127.0.0.1", localPort)

    fun canReuse(expectedAddresses: List<String>, expectedPort: Int): Boolean =
        running && addresses == expectedAddresses && remotePort == expectedPort

    fun start() {
        running = true
        try {
            executor.execute {
                try {
                    while (running) {
                        val local = try { server.accept() } catch (_: IOException) { break }
                        try {
                            executor.execute { forward(local) }
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

    private fun forward(local: Socket) {
        var remote: ZeroTierSocket? = null
        var handedToWorker = false
        try {
            remote = connect()
            val connectedRemote = remote ?: return
            // libzt maps SO_RCVTIMEO expiry to InputStream.read() == -1. The worker treats that as a
            // poll while active and as the bounded wake-up signal after close(), avoiding a concurrent
            // native close from the relay lifecycle thread.
            runCatching { connectedRemote.setSoTimeout(NATIVE_READ_POLL_MILLIS) }
            val worker = ZeroTierForwardWorker(
                local = local,
                remoteInput = connectedRemote.inputStream,
                remoteOutput = connectedRemote.outputStream,
                closeRemote = { connectedRemote.close() },
                executor = executor,
                onFinished = { workers.remove(it) },
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

    private fun connect(): ZeroTierSocket? {
        addresses.forEach { address ->
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull() ?: return@forEach
            try {
                socket.connect(address, remotePort)
                Log.d(TAG, "ZeroTier relay connected to $address:$remotePort")
                return socket
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
        // Do not close libzt here: a worker may still be inside a native read/write, and doing so
        // concurrently produced a Pixel 3 SIGSEGV. Closing each loopback endpoint makes its peer
        // copy return. The worker then waits for both directions and is the sole owner that closes
        // the native socket after no native I/O remains.
        workers.toList().forEach(ZeroTierForwardWorker::close)
    }

    private companion object {
        const val TAG = "ZeroTierRelay"
        const val NATIVE_READ_POLL_MILLIS = 250
    }
}

internal class ZeroTierForwardWorker(
    private val local: Socket,
    private val remoteInput: InputStream,
    private val remoteOutput: OutputStream,
    private val closeRemote: () -> Unit,
    private val executor: ExecutorService,
    private val onFinished: (ZeroTierForwardWorker) -> Unit,
) : Closeable {
    private val stateLock = Any()
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finished = CountDownLatch(2)
    private val remoteClosed = AtomicBoolean(false)

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
                executor.execute { copy(localInput, remoteOutput, pollEndOfStream = false) }
                submitted = 1
                executor.execute { copy(remoteInput, localOutput, pollEndOfStream = true) }
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

    private fun copy(input: InputStream, output: OutputStream, pollEndOfStream: Boolean) {
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!closing.get()) {
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (count > 0) {
                    if (runCatching { output.write(buffer, 0, count) }.isFailure) break
                } else if (!pollEndOfStream) {
                    break
                }
                // For libzt, -1 can mean SO_RCVTIMEO rather than peer EOF. Poll again while active;
                // after close() it becomes the bounded exit path without closing native I/O here.
            }
        } finally {
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
