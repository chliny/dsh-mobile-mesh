package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
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
import java.security.MessageDigest
import java.util.UUID
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val nativeDialPermits = NativeDialPermits(BuildConfig.ZERO_TIER_MAX_CONCURRENT_DIALS)
    private val nextDiagnosticWaitId = AtomicLong()
    private val peerDiagnosticsInFlight = AtomicBoolean(false)
    private val lastPeerDiagnosticsNanos = AtomicLong(0L)
    private val lock = Any()
    private var node: ZeroTierNode? = null
    private var relay: ZeroTierRelay? = null
    private var nodeNetworkId: String? = null
    private var readiness: ZeroTierReadiness? = null
    private val generation = ZeroTierGeneration()
    /** Consecutive retryable mesh-not-ready results; a patient wait follows a few of them. */
    private val consecutiveNotReady = java.util.concurrent.atomic.AtomicInteger(0)

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
                Log.d(TAG, "Reusing ZeroTier node (online=${isServiceOnline()})")
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
                timing.phase("node-start", startStart, "ok", detail = "nodeIdentityPresent=${nextNode.id != 0L}")
                node = nextNode
                readiness = nextReadiness
                nodeNetworkId = networkIdText
                nextNode to nextReadiness
            }
            Triple(pair.first, pair.second, generation.next())
        }
        timing.phase("node-select", selectStart, "ok", detail = "reused=$reused")
        // Background suspension clears MeshTransportManager.active but retains the process-global
        // native node. A subsequent start is therefore recovery, not a cold bootstrap.
        val targetConnectTimeoutMs = zeroTierTargetConnectTimeoutMs(recovery = reused)
        recordMeshAttempt("start", reused, networkId, targetConnectTimeoutMs)
        // A new node cannot reach a network target before joining that network and obtaining an
        // address. Retained nodes with an address pass through immediately, preserving recovery.
        ensureNetworkAddress(current, state, networkId, token, timing, zeroTierReadinessTimeoutMs(reusedNode = reused))
        val readyStart = timing.start("await-transport-ready")
        val (readySignal, dialedTarget) = awaitTransportReady(
            current,
            state,
            networkId,
            resolveTarget(config),
            targetConnectTimeoutMs,
            zeroTierReadinessTimeoutMs(reusedNode = reused),
        )
        timing.phase(
            "await-transport-ready",
            readyStart,
            if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) "timeout" else "ok",
            detail = "signal=$readySignal online=${isServiceOnline()}",
        )
        if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) {
            // The wait ended on its own deadline, not on an authorization decision: release any
            // socket that raced in and surface a retryable transport failure.
            closeDialed(dialedTarget)
            throw meshNotReady(
                "phase=await-transport-ready reused=$reused elapsedMs=${
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readyStart)
                } targetConnectMs=$targetConnectTimeoutMs",
                context.getString(
                    if (isServiceOnline()) R.string.zero_tier_target_unreachable
                    else R.string.zero_tier_node_unavailable,
                ),
            )
        }
        val relayStart = timing.start("relay-open")
        val opened = synchronized(lock) {
            try {
                coroutineContext.ensureActive()
                generation.checkCurrent(token)
            } catch (error: Throwable) {
                closeDialed(dialedTarget)
                throw error
            }
            // relayFor owns the proven dial from here on: it either adopts it into a relay or
            // closes it on every failure path before rethrowing.
            relayFor(config, timing, dialedTarget, targetConnectTimeoutMs)
        }
        timing.phase("relay-open", relayStart, "ok", detail = "port=${opened.port}")
        markMeshReady()
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
        // against NodeService destruction (a test device FORTIFY abort in phyOnTcpClose). Retain the
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
            // Retire the old relay before waiting on the target again. Its bounded in-flight native
            // connects are now fenced and will close when their 5s recovery budget expires.
            relay?.close()
            relay = null
            Triple(
                checkNotNull(node) { "ZeroTier node is not started" },
                checkNotNull(readiness) { "ZeroTier readiness is unavailable" },
                generation.next(),
            )
        }
        timing.phase("node-select", selectStart, "ok", detail = "reused=true")
        val targetConnectTimeoutMs = zeroTierTargetConnectTimeoutMs(recovery = true)
        recordMeshAttempt("renew-relay", reused = true, networkId = networkId, targetConnectTimeoutMs = targetConnectTimeoutMs)
        // Renewal can also resume a previously pending authorization, where no address exists yet.
        ensureNetworkAddress(current, state, networkId, token, timing, zeroTierReadinessTimeoutMs(reusedNode = true))
        val readyStart = timing.start("await-transport-ready")
        val (readySignal, dialedTarget) = awaitTransportReady(
            current,
            state,
            networkId,
            resolveTarget(config),
            targetConnectTimeoutMs,
            zeroTierReadinessTimeoutMs(reusedNode = true),
        )
        timing.phase(
            "await-transport-ready",
            readyStart,
            if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) "timeout" else "ok",
            detail = "signal=$readySignal online=${isServiceOnline()}",
        )
        if (readySignal == ZERO_TIER_TIMEOUT_SIGNAL) {
            // The wait ended on its own deadline, not on an authorization decision: release any
            // socket that raced in and surface a retryable transport failure.
            closeDialed(dialedTarget)
            throw meshNotReady(
                "phase=await-transport-ready reused=true elapsedMs=${
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readyStart)
                } targetConnectMs=$targetConnectTimeoutMs",
                context.getString(
                    if (isServiceOnline()) R.string.zero_tier_target_unreachable
                    else R.string.zero_tier_node_unavailable,
                ),
            )
        }
        val relayStart = timing.start("relay-open")
        val opened = synchronized(lock) {
            try {
                coroutineContext.ensureActive()
                generation.checkCurrent(token)
            } catch (error: Throwable) {
                closeDialed(dialedTarget)
                throw error
            }
            runCatching { relay?.close() }
            relay = null
            relayFor(config, timing, dialedTarget, targetConnectTimeoutMs)
        }
        timing.phase("relay-open", relayStart, "ok", detail = "port=${opened.port}")
        markMeshReady()
        opened
    }

    /** Durable snapshot of what the retained node could do at the start of one recovery attempt. */
    private fun recordMeshAttempt(phase: String, reused: Boolean, networkId: Long, targetConnectTimeoutMs: Int) {
        ConnectionTrace.record(
            "zt-attempt",
            "phase=$phase reused=$reused consecutiveNotReady=${consecutiveNotReady.get()}" +
                " online=${isServiceOnline()} addressAssigned=${hasAddress(networkId)}" +
                " transportReady=${transportReady(networkId)}" +
                " dialReadinessMs=${zeroTierReadinessTimeoutMs(reusedNode = reused)} targetConnectMs=$targetConnectTimeoutMs",
        )
    }

    /** Journal one retryable not-ready result and escalate the next wait once they accumulate. */
    private fun noteNotReady(detail: String) {
        consecutiveNotReady.incrementAndGet()
        ConnectionTrace.record(
            "zt-not-ready",
            "$detail streak=${consecutiveNotReady.get()} online=${isServiceOnline()}",
        )
    }

    private fun meshNotReady(detail: String, message: String): MeshNotReady {
        noteNotReady(detail)
        return MeshNotReady(message)
    }

    private fun markMeshReady() {
        consecutiveNotReady.set(0)
    }

    /** Join and acquire a local address before probing the remote target. */
    private suspend fun ensureNetworkAddress(
        current: ZeroTierNode,
        state: ZeroTierReadiness,
        networkId: Long,
        token: Long,
        timing: ZeroTierTiming,
        nodeInitTimeoutMs: Long,
    ) {
        val joinStart = timing.start("join")
        // NodeService::join synchronizes with construction/destruction and returns ERR_SERVICE
        // until the node is initialized. Retry only that transient state, without requiring ONLINE:
        // cached network configuration and peer paths can work when upstream is unavailable.
        val joined = withTimeoutOrNull(nodeInitTimeoutMs) {
            var didJoin = false
            var pending = true
            while (pending) {
                val result = synchronized(lock) {
                    coroutineContext.ensureActive()
                    generation.checkCurrent(token)
                    if (hasAddress(networkId)) null else current.join(networkId)
                }
                when (result) {
                    null -> pending = false
                    ZeroTierNative.ZTS_ERR_SERVICE -> kotlinx.coroutines.delay(50)
                    else -> {
                        checkResult(result, "join ZeroTier network")
                        didJoin = true
                        pending = false
                    }
                }
            }
            didJoin
        }
        timing.phase("join", joinStart, if (joined == null) "timeout" else "ok", detail = "joined=$joined")
        if (joined == null) {
            throw meshNotReady(
                "phase=join timeoutMs=$nodeInitTimeoutMs",
                context.getString(R.string.zero_tier_node_unavailable),
            )
        }
        val addressStart = timing.start("await-address")
        awaitAddress(current, networkId, state)
        timing.phase(
            "await-address",
            addressStart,
            "ok",
            detail = "transportReady=${transportReady(networkId)}",
        )
        synchronized(lock) {
            coroutineContext.ensureActive()
            generation.checkCurrent(token)
        }
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
        targetConnectTimeoutMs: Int,
        readinessTimeoutMs: Long,
    ): Pair<String, DialedTarget?> {
        val startedAt = System.nanoTime()
        val waitElapsedRealtimeStart = android.os.SystemClock.elapsedRealtime()
        val waitUptimeStart = android.os.SystemClock.uptimeMillis()
        // Native connect blocks, so dials run on the connector executor. The registry bounds each
        // call and closes any socket that finishes after this readiness generation is retired.
        val maxConcurrentDials = BuildConfig.ZERO_TIER_MAX_CONCURRENT_DIALS
        val permits = RecoveryDialPermits(SECOND_DIAL_AFTER_MILLIS, maxConcurrentDials, nativeDialPermits)
        val waitId = nextDiagnosticWaitId.incrementAndGet()
        val gateFinished = AtomicBoolean(false)
        val scheduled = AtomicInteger()
        val rejected = AtomicInteger()
        val nativeFullObserved = AtomicInteger()
        val localBusyObserved = AtomicInteger()
        val globalFullLocalIdleObserved = AtomicInteger()
        val executorRejected = AtomicInteger()
        val peakNative = AtomicInteger(permits.nativeInFlight)
        fun observeNative(count: Int) { peakNative.updateAndGet { maxOf(it, count) } }
        recordZeroTierDiagnostic(
            "zt-dial-wait-start",
            "waitId=$waitId targetCount=${target?.first?.size ?: 0} readinessBudgetMs=$readinessTimeoutMs" +
                " connectBudgetMs=$targetConnectTimeoutMs maxConcurrent=$maxConcurrentDials" +
                " staggerMs=$SECOND_DIAL_AFTER_MILLIS nativeInFlight=${permits.nativeInFlight} scope=readiness",
        )
        Log.d(TAG, "recovery dial policy maxConcurrent=$maxConcurrentDials secondAfterMs=$SECOND_DIAL_AFTER_MILLIS")
        val pendingDials = BoundedConnectRegistry<ZeroTierSocket> { socket -> runCatching { socket.close() } }
        val dialed = SingleClaim<DialedTarget> { (socket, _) -> runCatching { socket.close() } }
        var completedWait = false
        var waitResult = "error"
        try {
            val signal = awaitZeroTierTransportReady(
                awaitNodeOnline = {
                    state.awaitNodeOnline(readinessTimeoutMs, TimeUnit.MILLISECONDS) { current.isOnline() }
                },
                scheduleDial = { onConnected ->
                    val localBefore = permits.inFlight
                    val nativeBefore = permits.nativeInFlight
                    observeNative(nativeBefore)
                    val slot = if (target != null) permits.tryAcquire() else null
                    if (slot != null && target != null) {
                        scheduled.incrementAndGet()
                        observeNative(permits.nativeInFlight)
                        val (addresses, port) = target
                        Log.d(TAG, "remote-dial started slot=$slot elapsedMs=${elapsedMsSince(startedAt)}")
                        recordZeroTierDiagnostic(
                            "zt-dial-slot-start",
                            "waitId=$waitId slot=$slot elapsedMs=${elapsedMsSince(startedAt)}" +
                                " nativeInFlight=${permits.nativeInFlight} localInFlight=${permits.inFlight}",
                        )
                        runCatching {
                            executor.execute {
                                try {
                                    val connection = dialTarget(
                                        addresses,
                                        port,
                                        startedAt,
                                        targetConnectTimeoutMs,
                                        pendingDials,
                                        waitId,
                                        slot,
                                        gateFinished,
                                    )
                                    if (connection != null) {
                                        Log.d(TAG, "remote-dial candidate slot=$slot elapsedMs=${elapsedMsSince(startedAt)}")
                                        dialed.publish(connection)
                                        onConnected()
                                    }
                                } finally {
                                    permits.release()
                                    if (gateFinished.get()) {
                                        recordZeroTierDiagnostic(
                                            "zt-dial-slot-late",
                                            "waitId=$waitId slot=$slot elapsedMs=${elapsedMsSince(startedAt)}" +
                                                " nativeInFlight=${permits.nativeInFlight}",
                                        )
                                    }
                                }
                            }
                        }.onFailure {
                            executorRejected.incrementAndGet()
                            permits.release()
                        }
                    } else if (target != null) {
                        rejected.incrementAndGet()
                        // These are observations immediately around a rejected request, not a
                        // claim about the exact winner of the concurrent permit race.
                        val nativeAfter = permits.nativeInFlight
                        val localAfter = permits.inFlight
                        observeNative(nativeAfter)
                        if (nativeBefore >= maxConcurrentDials || nativeAfter >= maxConcurrentDials) nativeFullObserved.incrementAndGet()
                        if (localBefore > 0 || localAfter > 0) localBusyObserved.incrementAndGet()
                        if (localBefore == 0 && localAfter == 0 &&
                            nativeBefore >= maxConcurrentDials && nativeAfter >= maxConcurrentDials
                        ) globalFullLocalIdleObserved.incrementAndGet()
                    }
                },
                dialAttempts = DIAL_ATTEMPTS,
                timeoutMillis = readinessTimeoutMs,
                retryIntervalMillis = DIAL_RETRY_INTERVAL_MILLIS,
                requireTargetDial = target != null,
                onSample = { note ->
                    schedulePeerDiagnostics()
                    val inFlight = permits.inFlight
                    Log.d(
                        TAG,
                        "sample $note elapsedMs=${elapsedMsSince(startedAt)} online=${isServiceOnline()}" +
                            " transportReady=${transportReady(networkId)} address=${hasAddress(networkId)}" +
                            " dialInFlight=${inFlight > 0} concurrent=$inFlight" +
                            " nativeConcurrent=${permits.nativeInFlight}",
                    )
                },
            )
            val selectedDial = dialed.claim()
            waitResult = signal
            completedWait = true
            return signal to selectedDial
        } catch (cancelled: CancellationException) {
            waitResult = "cancelled"
            throw cancelled
        } finally {
            // Native probes have explicit budgets; closing the registry fences late dials, and a
            // socket that finishes after cancellation is closed by the registry before publication.
            gateFinished.set(true)
            if (!completedWait) dialed.claim()?.first?.let { runCatching { it.close() } }
            pendingDials.close()
            val nativeAtEnd = permits.nativeInFlight
            observeNative(nativeAtEnd)
            val waitElapsedMs = elapsedMsSince(startedAt)
            val elapsedDeltaMs = android.os.SystemClock.elapsedRealtime() - waitElapsedRealtimeStart
            val uptimeDeltaMs = android.os.SystemClock.uptimeMillis() - waitUptimeStart
            // deepSleepMs only counts suspend-to-idle; a cgroup-frozen (Doze/OEM freezer) process
            // shows up as overrunMs: the timer did not run for much longer than its own budget.
            val deepSleepMs = (elapsedDeltaMs - uptimeDeltaMs).coerceAtLeast(0L)
            val overrunMs = (waitElapsedMs - readinessTimeoutMs).coerceAtLeast(0L)
            recordZeroTierDiagnostic(
                "zt-dial-wait-end",
                "waitId=$waitId result=$waitResult elapsedMs=$waitElapsedMs" +
                    " readinessBudgetMs=$readinessTimeoutMs overrunMs=$overrunMs deepSleepMs=$deepSleepMs" +
                    " stalled=${overrunMs > 0L}" +
                    " scheduled=${scheduled.get()} rejected=${rejected.get()}" +
                    " nativeFullObservedOnReject=${nativeFullObserved.get()} localBusyObservedOnReject=${localBusyObserved.get()}" +
                    " globalFullLocalIdleOnReject=${globalFullLocalIdleObserved.get()}" +
                    " executorRejected=${executorRejected.get()} peakNativeObserved=${peakNative.get()}" +
                    " nativeInFlight=$nativeAtEnd localInFlight=${permits.inFlight}" +
                    " maxConcurrent=$maxConcurrentDials scope=readiness",
            )
        }
    }

    /** Sample all known leaf peers, not necessarily the target; never block the recovery gate. */
    private fun schedulePeerDiagnostics() {
        val now = System.nanoTime()
        val last = lastPeerDiagnosticsNanos.get()
        if (last != 0L && now - last < TimeUnit.SECONDS.toNanos(10)) return
        if (!peerDiagnosticsInFlight.compareAndSet(false, true)) return
        lastPeerDiagnosticsNanos.set(now)
        try {
            executor.execute {
                try {
                    val snapshot = ZeroTierNative.zts_peer_path_diagnostics(0L)
                    if (snapshot != null && snapshot.size == 7) {
                        val detail = "scope=all-leaf peers=${snapshot[0]}" +
                            " directPeers=${snapshot[1]} paths=${snapshot[2]} preferredPaths=${snapshot[3]}" +
                            " latestReceiveAgeMs=${snapshot[4]} latestSendAgeMs=${snapshot[5]}" +
                            " snapshotAgeMs=${snapshot[6]}"
                        Log.d(TAG, "peer-diagnostics $detail")
                        // The JNI snapshot summarizes all leaf peers, not necessarily the target.
                        recordZeroTierDiagnostic("zt-peer-summary", detail)
                    } else {
                        Log.d(TAG, "peer-diagnostics scope=all-leaf result=unavailable")
                        recordZeroTierDiagnostic("zt-peer-summary", "scope=all-leaf result=unavailable")
                    }
                } catch (error: Throwable) {
                    Log.d(TAG, "peer-diagnostics scope=all-leaf result=error type=${error.javaClass.simpleName}")
                    recordZeroTierDiagnostic("zt-peer-summary", "scope=all-leaf result=error")
                } finally {
                    peerDiagnosticsInFlight.set(false)
                }
            }
        } catch (error: RuntimeException) {
            peerDiagnosticsInFlight.set(false)
            Log.d(TAG, "peer-diagnostics scope=all-leaf result=schedule-failed type=${error.javaClass.simpleName}")
        }
    }

    /** Dial the target over ZeroTier and keep the socket that proves the mesh carried the connection. */
    private fun dialTarget(
        addresses: List<String>,
        remotePort: Int,
        startedAt: Long,
        timeoutMs: Int,
        pendingDials: BoundedConnectRegistry<ZeroTierSocket>,
        waitId: Long,
        slot: Int,
        gateFinished: AtomicBoolean,
    ): DialedTarget? {
        val connectStartedAt = System.nanoTime()
        for (address in addresses) {
            val remainingMs = remainingConnectTimeoutMs(timeoutMs, connectStartedAt, System.nanoTime())
            val target = ZeroTierDiagnosticTargets.describe(address)
            if (remainingMs <= 0) {
                recordZeroTierDiagnostic(
                    "zt-native-dial-end",
                    "waitId=$waitId slot=$slot $target result=budget-exhausted" +
                        " budgetMs=0 elapsedMs=${elapsedMsSince(startedAt)} retiredObserved=${gateFinished.get()}",
                )
                break
            }
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            recordZeroTierDiagnostic(
                "zt-native-dial-start",
                "waitId=$waitId slot=$slot $target budgetMs=$remainingMs" +
                    " waitElapsedMs=${elapsedMsSince(startedAt)} nativeInFlight=${nativeDialPermits.inFlight}" +
                    " retiredObserved=${gateFinished.get()} scope=readiness",
            )
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull()
            if (socket == null) {
                recordZeroTierDiagnostic(
                    "zt-native-dial-end",
                    "waitId=$waitId slot=$slot $target result=socket-create-failed" +
                        " budgetMs=$remainingMs retiredObserved=${gateFinished.get()}",
                )
                continue
            }
            if (!pendingDials.register(socket)) {
                recordZeroTierDiagnostic(
                    "zt-native-dial-end",
                    "waitId=$waitId slot=$slot $target result=registry-closed" +
                        " budgetMs=$remainingMs retiredObserved=${gateFinished.get()}",
                )
                return null
            }
            // This record must precede the blocking JNI call: if it never returns the exported
            // timeline still identifies the occupied slot, target family and timeout budget.
            recordZeroTierDiagnostic(
                "zt-native-connect-start",
                "waitId=$waitId slot=$slot $target budgetMs=$remainingMs" +
                    " waitElapsedMs=${elapsedMsSince(startedAt)} nativeInFlight=${nativeDialPermits.inFlight}" +
                    " retiredObserved=${gateFinished.get()} scope=readiness",
            )
            val nativeStartedAt = System.nanoTime()
            val failure = runCatching { socket.connect(address, remotePort, remainingMs) }.exceptionOrNull()
            val nativeConnectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nativeStartedAt)
            // Only retain numeric native return codes, not the arbitrary exception message, which
            // can include a host address or other private transport information.
            val nativeError = failure?.message?.let { message ->
                Regex("\\((-?\\d+)\\)").find(message)?.groupValues?.get(1)
            }
            val nativeResult = failure?.message?.let { message ->
                Regex("libztResult=(-?\\d+)").find(message)?.groupValues?.get(1)
            }
            val nativeErrno = failure?.message?.let { message ->
                Regex("errno=(-?\\d+|unavailable)").find(message)?.groupValues?.get(1)
            }
            // Report the JNI return before finishing/closing the socket; a slow close must not
            // look like a connect call that never returned.
            recordZeroTierDiagnostic(
                "zt-native-connect-return",
                "waitId=$waitId slot=$slot $target result=${if (failure == null) "ok" else "failed"}" +
                    " budgetMs=$remainingMs nativeElapsedMs=$nativeConnectMs" +
                    " libztResult=${nativeResult ?: "unavailable"} errno=${nativeErrno ?: "unavailable"}" +
                    " retiredObserved=${gateFinished.get()} scope=readiness",
            )
            var socketCloseMs: Long? = null
            val transferred = pendingDials.finish(socket, connected = failure == null) { elapsed ->
                socketCloseMs = elapsed
            }
            val result = when {
                failure != null -> "failed"
                !transferred -> "late-success-closed"
                else -> "connected"
            }
            recordZeroTierDiagnostic(
                "zt-native-dial-end",
                "waitId=$waitId slot=$slot $target result=$result budgetMs=$remainingMs" +
                    " nativeElapsedMs=$nativeConnectMs waitElapsedMs=${elapsedMsSince(startedAt)}" +
                    " libztResult=${nativeResult ?: "unavailable"} errno=${nativeErrno ?: "unavailable"}" +
                    " errorType=${failure?.javaClass?.simpleName ?: "none"}" +
                    " socketCloseMs=${socketCloseMs ?: "none"} retiredObserved=${gateFinished.get()}" +
                    " nativeInFlight=${nativeDialPermits.inFlight} scope=readiness",
            )
            Log.d(
                TAG,
                "remote-dial native-connect $target budgetMs=$remainingMs" +
                    " elapsedMs=$nativeConnectMs result=$result" +
                    " errorType=${failure?.javaClass?.simpleName ?: "none"} errorCode=${nativeError ?: "none"}" +
                    " libztResult=${nativeResult ?: "unavailable"} errno=${nativeErrno ?: "unavailable"}",
            )
            if (failure != null || !transferred) continue
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
        if (state.awaitNetworkAddress(networkId, 15, TimeUnit.SECONDS) { hasAddress(networkId) }) {
            state.clearNetworkError(networkId)
            return
        }
        // A timeout is not an authorization decision. libzt reports NETWORK_ACCESS_DENIED through
        // the readiness wait above, so without a recorded error the node simply has not finished
        // joining yet and the wait must stay retryable instead of asking for a manual action.
        val failure = state.failureOnAddressTimeout(
            networkId,
            java.lang.Long.toUnsignedString(current.id, 16).padStart(10, '0'),
            context.getString(R.string.zero_tier_node_unavailable),
        )
        if (failure is MeshNotReady) noteNotReady("phase=await-address timeoutMs=15000")
        throw failure
    }

    private fun relayFor(
        config: HostConfig,
        timing: ZeroTierTiming,
        dialed: DialedTarget?,
        targetConnectTimeoutMs: Int,
    ): MeshRelay {
        val resolveStart = timing.start("relay-resolve")
        val (addresses, remotePort) = try {
            resolveTarget(config)
        } catch (error: Throwable) {
            closeDialed(dialed)
            throw error
        }
        Log.d(TAG, "Resolved ZeroTier relay targetCount=${addresses.size}")
        timing.phase(
            "relay-resolve", resolveStart, "ok",
            detail = "targetCount=${addresses.size} ipv6Count=${addresses.count { ':' in it }}",
        )
        // This method owns `dialed` from here on: every path below adopts it into a relay or closes
        // it. A connection that already proved the path must never be dropped without an owner.
        // The relay only forwards to this target, so a reused relay can take the connection too.
        relay?.takeIf { it.canReuse(addresses, remotePort) }?.let {
            it.adopt(dialed, targetConnectTimeoutMs)
            Log.d(TAG, "Reusing ZeroTier relay at ${it.localPort} targetCount=${addresses.size}")
            return it.relay
        }
        runCatching { relay?.close() }
        Log.d(TAG, "Opening ZeroTier relay targetCount=${addresses.size}")
        val bindStart = timing.start("relay-bind")
        val nextRelay = try {
            ZeroTierRelay(addresses, remotePort, executor, targetConnectTimeoutMs, dialed)
        } catch (error: Throwable) {
            closeDialed(dialed)
            throw error
        }
        try {
            nextRelay.start()
        } catch (error: Throwable) {
            // The relay already owns `dialed`; closing it releases the bound server socket and the
            // unused proven connection.
            nextRelay.close()
            throw error
        }
        timing.phase("relay-bind", bindStart, "ok", detail = "port=${nextRelay.localPort}")
        relay = nextRelay
        return nextRelay.relay
    }

    /** Release a proven target connection that no relay took ownership of. */
    private fun closeDialed(dialed: DialedTarget?) {
        runCatching { dialed?.first?.close() }
    }

    private fun checkResult(code: Int, operation: String) {
        if (code < 0) throw IOException("Unable to $operation (libzt error $code)")
    }

    private companion object {
        const val TAG = "ZeroTierConnector"
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
        val total = TimeUnit.NANOSECONDS.toMillis(now - startedAt)
        Log.d(TAG, "ztAttempt=$id phase=$name start totalMs=$total")
        ConnectionTrace.record("zt-timing", "ztAttempt=$id phase=$name start totalMs=$total")
        return now
    }

    fun phase(name: String, phaseStartedAt: Long, result: String, detail: String = "") {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedAt)
        val total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val suffix = if (detail.isBlank()) "" else " detail=$detail"
        Log.d(TAG, "ztAttempt=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result$suffix")
        ConnectionTrace.record("zt-timing", "ztAttempt=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result$suffix")
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
    private val networkErrors = mutableMapOf<Long, Throwable>()

    @Synchronized fun nodeOnline() { online.complete(Unit) }
    @Synchronized fun nodeOffline() {
        online.complete(Unit) // Wake waiters bound to the retired event before replacing it.
        online = CompletableDeferred()
    }
    @Synchronized fun networkReady(id: Long) {
        networkErrors.remove(id)
        val event = networks[id]?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>()
        networks[id] = event
        event.complete(Unit)
    }
    @Synchronized fun networkUnavailable(id: Long) {
        networks[id]?.complete(Unit)
        networks[id] = CompletableDeferred()
    }
    @Synchronized fun networkError(id: Long, error: Throwable) {
        networkErrors[id] = error
        val event = networks[id]?.takeUnless { it.isCompleted } ?: CompletableDeferred<Unit>()
        networks[id] = event
        event.completeExceptionally(error)
    }

    @Synchronized fun clearNetworkError(id: Long) { networkErrors.remove(id) }

    /**
     * What a timed-out address wait should surface for [id].
     *
     * A denial or "network not found" callback recorded for this network wins, because libzt emits
     * those at most once. Without one, the wait only means the stack has not finished joining yet,
     * which is transient and retryable rather than a user action item.
     */
    fun failureOnAddressTimeout(id: Long, nodeId: String, nodeUnavailableMessage: String): Throwable =
        when (val recorded = synchronized(this) { networkErrors[id] }) {
            null -> MeshNotReady(nodeUnavailableMessage)
            is MeshAuthorizationPending -> MeshAuthorizationPending(
                "Authorize ZeroTier node $nodeId in the network controller, then tap Connect again.",
            )
            else -> recorded
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

/** Stable only within this app process; neither an IP address nor a network identity is logged. */
private object ZeroTierDiagnosticTargets {
    private val salt = runCatching { UUID.randomUUID().toString().toByteArray(Charsets.UTF_8) }.getOrNull()

    fun describe(address: String): String {
        val family = if (':' in address) "ipv6" else "ipv4"
        val id = salt?.let { secret ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(secret)
                digest.digest(address.toByteArray(Charsets.UTF_8)).take(8)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.getOrNull()
        } ?: "unavailable"
        return "targetId=$id family=$family"
    }
}

/** Diagnostics must never turn a native connection failure into an application failure. */
private fun recordZeroTierDiagnostic(event: String, detail: String) {
    runCatching { ConnectionTrace.record(event, detail) }
}

/** A ZeroTier TCP connection that already reached the relay's target, with the address it used. */
private typealias DialedTarget = Pair<ZeroTierSocket, String>

private class ZeroTierRelay(
    private val addresses: List<String>,
    private val remotePort: Int,
    private val executor: ExecutorService,
    targetConnectTimeoutMs: Int,
    dialed: DialedTarget? = null,
) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = ConcurrentHashMap.newKeySet<ZeroTierForwardWorker>()
    private val connectingSockets = BoundedConnectRegistry<ZeroTierSocket> { socket -> runCatching { socket.close() } }
    private val pending = SingleClaim<DialedTarget> { (socket, _) -> runCatching { socket.close() } }
        .apply { dialed?.let(::publish) }
    @Volatile private var targetConnectTimeoutMs = targetConnectTimeoutMs
    private val nextWorkerId = AtomicLong()
    @Volatile private var running = false
    val localPort: Int get() = server.localPort
    val relay: MeshRelay get() = MeshRelay("127.0.0.1", localPort)

    fun canReuse(expectedAddresses: List<String>, expectedPort: Int): Boolean =
        running && addresses == expectedAddresses && remotePort == expectedPort

    /** Offer a connection that already reached this relay's target to the next forwarded stream. */
    fun adopt(dialed: DialedTarget?, targetConnectTimeoutMs: Int) {
        this.targetConnectTimeoutMs = targetConnectTimeoutMs
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
                        Log.d(TAG, "relay accepted clientPort=$clientPort relayPort=$relayPort")
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
                    Log.w(TAG, "ZeroTier relay accept loop failed type=${error.javaClass.simpleName}")
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
            Log.d(TAG, "relay worker=$workerId connected clientPort=$clientPort localPort=$localPort ${ZeroTierDiagnosticTargets.describe(targetAddress)} connectMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}")
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
                    Log.d(TAG, "relay worker=$workerId finished clientPort=$clientPort localPort=$localPort ${ZeroTierDiagnosticTargets.describe(targetAddress)} elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)} requestClass=${stats.requestClass} endpoint=${stats.rpcEndpoint} responseStatus=${stats.responseStatus.takeIf { status -> status >= 0 } ?: "none"} localToNative=read:${stats.localToRemoteReadBytes},written:${stats.localToRemoteBytes},end:${stats.localToRemoteEnd} nativeToLocal=read:${stats.remoteToLocalReadBytes},written:${stats.remoteToLocalBytes},end:${stats.remoteToLocalEnd}")
                },
            )
            workers.add(worker)
            handedToWorker = true
            if (running) worker.start() else worker.close()
        } catch (error: Throwable) {
            // A libzt socket can fail while its relay is being replaced. This executor owns no
            // coroutine exception handler, so never let a native/JNI failure escape and kill the app.
            Log.w(TAG, "ZeroTier forwarding worker setup failed type=${error.javaClass.simpleName}")
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
            Log.d(TAG, "ZeroTier relay adopted a proven connection ${ZeroTierDiagnosticTargets.describe(address)}")
            return socket to address
        }
        val connectStartedAt = System.nanoTime()
        addresses.forEach { address ->
            val remainingMs = remainingConnectTimeoutMs(targetConnectTimeoutMs, connectStartedAt, System.nanoTime())
            if (remainingMs <= 0) return null
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull() ?: return@forEach
            if (!connectingSockets.register(socket)) return null
            val failure = runCatching { socket.connect(address, remotePort, remainingMs) }.exceptionOrNull()
            if (failure == null && connectingSockets.finish(socket, connected = true)) {
                Log.d(TAG, "ZeroTier relay connected ${ZeroTierDiagnosticTargets.describe(address)}")
                return socket to address
            }
            // `finish(false)` closes after the bounded native call returns. If relay shutdown won
            // the race, a late success is also closed instead of being handed to a dead forwarder.
            if (failure != null) {
                connectingSockets.finish(socket, connected = false)
                Log.w(TAG, "ZeroTier relay failed to connect ${ZeroTierDiagnosticTargets.describe(address)} type=${failure.javaClass.simpleName}")
            }
        }
        return null
    }

    override fun close() {
        running = false
        connectingSockets.close()
        runCatching { server.close() }
        // An unclaimed connection to the host would otherwise stay open with no reader on either side.
        pending.claim()?.let { (socket, _) -> runCatching { socket.close() } }
        // Do not close libzt here: a worker may still be inside a native read/write, and doing so
        // concurrently produced a test device SIGSEGV. Closing each loopback endpoint makes its peer
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
