package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.ScheduleDeleteRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleHistoryRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleUpdateRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRemoteTest {
    private class Stub(private val value: String, private val failureStatus: Int? = null) : RpcTransport {
        var path = ""
        var args = kotlinx.serialization.json.JsonObject(emptyMap())
        override suspend fun <T> download(path: String, consume: (String?, String?, java.io.InputStream) -> T): T =
            error("download not expected")
        override suspend fun upload(path: String, contentType: String, contentLength: Long,
            body: java.io.InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse =
            error("upload not expected")
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            failureStatus?.let { throw RpcTransportException(it, "carrier refused") }
            this.path = path
            val request = Json.parseToJsonElement(body).jsonObject
            args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"${request.getValue("rpcId").jsonPrimitive.content}","result":{"ok":true,"value":$value}}""")
        }
    }

    @Test fun catalogKeepsHostOrderAndInactiveRows() = runTest {
        val stub = Stub("""[{"id":"a","kind":"daily","title":"Daily","prompt":"Check","scheduledAt":"2026-10-19T00:00:00.000Z","time":"09:00:00.000","timeZone":"Asia/Shanghai","sessionId":"s","status":"inactive"},{"id":"b","kind":"cron","title":"Cron","prompt":"Check","scheduledAt":"2026-10-20T00:00:00.000Z","expression":"0 9 * * *","timeZone":"UTC","sessionId":"s","status":"active"}]""")
        val result = DshApiClient(stub).scheduleCatalog() as RpcResult.Ok
        assertEquals("/api/schedule/catalog", stub.path)
        assertTrue(stub.args.isEmpty())
        assertEquals(listOf("a", "b"), result.value.map { it.id })
        assertEquals("inactive", result.value.first().status)
    }

    @Test fun updateSendsObservedRuleAndOmitsUnchangedFields() = runTest {
        val stub = Stub("""{"id":"a","updated":false,"code":"schedule_conflict"}""")
        val expected = buildJsonObject { put("id", "a"); put("kind", "at"); put("title", "Title") }
        val result = DshApiClient(stub).scheduleUpdate(ScheduleUpdateRequest("s", "a", expected)) as RpcResult.Ok
        assertEquals("schedule_conflict", result.value.code)
        val request = stub.args.getValue("request").jsonObject
        assertEquals(expected, request.getValue("expected"))
        assertFalse(request.containsKey("change"))
        assertFalse(request.containsKey("prompt"))
        assertEquals("s", request.getValue("sessionId").jsonPrimitive.content)
    }

    @Test fun onlyMissingRouteIsUnsupported() = runTest {
        val missing = DshApiClient(Stub("[]", 404)).scheduleCatalog() as RpcResult.Err
        val network = DshApiClient(Stub("[]", 0)).scheduleCatalog() as RpcResult.Err
        assertEquals("capability-unavailable", missing.error.code)
        assertEquals("internal", network.error.code)
    }

    @Test fun historyAndDeleteBindTaskToOriginalSession() = runTest {
        val history = Stub("""{"id":"a","records":[],"earlierRecordsUnavailable":false,"earlierRecordsPruned":false,"retention":{"days":30,"records":100}}""")
        val result = DshApiClient(history).scheduleHistory(ScheduleHistoryRequest("s", "a")) as RpcResult.Ok
        assertEquals(100, result.value.retention?.records)
        assertEquals("/api/schedule/history", history.path)
        assertEquals("s", history.args.getValue("request").jsonObject.getValue("sessionId").jsonPrimitive.content)
        val deletion = Stub("""{"id":"a","deleted":false,"code":"schedule_not_found"}""")
        assertFalse((DshApiClient(deletion).scheduleDelete(ScheduleDeleteRequest("s", "a")) as RpcResult.Ok).value.deleted)
        assertEquals("/api/schedule/delete", deletion.path)
    }
}
