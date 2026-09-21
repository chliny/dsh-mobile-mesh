package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.PublicKey
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
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

    /** Called exactly once when a live SSH forwarder exits unexpectedly. */
    @Volatile
    var onRelayTerminated: ((token: Long) -> Unit)? = null

    val activeRelayToken: Long?
        get() = active?.token

    suspend fun start(
        config: HostConfig,
        sshHost: String,
        sshPort: Int,
        recovery: Boolean = false,
    ): SshRelay = withContext(Dispatchers.IO) {
        require(config.sshEnabled)
        active?.takeIf { it.canReuse(config.id, sshHost, sshPort) }?.let {
            Log.d(TAG, "Reusing authenticated SSH relay at ${it.relay.baseUrl} to $sshHost:$sshPort")
            return@withContext it.relay
        }
        Log.d(TAG, "Opening SSH relay to $sshHost:$sshPort")
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
        fun newClient(): SSHClient = SSHClient(sshConfig).also {
            it.addHostKeyVerifier(AcceptAllHostKeyVerifier)
            it.setConnectTimeout(SSH_CONNECT_TIMEOUT_MS)
            // SSHJ completes connect/auth through its transport callbacks and blocking API. The
            // socket/transport timeout is only a safety ceiling for a peer that emits no event;
            // readiness is decided by connect/auth returning, never by sleeping and rechecking.
            it.setTimeout(SSH_HANDSHAKE_TIMEOUT_MS)
            it.transport.setTimeoutMs(SSH_HANDSHAKE_TIMEOUT_MS)
        }
        var client = newClient()
        var keyFile: File? = null
        try {
            // Recreate SSHJ after a failed handshake: SSHJ's transport thread cannot be restarted
            // after a banner read reset (reusing it raises IllegalThreadStateException).
            val connectStartedAt = System.nanoTime()
            // tsnet's local relay can take a moment to expose the remote SSH banner after the
            // native node becomes ready. Retry only the transport handshake; authentication is
            // never repeated blindly and remains below this block.
            var lastConnectError: Throwable? = null
            val attempts = if (recovery) SSH_RECOVERY_CONNECT_ATTEMPTS else SSH_CONNECT_ATTEMPTS
            for (attempt in 0 until attempts) {
                if (attempt > 0) client = newClient()
                try {
                    client.connect(sshHost, sshPort)
                    lastConnectError = null
                    break
                } catch (error: Throwable) {
                    lastConnectError = error
                    runCatching { client.close() }
                    if (attempt + 1 < attempts) {
                        Log.w(TAG, "SSH transport attempt ${attempt + 1} failed; retrying", error)
                        Thread.sleep(SSH_CONNECT_RETRY_DELAY_MS)
                    }
                }
            }
            lastConnectError?.let { throw it }
            Log.d(TAG, "SSH transport ready in ${elapsedMs(connectStartedAt)}ms to $sshHost:$sshPort")
            // NATs commonly discard an idle SSH TCP mapping long before the app's next RPC. SSHJ's
            // transport-level keepalive both refreshes that mapping and makes a dead forward fail
            // promptly, so ConnectionLoop can rebuild the mesh + SSH path.
            client.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
            val authStartedAt = System.nanoTime()
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
            Log.d(TAG, "SSH authentication ready in ${elapsedMs(authStartedAt)}ms for $sshHost:$sshPort")
            val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            val forwarder = client.newLocalPortForwarder(
                Parameters("127.0.0.1", server.localPort, config.sshDshHost, config.port),
                server,
            )
            val termination = RelayTerminationGate()
            val thread = Thread({
                val failure = runCatching { forwarder.listen() }.exceptionOrNull()
                if (termination.reportUnexpectedTermination()) {
                    Log.w(TAG, "SSH forwarder stopped unexpectedly", failure)
                    onRelayTerminated?.invoke(termination.token)
                }
            }, "dsh-ssh-forward").apply {
                isDaemon = true
                start()
            }
            val relay = SshRelay("http://127.0.0.1:${server.localPort}")
            active = ActiveTunnel(config.id, sshHost, sshPort, client, forwarder, thread, relay, termination)
            Log.d(TAG, "SSH local forward ready at ${relay.baseUrl} -> ${config.sshDshHost}:${config.port}")
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
        private val termination: RelayTerminationGate,
    ) : Closeable {
        val token: Long get() = termination.token

        fun canReuse(configId: String, sshHost: String, sshPort: Int): Boolean =
            this.configId == configId && this.sshHost == sshHost && this.sshPort == sshPort &&
                client.isConnected && client.isAuthenticated && client.transport.isRunning && thread.isAlive

        override fun close() {
            termination.markClosing()
            runCatching { forwarder.close() }
            runCatching { client.close() }
            thread.interrupt()
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private companion object {
        /** Below typical mobile NAT idle expiry without needlessly waking the radio. */
        const val KEEP_ALIVE_INTERVAL_SECONDS = 20
        const val SSH_CONNECT_TIMEOUT_MS = 10_000
        const val SSH_CONNECT_ATTEMPTS = 3
        /** Recovery gets one immediate retry; the outer transport loop owns longer backoff. */
        const val SSH_RECOVERY_CONNECT_ATTEMPTS = 2
        const val SSH_CONNECT_RETRY_DELAY_MS = 750L
        /**
         * Long enough for a slow userspace path, far short of sshj's default.
         *
         * A suspended ZeroTier peer can complete the TCP and banner exchange and then stop
         * carrying bytes, which left authentication waiting two minutes. Failing in a fraction of
         * that lets the recovery retry rebuild the relay while the user is still watching.
         */
        const val SSH_HANDSHAKE_TIMEOUT_MS = 25_000
        const val TAG = "SshTunnelManager"
    }
}

private object AcceptAllHostKeyVerifier : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = true

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
