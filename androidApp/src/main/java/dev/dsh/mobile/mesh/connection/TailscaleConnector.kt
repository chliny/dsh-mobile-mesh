package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
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

private object TailscaleNative {
    init {
        System.loadLibrary("dsh_tsnet_jni")
        System.loadLibrary("dsh_tsnet")
    }
    @JvmStatic external fun startNative(stateDirectory: String, hostname: String, remoteHost: String, remotePort: Int): String
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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkBinding(network)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
            refreshNetworkBinding(network)
        override fun onLost(network: Network) {
            if (connectivity.activeNetwork == null) {
                TailscaleNative.setNetworkNative(null)
                TailscaleNative.setInterfacesNative("[]")
            }
        }
    }

    override suspend fun start(config: HostConfig): MeshRelay = withContext(Dispatchers.IO) {
        require(config.meshTransport == MeshTransport.TAILSCALE) { "Not a Tailscale host" }
        val hostname = config.tailscaleHostname?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,63}")) }
            ?: "dsh-${config.id.take(12)}"
        val stateDirectory = File(context.noBackupFilesDir, "tailscale/${config.id}").apply { mkdirs() }
        val network = connectivity.activeNetwork
            ?: throw IllegalStateException("No active internet connection for Tailscale")
        refreshNetworkBinding(network)
        registerNetworkCallback()
        val result = Json.decodeFromString<TailscaleStartResult>(
            TailscaleNative.startNative(
                stateDirectory.absolutePath,
                hostname,
                config.host,
                if (config.sshEnabled) config.sshPort else config.port,
            ),
        )
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

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            unregisterNetworkCallback()
            TailscaleNative.setNetworkNative(null)
            TailscaleNative.setInterfacesNative("[]")
            TailscaleNative.stopNative()
        }
    }

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
        TailscaleNative.setNetworkNative(network)
        TailscaleNative.setInterfacesNative(networkInterfaces())
    }

    private fun networkInterfaces(): String {
        val entries = JSONArray()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return "[]"
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
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
        return entries.toString()
    }
}
