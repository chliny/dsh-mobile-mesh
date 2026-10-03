package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.GenerationFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RemoteStreamException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierRecoveryPolicyTest {
    @Test
    fun `ZeroTier-only generation failures do not churn a healthy relay`() {
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = 1))
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = true, recoveryInFlight = false, failedAttempt = 1))
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = false, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = ZERO_TIER_RENEW_AFTER_FAILURES))
    }

    @Test
    fun `host authentication response never renews ZeroTier carrier`() {
        assertFalse(loopFailureCanRenewCarrier(GenerationFailure.MuxFailed(TransportFailure.UNAUTHENTICATED, "HTTP 401")))
        assertTrue(loopFailureCanRenewCarrier(GenerationFailure.MuxTimedOut(3_000)))
    }

    @Test
    fun `ready timeout can renew a persistently stale ZeroTier relay`() {
        assertTrue(loopFailureCanRenewCarrier(GenerationFailure.ReadyFailed(RpcError("internal", "no ready frame within 5000ms"))))
        assertFalse(
            loopFailureCanRenewCarrier(
                GenerationFailure.ReadyFailed(
                    RpcError(
                        "unauthenticated",
                        "expired",
                        JsonObject(mapOf("transport" to JsonPrimitive(TransportFailure.UNAUTHENTICATED.name))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `host readiness timeout does not immediately recycle the local relay`() {
        assertFalse(shouldRenewCarrierAfterLoopFailure(
            sshEnabled = false,
            networkRecoveryPending = false,
            recoveryInFlight = false,
            failedAttempt = 1,
            isCarrierFailure = false,
        ))
    }

    @Test
    fun `ready handshake timeout without a carrier cause does not recycle ZeroTier`() {
        assertFalse(loopFailureIsCarrierFailure(null))
        assertFalse(shouldRenewCarrierAfterLoopFailure(
            sshEnabled = false,
            networkRecoveryPending = false,
            recoveryInFlight = false,
            failedAttempt = ZERO_TIER_RENEW_AFTER_FAILURES,
            isCarrierFailure = loopFailureIsCarrierFailure(null),
        ))
    }

    @Test
    fun `raw mux close cause identifies a physical carrier loss`() {
        assertTrue(loopFailureIsCarrierFailure(java.io.IOException("socket closed")))
    }

    @Test
    fun `physical websocket EOF is classified as carrier failure for renewal`() {
        val failure = RemoteStreamException(
            RpcError("internal", "remote stream carrier failed", JsonObject(mapOf("transport" to JsonPrimitive(TransportFailure.PEER_CLOSED.name)))),
            carrier = true,
        )
        assertTrue(loopFailureIsCarrierFailure(failure))
        assertTrue(shouldRenewCarrierAfterLoopFailure(
            sshEnabled = false,
            networkRecoveryPending = false,
            recoveryInFlight = false,
            failedAttempt = ZERO_TIER_RENEW_AFTER_FAILURES,
            isCarrierFailure = loopFailureIsCarrierFailure(failure),
        ))
    }

    @Test
    fun `logical stream failure does not identify a dead socket`() {
        val hostError = RemoteStreamException(RpcError("internal", "host stream ended"), carrier = false)
        assertFalse(loopFailureIsCarrierFailure(hostError))
    }

    @Test
    fun `carrier ping timeout renews the ZeroTier relay after threshold`() {
        val timeout = RemoteStreamException(
            RpcError("internal", "ping timeout", JsonObject(mapOf("transport" to JsonPrimitive(TransportFailure.TIMEOUT.name)))),
            carrier = true,
        )
        assertTrue(loopFailureIsCarrierFailure(timeout))
        assertTrue(shouldRenewCarrierAfterLoopFailure(
            sshEnabled = false,
            networkRecoveryPending = false,
            recoveryInFlight = false,
            failedAttempt = ZERO_TIER_RENEW_AFTER_FAILURES,
            isCarrierFailure = loopFailureIsCarrierFailure(timeout),
        ))
    }

    @Test
    fun `SSH termination or network handover can renew the carrier`() {
        assertTrue(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = false, failedAttempt = 1))
        assertFalse(shouldRenewCarrierAfterLoopFailure(sshEnabled = true, networkRecoveryPending = false, recoveryInFlight = true, failedAttempt = 1))
    }
}
