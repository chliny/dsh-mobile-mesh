package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.TerminalCreateRequest
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal unary calls use the exact Remote parameter names and accept omitted void values. */
class DshApiClientTerminalTest {
    private class RecordingTransport(private val value: String? = null) : RpcTransport {
        var path: String? = null
        var body: String? = null

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            this.body = body
            val rpcId = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            val result = if (value == null) "" else ",\"value\":$value"
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true$result}}""")
        }

        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("not used")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("not used")

        fun assertCall(method: String, expectedArgs: String) {
            assertEquals("/api/$method", path)
            val envelope = Json.parseToJsonElement(body!!).jsonObject
            assertEquals("client-request", envelope["type"]!!.jsonPrimitive.content)
            assertEquals(method, envelope["method"]!!.jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(expectedArgs).jsonObject, envelope["payload"]!!.jsonObject["args"]!!.jsonObject)
        }
    }

    private val info = """{"id":"term-1","title":"Bash","shell":{"path":"/bin/bash","args":["-i"],"name":"Bash"},"cwd":"/work","cols":80,"rows":24,"state":"running","exitCode":null,"controllerId":"attach-1"}"""

    @Test
    fun `list addresses session directly and decodes terminal metadata`() = runTest {
        val transport = RecordingTransport("[$info]")
        val result = DshApiClient(transport).terminalList("session-1")
        transport.assertCall("terminal/list", """{"sessionId":"session-1"}""")
        val terminal = (result as RpcResult.Ok).value.single()
        assertEquals("/bin/bash", terminal.shell.path)
        assertEquals(listOf("-i"), terminal.shell.args)
        assertEquals("attach-1", terminal.controllerId)
        assertEquals(null, terminal.exitCode)
    }

    @Test
    fun `environment uses agent lookup and decodes limits`() = runTest {
        val transport = RecordingTransport("""{"cwd":"/work","maxInputBytes":65536,"maxCols":500,"maxRows":200,"scrollback":1000}""")
        val result = DshApiClient(transport).terminalEnvironment("agent-1")
        transport.assertCall("terminal/environment", """{"agentId":"agent-1"}""")
        assertEquals(65536, (result as RpcResult.Ok).value.maxInputBytes)
        assertEquals("/work", result.value.cwd)
    }

    @Test
    fun `create sends nested request without an absent optional shell path`() = runTest {
        val transport = RecordingTransport(info)
        val result = DshApiClient(transport).terminalCreate("agent-1", TerminalCreateRequest("term-1", 80, 24))
        transport.assertCall("terminal/create", """{"agentId":"agent-1","request":{"id":"term-1","cols":80,"rows":24}}""")
        assertEquals("running", (result as RpcResult.Ok).value.state)
        val selected = RecordingTransport(info)
        DshApiClient(selected).terminalCreate("agent-1", TerminalCreateRequest("term-1", 80, 24, "/bin/bash"))
        selected.assertCall("terminal/create", """{"agentId":"agent-1","request":{"id":"term-1","cols":80,"rows":24,"shellPath":"/bin/bash"}}""")
    }

    @Test
    fun `write resize and close preserve their flat args and succeed with omitted values`() = runTest {
        val transport = RecordingTransport()
        val client = DshApiClient(transport)
        val write = client.terminalWrite("agent-1", "term-1", "attach-1", "héllo\t")
        transport.assertCall("terminal/write", """{"agentId":"agent-1","id":"term-1","attachmentId":"attach-1","data":"héllo\t"}""")
        assertTrue(write is RpcResult.Ok)
        assertEquals(JsonObject(emptyMap()), (write as RpcResult.Ok).value)

        val resize = client.terminalResize("agent-1", "term-1", "attach-1", 120, 35)
        transport.assertCall("terminal/resize", """{"agentId":"agent-1","id":"term-1","attachmentId":"attach-1","cols":120,"rows":35}""")
        assertTrue(resize is RpcResult.Ok)

        val close = client.terminalClose("agent-1", "term-1")
        transport.assertCall("terminal/close", """{"agentId":"agent-1","id":"term-1"}""")
        assertTrue(close is RpcResult.Ok)
        assertEquals(JsonObject(emptyMap()), (close as RpcResult.Ok).value)
    }
}
