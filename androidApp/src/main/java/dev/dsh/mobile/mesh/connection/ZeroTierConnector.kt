package dev.dsh.mobile.mesh.connection

import android.content.Context
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ZeroTierNativeBridge {
    init { System.loadLibrary("zt") }
    @JvmStatic external fun safeNodeStop(): Int
}

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

    override suspend fun start(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.ZERO_TIER) { "Not a ZeroTier host" }
        val networkIdText = config.zeroTierNetworkId?.lowercase()
            ?: throw IllegalArgumentException("A ZeroTier network ID is required")
        require(networkIdText.matches(Regex("[0-9a-f]{16}"))) {
            "ZeroTier network ID must contain exactly 16 hexadecimal characters"
        }
        synchronized(lock) {
            stopLocked()
            val networkId = java.lang.Long.parseUnsignedLong(networkIdText, 16)
            val storage = java.io.File(context.noBackupFilesDir, "zerotier/${config.id}").apply { mkdirs() }
            val roots = java.io.File(storage, "roots")
            val planet = config.zeroTierPlanetId?.let(planets::resolve)
            if (config.zeroTierPlanetId != null && planet == null) {
                throw IOException("Configured ZeroTier planet is missing")
            }
            if (planet == null) roots.delete() else planet.copyTo(roots, overwrite = true)
            val nextNode = ZeroTierNode()
            checkResult(nextNode.initFromStorage(storage.absolutePath), "initialize ZeroTier")
            checkResult(nextNode.initAllowRootsCache(planet == null), "configure ZeroTier")
            checkResult(nextNode.start(), "start ZeroTier")
            node = nextNode
            waitForOnline(nextNode)
            checkResult(nextNode.join(networkId), "join ZeroTier network")
            waitForAddress(nextNode, networkId)
            val addresses = InetAddress.getAllByName(config.host).mapNotNull { it.hostAddress }.distinct()
            require(addresses.isNotEmpty()) { "ZeroTier server name did not resolve" }
            val remotePort = if (config.sshEnabled) config.sshPort else config.port
            val nextRelay = ZeroTierRelay(addresses, remotePort, executor).also { it.start() }
            relay = nextRelay
            MeshRelay("127.0.0.1", nextRelay.localPort)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) { synchronized(lock) { stopLocked() } }

    private fun stopLocked() {
        relay?.close()
        relay = null
        // libzt is process-global, including after a failed initialization.
        runCatching { ZeroTierNativeBridge.safeNodeStop() }
        node = null
    }

    private fun waitForOnline(current: ZeroTierNode) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!current.isOnline() && System.nanoTime() < deadline) Thread.sleep(50)
        check(current.isOnline()) { "ZeroTier node did not come online; check internet access" }
    }

    private fun waitForAddress(current: ZeroTierNode, networkId: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET) == 1 ||
                ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET6) == 1) return
            Thread.sleep(150)
        }
        val nodeId = java.lang.Long.toUnsignedString(current.id, 16).padStart(10, '0')
        throw IOException("ZeroTier node $nodeId is awaiting network authorization or an assigned IP")
    }

    private fun checkResult(code: Int, operation: String) {
        if (code < 0) throw IOException("Unable to $operation (libzt error $code)")
    }
}

private class ZeroTierRelay(
    private val addresses: List<String>,
    private val remotePort: Int,
    private val executor: java.util.concurrent.ExecutorService,
) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = false
    val localPort: Int get() = server.localPort

    fun start() {
        running = true
        executor.execute {
            while (running) {
                val local = try { server.accept() } catch (_: IOException) { break }
                executor.execute { forward(local) }
            }
        }
    }

    private fun forward(local: Socket) {
        local.use { client ->
            val remote = connect() ?: return
            try {
                executor.execute { runCatching { client.getInputStream().copyTo(remote.outputStream) } }
                runCatching { remote.inputStream.copyTo(client.getOutputStream()) }
            } finally {
                runCatching { remote.close() }
            }
        }
    }

    private fun connect(): ZeroTierSocket? {
        addresses.forEach { address ->
            val family = if (':' in address) ZeroTierNative.ZTS_AF_INET6 else ZeroTierNative.ZTS_AF_INET
            val socket = runCatching { ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0) }.getOrNull() ?: return@forEach
            try {
                socket.connect(address, remotePort)
                return socket
            } catch (_: IOException) { runCatching { socket.close() } }
        }
        return null
    }

    override fun close() { running = false; runCatching { server.close() } }
}
