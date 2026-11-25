package dev.dsh.mobile.mesh.core.wire

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The image route the web client renders workspace images with, exercised over the real carrier.
 *
 * `/api/file` answers a bare path with a `302` to a versioned URL; the signed cookie and the relay
 * `Host` header have to accompany that second request too, or a paired app would get a 401 exactly
 * once and show a caption where the web shows the image.
 */
class HarnessFileApiTest {

    private val requests = CopyOnWriteArrayList<HttpExchange>()

    private fun serve(handler: (HttpExchange) -> Unit): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/file") { exchange ->
            requests += exchange
            handler(exchange)
        }
        server.start()
        return server to server.address.port
    }

    private fun HttpExchange.respond(status: Int, body: ByteArray, type: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
        close()
    }

    @Test
    fun `image bytes arrive through the version redirect with the session headers`() = runBlocking {
        val image = ByteArray(64) { (it * 7).toByte() }
        val (server, port) = serve { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            if (!query.contains("v=")) {
                // The Host's actual answer: a query-only reference that must keep `/api/file`.
                exchange.respond(302, ByteArray(0), "text/plain", mapOf("Location" to "?path=%2Fwork%2Fshot.png&v=42"))
            } else {
                exchange.respond(200, image, "image/png")
            }
        }
        try {
            val api = HarnessFileApi(
                baseUrl = "http://127.0.0.1:$port",
                cookie = "dsh-auth-host=v1.ticket",
                hostHeader = "harness.local:3080",
                httpClient = okHttpClient(timeoutMs = 5_000),
            )
            assertArrayEquals(image, api.readImage("/work/shot.png"))
            assertEquals(2, requests.size)
            assertEquals("/api/file?path=%2Fwork%2Fshot.png", requests[0].requestURI.toString())
            assertEquals("dsh-auth-host=v1.ticket", requests[0].requestHeaders.getFirst("Cookie"))
            assertEquals("dsh-auth-host=v1.ticket", requests[1].requestHeaders.getFirst("Cookie"))
            assertEquals("harness.local:3080", requests[1].requestHeaders.getFirst("Host"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a redirect to another origin is not followed`() = runBlocking {
        val (server, port) = serve { exchange ->
            exchange.respond(302, ByteArray(0), "text/plain", mapOf("Location" to "http://example.invalid/steal.png"))
        }
        try {
            val api = HarnessFileApi("http://127.0.0.1:$port", "dsh-auth=v1", null, okHttpClient(5_000))
            assertNull(api.readImage("/work/shot.png"))
            assertEquals(1, requests.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a host without the file route yields null`() = runBlocking {
        val (server, port) = serve { exchange -> exchange.respond(404, ByteArray(0), "text/plain") }
        try {
            val api = HarnessFileApi("http://127.0.0.1:$port", null, null, okHttpClient(5_000))
            assertNull(api.readImage("/work/missing.png"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-image answer is refused instead of decoded as one`() = runBlocking {
        val (server, port) = serve { exchange ->
            exchange.respond(200, "<html>not an image</html>".toByteArray(), "text/html")
        }
        try {
            val api = HarnessFileApi("http://127.0.0.1:$port", null, null, okHttpClient(5_000), maxBytes = 1024)
            assertNull(api.readImage("/work/page.png"))
        } finally {
            server.stop(0)
        }
    }

    private fun okHttpClient(timeoutMs: Long) = okhttp3.OkHttpClient.Builder()
        .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
}
