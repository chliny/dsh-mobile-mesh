package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChangesRequestDiagnosticsTest {
    @Test fun `404 diagnostic identifies route and event without exposing session or response`() {
        val error = RpcError("capability-unavailable", "private response", TransportFailures.details(TransportFailure.NOT_FOUND, 404))
        val summary = changesRequestDiagnostic("changes.summary", 42, null, error)
        assertEquals("GET /api/changes.summary seq=42 code=capability-unavailable status=404 transport=NOT_FOUND", summary)
        val diff = changesRequestDiagnostic("changes.diff", 42, 3, error)
        assertEquals("GET /api/changes.diff seq=42 index=3 code=capability-unavailable status=404 transport=NOT_FOUND", diff)
        assertFalse(diff.contains("private response"))
    }

    @Test fun `real route 404 means expired data while missing route 404 means unsupported API`() {
        val error = RpcError("capability-unavailable", "carrier returned HTTP 404", TransportFailures.details(TransportFailure.NOT_FOUND, 404))
        assertEquals("changes/resource-unavailable", classifyChangesNotFound(error, RpcResult.Ok(true)).code)
        assertEquals("changes/route-unavailable", classifyChangesNotFound(error, RpcResult.Ok(false)).code)
        assertEquals(error, classifyChangesNotFound(error, RpcResult.Err(RpcError("unauthenticated", "401"))))
        assertEquals(error, classifyChangesNotFound(error, null))
        val unrelated = RpcError("internal", "failed", TransportFailures.details(TransportFailure.OTHER, 500))
        assertEquals(unrelated, classifyChangesNotFound(unrelated, RpcResult.Ok(false)))
    }
}
