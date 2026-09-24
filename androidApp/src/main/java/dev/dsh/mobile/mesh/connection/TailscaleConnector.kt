package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

internal fun isUsableTailscaleNetwork(hasActiveNetwork: Boolean, hasInternetCapability: Boolean): Boolean =
    hasActiveNetwork && hasInternetCapability

private object TailscaleNative {
    init {
        System.loadLibrary("dsh_tsnet_jni")
        System.loadLibrary("dsh_tsnet")
    }
    @JvmStatic external fun startNative(stateDirectory: String, hostname: String, remoteHost: String, remotePort: Int): String
    @JvmStatic external fun restartRelayNative(remoteHost: String, remotePort: Int): String
    @JvmStatic external fun cancelStartNative(): String
    @JvmStatic external fun stopNative(): String
    @JvmStatic external fun setNetworkNative(network: Network?)
    @JvmStatic external fun setInterfacesNative(interfaces: String)
}

@Serializable
private data class TailscaleStartResult(val state: String, val baseUrl: String? = null, val loginUrl: String? = null, val error: String? = null)

/** tsnet userspace node and its app-local TCP relay. Tailscale authorization is retained in no-backup storage. */
@Singleton
class TailscaleConnector @Inject constructor(
    @ApplicationContext private val context: Context,
) : MeshConnector {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var observingNetwork = false
    private var waitingForNetwork = false
    private var nativeStarted = false
    private var networkWaiter: kotlinx.coroutines.CompletableDeferred<Network?>? = null
    @Volatile private var boundNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkBinding(network)
            completeNetworkWaiterIfUsable(network)
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            refreshNetworkBinding(network)
            completeNetworkWaiterIfUsable(network)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (shouldSignalTailscaleNetworkWaiter(
                    isActiveNetwork = connectivity.activeNetwork == network,
                    hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                )) completeNetworkWaiterIfUsable(network)
        }
        override fun onLost(network: Network) {
            synchronized(lock) {
                // A delayed callback for the retired carrier must never clear or replace the current
                // tsnet binding. Only clear when the callback is for the network we actually bound.
                if (nativeStarted && boundNetwork == network && connectivity.activeNetwork == null) {
                    boundNetwork = null
                    TailscaleNative.setNetworkNative(null)
                    TailscaleNative.setInterfacesNative("[]")
                }
            }
        }
    }

    override suspend fun start(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.TAILSCALE) { "Not a Tailscale host" }
        val hostname = config.tailscaleHostname?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,63}")) }
            ?: "dsh-${config.id.take(12)}"
        val stateDirectory = File(context.noBackupFilesDir, "tailscale/${config.id}").apply { mkdirs() }
        val network = connectivity.activeNetwork
            ?.takeIf { isUsableTailscaleNetwork(hasActiveNetwork = true, hasInternetCapability = isInternetCapable(it)) }
            ?: awaitActiveNetwork()
            ?: throw IllegalStateException("No active internet connection for Tailscale")
        synchronized(lock) {
            nativeStarted = true
            boundNetwork = network
            TailscaleNative.setNetworkNative(network)
            TailscaleNative.setInterfacesNative(networkInterfaces())
            registerNetworkCallback()
        }
        // startNative may wait for tsnet's control-plane state. Do not hold the callback lock
        // across that synchronous JNI call: Android network callbacks must remain able to rebind
        // the native socket while authorization is completing.
        android.util.Log.d("TailscaleConnector", "Starting tsnet remote=${config.host}:${if (config.sshEnabled) config.sshPort else config.port} hostname=$hostname")
        val result = Json.decodeFromString<TailscaleStartResult>(
            TailscaleNative.startNative(
                stateDirectory.absolutePath,
                hostname,
                config.host,
                if (config.sshEnabled) config.sshPort else config.port,
            ),
        )
        android.util.Log.d("TailscaleConnector", "tsnet result state=${result.state} hasBase=${result.baseUrl != null} hasLogin=${result.loginUrl != null} error=${result.error}")
        result.baseUrl?.let { baseUrl ->
            val parsed = Uri.parse(baseUrl)
            MeshRelay(parsed.host ?: "127.0.0.1", parsed.port.takeIf { it > 0 } ?: 80)
        } ?: run {
            if (result.loginUrl != null) {
                // tsnet retains the pending node state. The connection manager polls that same
                // identity after the embedded sign-in completes rather than minting another node.
                throw TailscaleLoginRequired(result.loginUrl)
            }
            throw IllegalStateException(result.error ?: "Tailscale is not ready")
        }
    }

    suspend fun renewRelay(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.TAILSCALE) { "Not a Tailscale host" }
        val startedAt = System.nanoTime()
        val remotePort = if (config.sshEnabled) config.sshPort else config.port
        synchronized(lock) {
            check(nativeStarted) { "Tailscale is not running" }
        }
        val result = Json.decodeFromString<TailscaleStartResult>(
            TailscaleNative.restartRelayNative(config.host, remotePort),
        )
        result.baseUrl?.let { baseUrl ->
            val parsed = Uri.parse(baseUrl)
            android.util.Log.d("TailscaleConnector", "relay-renew elapsedMs=${elapsedMs(startedAt)} result=ok")
            MeshRelay(parsed.host ?: "127.0.0.1", parsed.port.takeIf { it > 0 } ?: 80)
        } ?: throw IllegalStateException(result.error ?: "Tailscale relay is not ready")
    }

    fun cancelStart() {
        runCatching { TailscaleNative.cancelStartNative() }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            val shouldStop = synchronized(lock) {
                unregisterNetworkCallback()
                waitingForNetwork = false
                networkWaiter?.complete(null)
                networkWaiter = null
                if (!nativeStarted) false else {
                    nativeStarted = false
                    boundNetwork = null
                    true
                }
            }
            if (shouldStop) {
                TailscaleNative.setNetworkNative(null)
                TailscaleNative.setInterfacesNative("[]")
                TailscaleNative.stopNative()
            }
        }
    }

    private fun completeNetworkWaiterIfUsable(network: Network) {
        if (!shouldSignalTailscaleNetworkWaiter(
                isActiveNetwork = connectivity.activeNetwork == network,
                hasInternetCapability = isInternetCapable(network),
            )) return
        synchronized(lock) {
            if (waitingForNetwork) {
                waitingForNetwork = false
                networkWaiter?.complete(network)
                networkWaiter = null
            }
        }
    }

    private suspend fun awaitActiveNetwork(): Network? {
        connectivity.activeNetwork?.takeIf(::isInternetCapable)?.let { return it }
        val waiter = kotlinx.coroutines.CompletableDeferred<Network?>()
        synchronized(lock) {
            waitingForNetwork = true
            networkWaiter?.cancel()
            networkWaiter = waiter
        }
        registerNetworkCallback()
        connectivity.activeNetwork?.let(::completeNetworkWaiterIfUsable)
        return try {
            waiter.await()
        } finally {
            synchronized(lock) {
                if (networkWaiter === waiter) {
                    waitingForNetwork = false
                    networkWaiter = null
                }
            }
        }
    }

    private fun isInternetCapable(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun elapsedMs(startedAt: Long): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun registerNetworkCallback() = synchronized(lock) {
        if (observingNetwork) return
        connectivity.registerDefaultNetworkCallback(networkCallback)
        observingNetwork = true
    }

    private fun unregisterNetworkCallback() = synchronized(lock) {
        if (!observingNetwork) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        observingNetwork = false
    }

    /** Rebind new tsnet sockets whenever Android changes its validated/default transport. */
    private fun refreshNetworkBinding(network: Network) {
        synchronized(lock) {
        if (!nativeStarted || connectivity.activeNetwork != network) return@synchronized
        runCatching {
            // Framework callbacks run on a system-managed thread. JNI or interface enumeration
            // failures must not escape it and risk taking down the process.
            val interfaces = networkInterfaces()
            TailscaleNative.setNetworkNative(network)
            TailscaleNative.setInterfacesNative(interfaces)
            boundNetwork = network
        }.onFailure {
            android.util.Log.w("TailscaleConnector", "Unable to refresh tsnet network binding", it)
        }
        }
    }

    private fun networkInterfaces(): String {
        val entries = JSONArray()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return "[]"
        while (interfaces.hasMoreElements()) {
            runCatching {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) return@runCatching
                val addresses = JSONArray().apply {
                    network.interfaceAddresses.forEach { address ->
                        address.address.hostAddress?.let { put("$it/${address.networkPrefixLength}") }
                    }
                }
                entries.put(JSONObject().apply {
                    put("name", network.name)
                    put("index", network.index)
                    put("addresses", addresses)
                })
            }
        }
        return entries.toString()
    }
}
