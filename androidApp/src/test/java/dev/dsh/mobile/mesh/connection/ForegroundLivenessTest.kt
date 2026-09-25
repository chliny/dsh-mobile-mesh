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
    fun `ZeroTier probe allows native relay resume budget`() {
        assertTrue(foregroundProbeTimeoutMs(MeshTransport.ZERO_TIER) > foregroundProbeTimeoutMs(MeshTransport.TAILSCALE))
        assertTrue(foregroundProbeTimeoutMs(MeshTransport.ZERO_TIER) > FOREGROUND_PROBE_TIMEOUT_MS)
    }

    @Test
    fun `recovered generation is not published connected until required probe succeeds`() {
        val healthyResponse = RpcResult.Ok(JsonPrimitive(true))
        assertFalse(shouldPublishConnectedAfterForegroundProbe(
            needsProbe = true,
            appInForeground = true,
            carrierOpen = true,
            result = null,
        ))
        assertFalse(shouldPublishConnectedAfterForegroundProbe(
            needsProbe = true,
            appInForeground = false,
            carrierOpen = true,
            result = healthyResponse,
        ))
        assertTrue(shouldPublishConnectedAfterForegroundProbe(
            needsProbe = true,
            appInForeground = true,
            carrierOpen = true,
            result = healthyResponse,
        ))
        assertTrue(shouldPublishConnectedAfterForegroundProbe(
            needsProbe = false,
            appInForeground = false,
            carrierOpen = false,
            result = null,
        ))
    }

    @Test
    fun `connected phase is not authoritative while recovered generation probe is pending`() {
        assertFalse(isConnectionStateAuthoritative(ConnectionPhase.CONNECTED, generationPublished = true, probePending = true))
        assertFalse(isConnectionStateAuthoritative(ConnectionPhase.RECONNECTING, generationPublished = true, probePending = false))
        assertTrue(isConnectionStateAuthoritative(ConnectionPhase.CONNECTED, generationPublished = true, probePending = false))
    }

    @Test
    fun `probe result from background or replaced generation cannot publish connected`() {
        assertFalse(mayPublishAfterForegroundProbe(false, true, true))
        assertFalse(mayPublishAfterForegroundProbe(true, false, true))
        assertFalse(mayPublishAfterForegroundProbe(true, true, false))
        assertTrue(mayPublishAfterForegroundProbe(true, true, true))
    }

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
