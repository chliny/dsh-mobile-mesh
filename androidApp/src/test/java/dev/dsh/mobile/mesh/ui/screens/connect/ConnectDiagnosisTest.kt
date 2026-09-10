package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ProbeOutcome
import dev.dsh.mobile.mesh.core.wire.GenerationFailure
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole point of the probe keeping its reason is that these two lead to opposite advice:
 * a refusal means the harness is bound to loopback, a timeout means something is dropping packets.
 */
class ConnectDiagnosisTest {

    @Test
    fun `probe outcomes map to their own diagnosis`() {
        assertEquals(ConnectFailure.TrustFence, ConnectFailure.from(ProbeOutcome.TrustFence))
        assertEquals(ConnectFailure.Refused, ConnectFailure.from(ProbeOutcome.Refused))
        assertEquals(ConnectFailure.Timeout, ConnectFailure.from(ProbeOutcome.Timeout))
        assertEquals(ConnectFailure.DnsFailure, ConnectFailure.from(ProbeOutcome.DnsFailure))
        assertEquals(ConnectFailure.NotAHarness, ConnectFailure.from(ProbeOutcome.NotAHarness))
        assertEquals(ConnectFailure.TlsFailure, ConnectFailure.from(ProbeOutcome.TlsFailure))
    }

    /** No route is a different-network problem; "nothing answered" is the honest reading. */
    @Test
    fun `unreachable reads as a timeout`() {
        assertEquals(ConnectFailure.Timeout, ConnectFailure.from(ProbeOutcome.Unreachable))
    }

    @Test
    fun `an unclassified probe failure keeps the carrier's words`() {
        assertEquals(
            ConnectFailure.Other("socket closed"),
            ConnectFailure.from(ProbeOutcome.Other("socket closed")),
        )
    }

    @Test
    fun `streams that never open are reported as blocked, not as a dead host`() {
        assertEquals(
            ConnectFailure.StreamsBlocked,
            ConnectFailure.from(GenerationFailure.MuxTimedOut(3_000)),
        )
    }

    @Test
    fun `a stream failure carries its transport kind through`() {
        assertEquals(
            ConnectFailure.TrustFence,
            ConnectFailure.from(GenerationFailure.MuxFailed(TransportFailure.TRUST_FENCE, "403")),
        )
        assertEquals(
            ConnectFailure.Refused,
            ConnectFailure.from(GenerationFailure.MuxFailed(TransportFailure.REFUSED, null)),
        )
        assertEquals(
            ConnectFailure.Timeout,
            ConnectFailure.from(GenerationFailure.MuxFailed(TransportFailure.TIMEOUT, null)),
        )
        assertEquals(
            ConnectFailure.TlsFailure,
            ConnectFailure.from(GenerationFailure.MuxFailed(TransportFailure.TLS, "handshake failed")),
        )
    }

    @Test
    fun `an unclassifiable stream failure falls back to blocked streams`() {
        assertEquals(
            ConnectFailure.StreamsBlocked,
            ConnectFailure.from(GenerationFailure.MuxFailed(TransportFailure.OTHER, null)),
        )
    }

    @Test
    fun `a describe failure reads its kind out of the error details`() {
        val fenced = RpcError(
            code = "forbidden",
            message = "harness trust fence rejected the request (HTTP 403)",
            details = TransportFailures.details(TransportFailure.TRUST_FENCE, 403),
        )
        assertEquals(ConnectFailure.TrustFence, ConnectFailure.from(GenerationFailure.ReadyFailed(fenced)))

        val notHarness = RpcError(
            code = "internal",
            message = "decode failed",
            details = TransportFailures.details(TransportFailure.NOT_A_HARNESS),
        )
        assertEquals(ConnectFailure.NotAHarness, ConnectFailure.from(GenerationFailure.ReadyFailed(notHarness)))
    }

    /** A business error (agent-busy, say) carries no transport marker; keep its message. */
    @Test
    fun `a describe failure with no marker falls back to its message`() {
        val business = RpcError("agent-busy", "the agent is busy", JsonObject(emptyMap()))
        assertEquals(
            ConnectFailure.Other("the agent is busy"),
            ConnectFailure.from(GenerationFailure.ReadyFailed(business)),
        )
    }

}
