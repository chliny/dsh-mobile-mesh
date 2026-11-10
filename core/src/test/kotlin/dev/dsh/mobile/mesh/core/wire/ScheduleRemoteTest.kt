package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCreateRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleTarget
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleDeleteRequest
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditPatch
import dev.dsh.mobile.mesh.core.wire.dto.ScheduleEditRequest
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
import org.junit.Assert.assertNull
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

    @Test fun catalogDecodesBothHostGenerations() = runTest {
        // One row from a pre-0.2.2 Host (flat session binding, inactive) beside one from a
        // 0.2.2 Host (delivery target, paused status, revision, rrule timing). Insight into
        // whether the app can render reminders across an upgrade: both shapes arrive in one list.
        val stub = Stub(
            """[
                {"id":"legacy","kind":"daily","title":"Legacy","prompt":"Check","scheduledAt":"2026-10-19T00:00:00.000Z","time":"09:00:00.000","timeZone":"UTC","sessionId":"old-session","status":"inactive"},
                {"id":"modern","kind":"rrule","title":"Modern","prompt":"Check","scheduledAt":"2026-11-09T00:00:00.000Z","rrule":"DTSTART:20261109T000000Z\nRRULE:FREQ=DAILY","status":"paused","updatedAt":"2026-11-09T01:00:00.000Z",
                 "target":{"kind":"new_once","sessionId":"reserved-session"},"involvedSessionIds":["creator-session","reserved-session"],"lastDelivery":{"scheduledAt":"2026-11-08T00:00:00.000Z","deliveredAt":"2026-11-08T00:00:01.000Z","messageId":"m1","targetSessionId":"reserved-session"}}
            ]""",
        )
        val rows = (DshApiClient(stub).scheduleCatalog() as RpcResult.Ok).value
        assertEquals("old-session", rows[0].deliverySessionId)
        assertEquals(false, rows[0].enabled)
        assertEquals("reserved-session", rows[1].deliverySessionId)
        assertEquals(false, rows[1].enabled)
        assertEquals("2026-11-09T01:00:00.000Z", rows[1].updatedAt)
        assertEquals("RRULE:FREQ=DAILY", rows[1].rrule!!.lines().last())
        assertEquals("reserved-session", rows[1].lastDelivery!!.targetSessionId)
    }

    @Test fun editSendsOneClosedOperationWithRevision() = runTest {
        val stub = Stub("""{"id":"a","code":"schedule_conflict"}""")
        val result = DshApiClient(stub).scheduleEdit(
            ScheduleEditRequest(
                id = "a",
                action = "update",
                patch = ScheduleEditPatch(title = "Renamed"),
                expectedUpdatedAt = "2026-11-09T01:00:00.000Z",
            ),
        ) as RpcResult.Ok
        assertEquals("/api/schedule/edit", stub.path)
        assertEquals("schedule_conflict", result.value.missCode)
        assertFalse(result.value.accepted)
        val request = stub.args.getValue("request").jsonObject
        assertEquals("update", request.getValue("action").jsonPrimitive.content)
        assertEquals("Renamed", request.getValue("patch").jsonObject.getValue("title").jsonPrimitive.content)
        assertFalse(request.getValue("patch").jsonObject.containsKey("prompt"))
        assertEquals("2026-11-09T01:00:00.000Z", request.getValue("expectedUpdatedAt").jsonPrimitive.content)
    }

    @Test fun editDecodesCommittedRowsAndDeleteReceipts() = runTest {
        val committed = Stub(
            """{"id":"a","kind":"daily","title":"Renamed","prompt":"Check","scheduledAt":"2026-11-10T00:00:00.000Z","time":"09:00:00.000","timeZone":"UTC","status":"active","target":{"kind":"session","sessionId":"s"},"involvedSessionIds":["s"],"updatedAt":"2026-11-09T02:00:00.000Z"}""",
        )
        val updated = (DshApiClient(committed).scheduleEdit(
            ScheduleEditRequest("a", "update", ScheduleEditPatch(prompt = "Check")),
        ) as RpcResult.Ok).value
        assertTrue(updated.accepted)
        assertNull(updated.missCode)

        val deleted = Stub("""{"id":"a","deleted":true}""")
        val receipt = (DshApiClient(deleted).scheduleEdit(ScheduleEditRequest("a", "delete")) as RpcResult.Ok).value
        assertTrue(receipt.accepted)
        assertEquals(true, receipt.deleted)
    }

    @Test fun createPauseAndRunUseHostManagementRoutes() = runTest {
        val create = Stub("""{"id":"a","kind":"rrule","title":"Daily","prompt":"Check","scheduledAt":"2026-11-10T00:00:00Z","status":"active"}""")
        val timing = buildJsonObject { put("kind", "rrule"); put("rrule", "DTSTART:20261110T000000Z\nRRULE:FREQ=DAILY") }
        val created = DshApiClient(create).scheduleCreate(ScheduleCreateRequest("Daily", "Check", timing, ScheduleTarget("new_once"))) as RpcResult.Ok
        assertEquals("a", created.value.id)
        assertEquals("/api/schedule/create", create.path)
        assertEquals("new_once", create.args.getValue("request").jsonObject.getValue("target").jsonObject.getValue("kind").jsonPrimitive.content)

        val pause = Stub("""{"id":"a","kind":"rrule"}""")
        DshApiClient(pause).scheduleEdit(ScheduleEditRequest("a", "update", ScheduleEditPatch(active = false), "revision"))
        val request = pause.args.getValue("request").jsonObject
        assertEquals("false", request.getValue("patch").jsonObject.getValue("active").jsonPrimitive.content)
        assertEquals("revision", request.getValue("expectedUpdatedAt").jsonPrimitive.content)

        val run = Stub("""{"id":"a","code":"schedule_execution_pending"}""")
        val pending = DshApiClient(run).scheduleRun("a") as RpcResult.Ok
        assertEquals("schedule_execution_pending", pending.value.code)
        assertEquals("/api/schedule/run", run.path)
        val clear = Stub("""{"deleted":2}""")
        assertEquals(2, (DshApiClient(clear).scheduleDeleteInactive() as RpcResult.Ok).value.deleted)
        assertEquals("/api/schedule/deleteInactive", clear.path)
    }

    @Test fun onlyMissingRouteIsUnsupported() = runTest {
        val missing = DshApiClient(Stub("[]", 404)).scheduleCatalog() as RpcResult.Err
        val network = DshApiClient(Stub("[]", 0)).scheduleCatalog() as RpcResult.Err
        assertEquals("capability-unavailable", missing.error.code)
        assertEquals("internal", network.error.code)
    }

    @Test fun deliveryLimitsReadTheHostNewSessionInterval() = runTest {
        val stub = Stub("""{"newSessionIntervalSeconds":60}""")
        val value = (DshApiClient(stub).scheduleDeliveryLimits() as RpcResult.Ok).value
        assertEquals("/api/schedule/deliveryLimits", stub.path)
        assertEquals(60L, value.newSessionIntervalSeconds)
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
