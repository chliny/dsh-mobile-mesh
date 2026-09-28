package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobListCompatibilityTest {
    @Test
    fun `invocation unavailable for exact job list endpoint enables old control fallback`() {
        val error = RpcError(
            code = "gateway/invocation-unavailable",
            message = "no active Remote method exports this endpoint",
            details = buildJsonObject { put("endpoint", "job/list") },
        )
        assertTrue(isUnsupportedJobListFailure(error, carrierFailure = false))
    }

    @Test
    fun `does not fall back for another endpoint or absent endpoint details`() {
        assertFalse(isUnsupportedJobListFailure(
            RpcError("gateway/invocation-unavailable", "missing", buildJsonObject { put("endpoint", "job/follow") }),
            carrierFailure = false,
        ))
        assertFalse(isUnsupportedJobListFailure(
            RpcError("gateway/invocation-unavailable", "missing"),
            carrierFailure = false,
        ))
    }

    @Test
    fun `does not fall back on carrier or unrelated structured failures`() {
        val details = buildJsonObject { put("endpoint", "job/list") }
        assertFalse(isUnsupportedJobListFailure(RpcError("gateway/invocation-unavailable", "missing", details), carrierFailure = true))
        assertFalse(isUnsupportedJobListFailure(RpcError("gateway/method-unavailable", "broken method", details), carrierFailure = false))
        assertFalse(isUnsupportedJobListFailure(RpcError("session/not-found", "missing", details), carrierFailure = false))
    }
}
