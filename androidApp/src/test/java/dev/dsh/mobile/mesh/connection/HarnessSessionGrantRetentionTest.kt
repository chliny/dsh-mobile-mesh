package dev.dsh.mobile.mesh.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.dsh.mobile.mesh.core.wire.SessionExchange
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A renewal that cannot mint must be a no-op.
 *
 * The launch token rotates with its harness process, so a proactive exchange can be refused while
 * the cookie already on disk is still perfectly usable. Only a granted exchange may touch the
 * stored grant; a refusal or an unreachable host has to leave the working session exactly as it
 * was, otherwise the attempt to *keep* the session alive would be what signs the user out.
 */
class HarnessSessionGrantRetentionTest {
    @get:Rule val files = TemporaryFolder()

    private val cookie = "dsh-auth-abc=v1.eyJhIjoxfQ.sig"

    @Test
    fun `a refused or unreachable exchange keeps the usable session`() = runBlocking {
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val response = if ("token=current" in requestLine) {
                "HTTP/1.1 303 See Other\r\n" +
                    "Location: ./\r\n" +
                    "Set-Cookie: $cookie; Max-Age=2592000; Path=/; Expires=Sat, 27 Dec 2026 07:00:00 GMT; HttpOnly; SameSite=Strict\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
            } else {
                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        }
        val server = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    serve(client)
                } catch (_: IOException) {
                    // The peer went away; the next attempt gets its own connection.
                } finally {
                    runCatching { client.close() }
                }
            }
        }
        try {
            val data = PreferenceDataStoreFactory.create(scope = dataScope) { files.newFile("grants.preferences_pb") }
            val sessions = HarnessSessionStore(data, OkHttpClient())
            val host = HostConfig("host", "host", "127.0.0.1", socket.localPort)
            val baseUrl = "http://127.0.0.1:${socket.localPort}"

            // A granted exchange establishes the session a later failure must preserve.
            assertTrue(sessions.pair(host, baseUrl, "current") is SessionExchange.Granted)
            assertEquals(cookie, sessions.cookie(host))
            val grantedRefreshAt = sessions.refreshAt(host)
            assertNotNull(grantedRefreshAt)

            // The harness restarted and rotated the token: this exchange is refused, but the
            // cookie it was trying to replace is still valid on the host.
            val refused = sessions.pair(host, baseUrl, "rotated")
            assertTrue(refused is SessionExchange.Refused)
            assertEquals("a refused renewal must not replace the stored cookie", cookie, sessions.cookie(host))
            assertEquals(grantedRefreshAt, sessions.refreshAt(host))

            // The host went away mid-refresh: same rule.
            socket.close()
            server.join(5_000)
            val unreachable = sessions.pair(host, baseUrl, "current")
            assertTrue(unreachable is SessionExchange.Unreachable)
            assertEquals("an unreachable host must not replace the stored cookie", cookie, sessions.cookie(host))
            assertEquals(grantedRefreshAt, sessions.refreshAt(host))
        } finally {
            runCatching { socket.close() }
            server.join(5_000)
            dataScope.cancel()
        }
    }
}
