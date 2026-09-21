package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract: only an actual carrier failure should rebuild mesh and SSH. */
class ForegroundLivenessTest {
    @Test
    fun `successful endpoint probe keeps healthy carrier`() {
        assertTrue(foregroundProbeReachedHost(true, RpcResult.Ok(JsonPrimitive(true))))
    }

    @Test
    fun `authenticated protocol error still proves endpoint reachability`() {
        val error = RpcError("forbidden", "denied", TransportFailures.details(TransportFailure.TRUST_FENCE, 403))
        assertTrue(foregroundProbeReachedHost(true, RpcResult.Err(error)))
    }

    @Test
    fun `business error without transport marker still proves endpoint reachability`() {
        assertTrue(foregroundProbeReachedHost(true, RpcResult.Err(RpcError("busy", "busy"))))
    }

    @Test
    fun `transport timeout fails foreground probe`() {
        val error = RpcError("internal", "timeout", TransportFailures.details(TransportFailure.TIMEOUT))
        assertFalse(foregroundProbeReachedHost(true, RpcResult.Err(error)))
    }

    @Test
    fun `closed carrier or missing result fails foreground probe`() {
        assertFalse(foregroundProbeReachedHost(true, null))
        assertFalse(foregroundProbeReachedHost(false, RpcResult.Ok(JsonPrimitive(true))))
    }

    @Test
    fun `unclassified protocol error does not tear down an open carrier`() {
        assertTrue(foregroundProbeReachedHost(true, RpcResult.Err(RpcError("internal", "timeout"))))
    }
}
