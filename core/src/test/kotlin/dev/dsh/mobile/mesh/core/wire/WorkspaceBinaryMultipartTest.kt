package dev.dsh.mobile.mesh.core.wire

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A workspace read that carries native bytes, exercised against the transport a real Host speaks.
 *
 * Harness 0.2.0 answers a Remote result containing `Uint8Array` as `multipart/form-data` instead of
 * JSON. The app's Markdown images and file previews read workspace bytes, so a client that only
 * understood JSON envelopes silently lost every image; this drives the real HTTP carrier with the
 * exact multipart shape the Host writes (metadata envelope plus raw part) and asserts the bytes
 * arrive intact.
 */
class WorkspaceBinaryMultipartTest {

    @Test
    fun `workspace image bytes arrive intact from a multipart host response`() = runBlocking<Unit> {
        // Arbitrary byte values, including a delimiter-looking run that is not at a line start.
        val bytes = ByteArray(512) { (it * 37 % 251).toByte() }.also { payload ->
            "------dsh-boundary\r\npublished".toByteArray(Charsets.UTF_8).copyInto(payload, 64)
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/workspaceFiles/readBytes") { exchange ->
            val request = WireJson.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            val args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals("session-image", args.getValue("workspaceFileScopeId").jsonPrimitive.content)
            assertEquals("/work/screens/shot.png", args.getValue("path").jsonPrimitive.content)
            val rpcId = request.getValue("rpcId").jsonPrimitive.content
            val metadata = """
                {"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":{
                  "absolutePath":"/work/screens/shot.png","version":"v1","bytes":${bytes.size},
                  "offset":0,"data":null,"eof":true}},
                  "attachments":[{"path":["data"],"codec":"bytes","part":"bytes-0"}]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val body = multipart("----host-boundary", metadata, bytes)
            exchange.responseHeaders.add("Content-Type", "multipart/form-data; boundary=----host-boundary")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val api = DshApiClient(
                OkHttpRpcTransport("http://127.0.0.1:${server.address.port}", connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
            )
            val value = (api.workspaceFilesReadBytes("session-image", "/work/screens/shot.png", length = 4096) as RpcResult.Ok).value
            assertEquals(0, value.offset)
            assertTrue(value.eof)
            assertArrayEquals(bytes, value.data)
        } finally {
            server.stop(0)
        }
    }

    /** One multipart body in the order the Host writes it: the binary part first, metadata last. */
    private fun multipart(boundary: String, metadata: ByteArray, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun part(name: String, contentType: String, content: ByteArray) {
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Disposition: form-data; name=\"$name\"; filename=\"blob\"\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(content)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        part("bytes-0", "application/octet-stream", bytes)
        part("metadata", "application/json", metadata)
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
