package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.PublicKey
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.kex.DHG14
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.common.SecurityUtils

data class SshRelay(val baseUrl: String)

/** Owns one SSHJ local forwarder. SSH host-key verification is intentionally disabled. */
@Singleton
class SshTunnelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SshSecretStore,
) {
    private var active: ActiveTunnel? = null

    suspend fun start(config: HostConfig, sshHost: String, sshPort: Int): SshRelay = withContext(Dispatchers.IO) {
        require(config.sshEnabled)
        active?.takeIf { it.canReuse(config.id, sshHost, sshPort) }?.let { return@withContext it.relay }
        stopLocked()
        val username = config.sshUsername?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("SSH username is required")
        val credentials = secrets.get(config.id)
            ?: throw IllegalArgumentException("SSH credentials are missing")
        // Android's platform Bouncy Castle provider has no X25519 implementation, so avoid
        // Curve25519 while retaining the server's other modern NIST ECDH alternatives.
        // Its "BC" name otherwise makes SSHJ pin all cryptography to that incomplete provider.
        SecurityUtils.setRegisterBouncyCastle(false)
        val sshConfig = DefaultConfig().apply {
            keyExchangeFactories = listOf(
                ECDHNistP.Factory256(),
                ECDHNistP.Factory384(),
                ECDHNistP.Factory521(),
                DHGexSHA256.Factory(),
                DHG14.Factory(),
            )
        }
        val client = SSHClient(sshConfig)
        client.addHostKeyVerifier(AcceptAllHostKeyVerifier)
        var keyFile: File? = null
        try {
            client.connect(sshHost, sshPort)
            // NATs commonly discard an idle SSH TCP mapping long before the app's next RPC. SSHJ's
            // transport-level keepalive both refreshes that mapping and makes a dead forward fail
            // promptly, so ConnectionLoop can rebuild the mesh + SSH path.
            client.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
            when (config.sshAuthentication) {
                SshAuthentication.PASSWORD -> client.authPassword(
                    username,
                    credentials.password ?: throw IllegalArgumentException("SSH password is missing"),
                )
                SshAuthentication.PRIVATE_KEY -> {
                    keyFile = File.createTempFile("ssh-key-", null, context.cacheDir).apply {
                        setReadable(false, false)
                        setWritable(false, false)
                        setReadable(true, true)
                        setWritable(true, true)
                        writeText(credentials.privateKey ?: throw IllegalArgumentException("SSH private key is missing"))
                    }
                    val provider = client.loadKeys(keyFile.absolutePath, credentials.privateKeyPassphrase)
                    client.authPublickey(username, provider)
                }
            }
            val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            val forwarder = client.newLocalPortForwarder(
                Parameters("127.0.0.1", server.localPort, config.sshDshHost, config.port),
                server,
            )
            val thread = Thread({ runCatching { forwarder.listen() } }, "dsh-ssh-forward").apply {
                isDaemon = true
                start()
            }
            val relay = SshRelay("http://127.0.0.1:${server.localPort}")
            active = ActiveTunnel(config.id, sshHost, sshPort, client, forwarder, thread, relay)
            relay
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to establish SSH relay to $sshHost:$sshPort", error)
            runCatching { client.close() }
            throw error
        } finally {
            keyFile?.runCatching { delete() }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) { stopLocked() }

    private fun stopLocked() {
        active?.close()
        active = null
    }

    private class ActiveTunnel(
        private val configId: String,
        private val sshHost: String,
        private val sshPort: Int,
        private val client: SSHClient,
        private val forwarder: LocalPortForwarder,
        private val thread: Thread,
        val relay: SshRelay,
    ) : Closeable {
        fun canReuse(configId: String, sshHost: String, sshPort: Int): Boolean =
            this.configId == configId && this.sshHost == sshHost && this.sshPort == sshPort &&
                client.isConnected && client.isAuthenticated && thread.isAlive

        override fun close() {
            runCatching { forwarder.close() }
            runCatching { client.close() }
            thread.interrupt()
        }
    }

    private companion object {
        /** Below typical mobile NAT idle expiry without needlessly waking the radio. */
        const val KEEP_ALIVE_INTERVAL_SECONDS = 20
        const val TAG = "SshTunnelManager"
    }
}

private object AcceptAllHostKeyVerifier : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = true

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
