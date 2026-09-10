package dev.dsh.mobile.mesh.connection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.HostKeyVerifier

class SshHostKeyUnconfirmed(val fingerprint: String) :
    IllegalStateException("Confirm SSH host key $fingerprint and connect again")

data class SshRelay(val baseUrl: String)

/** Owns one SSHJ local forwarder and refuses every unconfirmed or changed host key. */
@Singleton
class SshTunnelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SshSecretStore,
) {
    private var active: ActiveTunnel? = null

    suspend fun start(config: HostConfig, sshHost: String, sshPort: Int): SshRelay = withContext(Dispatchers.IO) {
        stopLocked()
        require(config.sshEnabled)
        val username = config.sshUsername?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("SSH username is required")
        val credentials = secrets.get(config.id)
            ?: throw IllegalArgumentException("SSH credentials are missing")
        val client = SSHClient()
        val expected = config.sshHostKeyFingerprint?.trim()?.takeIf { it.isNotEmpty() }
        val verifier = ExactHostKeyVerifier(expected)
        client.addHostKeyVerifier(verifier)
        var keyFile: File? = null
        try {
            client.connect(sshHost, sshPort)
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
            active = ActiveTunnel(client, forwarder, thread)
            SshRelay("http://127.0.0.1:${server.localPort}")
        } catch (error: Throwable) {
            runCatching { client.close() }
            if (expected == null && verifier.observedFingerprint != null) {
                throw SshHostKeyUnconfirmed(verifier.observedFingerprint!!)
            }
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
        private val client: SSHClient,
        private val forwarder: LocalPortForwarder,
        private val thread: Thread,
    ) : Closeable {
        override fun close() {
            runCatching { forwarder.close() }
            runCatching { client.close() }
            thread.interrupt()
        }
    }
}

private class ExactHostKeyVerifier(private val expected: String?) : HostKeyVerifier {
    var observedFingerprint: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(wireKey)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        observedFingerprint = fingerprint
        if (expected == null) return false
        return constantTimeEquals(normalize(expected), normalize(fingerprint))
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun normalize(value: String): ByteArray = value.trim().removePrefix("SHA256:").let { encoded ->
        runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { encoded.toByteArray() }
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(left, right)
}
