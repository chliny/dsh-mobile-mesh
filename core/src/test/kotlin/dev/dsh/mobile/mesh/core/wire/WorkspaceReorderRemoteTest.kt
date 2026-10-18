package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceInsertBeforeRequest
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReorderRemoteTest {
    private class RecordingTransport : RpcTransport {
        var path: String? = null
        var body: String? = null

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            this.body = body
            val id = Json.parseToJsonElement(body).jsonObject["rpcId"]!!.jsonPrimitive.content
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":{"workspaceIds":["b","a"]}}}""")
        }

        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("unexpected download")

        override suspend fun upload(
            path: String,
            contentType: String,
            contentLength: Long,
            body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("unexpected upload")
    }

    @Test fun `workspace reorder calls Host and decodes committed order`() = runTest {
        val transport = RecordingTransport()
        val result = DshApiClient(transport = transport)
            .workspaceInsertBefore(WorkspaceInsertBeforeRequest("b", "a"))
        assertEquals("/api/workspace/insertBefore", transport.path)
        val envelope = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("workspace/insertBefore", envelope["method"]!!.jsonPrimitive.content)
        val request = envelope["payload"]!!.jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertEquals("b", request["workspaceId"]!!.jsonPrimitive.content)
        assertEquals("a", request["beforeWorkspaceId"]!!.jsonPrimitive.content)
        assertEquals(listOf("b", "a"), (result as RpcResult.Ok).value.workspaceIds)
    }

    @Test fun `append omits optional anchor for Host compatibility`() = runTest {
        val transport = RecordingTransport()
        DshApiClient(transport = transport).workspaceInsertBefore(WorkspaceInsertBeforeRequest("b"))
        val request = Json.parseToJsonElement(transport.body!!).jsonObject["payload"]!!
            .jsonObject["args"]!!.jsonObject["request"]!!.jsonObject
        assertFalse(request.containsKey("beforeWorkspaceId"))
        assertTrue(request.containsKey("workspaceId"))
    }
}
