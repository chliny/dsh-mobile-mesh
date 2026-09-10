package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ProbeOutcome
import dev.dsh.mobile.mesh.core.wire.GenerationFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures

/**
 * Why a connection attempt did not succeed, at the level a person can act on.
 *
 * One step above [ProbeOutcome]: the probe knows what the socket did, this knows what to tell
 * someone standing between a phone and a computer. Deliberately free of Android imports so the whole
 * mapping is unit-testable — the app's tests are plain JVM, with no Robolectric.
 */
sealed interface ConnectFailure {

    /** The address or port was not usable as typed. */
    data object InvalidInput : ConnectFailure

    /** The address is not on this phone's own /24, so nothing here can reach it. */
    data class DifferentSubnet(val localPrefix: String?) : ConnectFailure

    /** Nothing answered — dropped packets. Firewall, or a router isolating wireless clients. */
    data object Timeout : ConnectFailure

    /** Actively refused — the computer is there, the harness is not listening on that port. */
    data object Refused : ConnectFailure

    /** The harness answered and its `Host` trust fence rejected this address. */
    data object TrustFence : ConnectFailure

    /**
     * The harness answered and has no browser session for this client (HTTP 401).
     *
     * Harness 0.1.2 authenticates its whole `/api` surface, so a device that has not exchanged a
     * launch token is refused before any method runs. Separate from [TrustFence] because the fix
     * is on the phone (exchange a token from the harness's startup URL) rather than in the
     * harness's trusted-host list.
     */
    data object Unauthenticated : ConnectFailure

    /** The name did not resolve on this network. */
    data object DnsFailure : ConnectFailure

    /** Something is listening, but it is not a harness. */
    data object NotAHarness : ConnectFailure

    /** The TLS handshake failed — an untrusted certificate, or `https://` to a plain-HTTP server. */
    data object TlsFailure : ConnectFailure

    /** The API answered but the event streams would not open. */
    data object StreamsBlocked : ConnectFailure

    /** Anything else; [detail] is the carrier's own words. */
    data class Other(val detail: String) : ConnectFailure

    companion object {

        /** Map a pre-flight probe outcome. */
        fun from(outcome: ProbeOutcome): ConnectFailure = when (outcome) {
            is ProbeOutcome.Reachable -> Other("")
            ProbeOutcome.TrustFence -> TrustFence
            ProbeOutcome.Unauthenticated -> Unauthenticated
            ProbeOutcome.Refused -> Refused
            ProbeOutcome.Timeout -> Timeout
            ProbeOutcome.DnsFailure -> DnsFailure
            // No route is a different-network problem; the subnet pre-check catches most of these
            // first, and when it does not, "nothing answered" is the honest reading.
            ProbeOutcome.Unreachable -> Timeout
            ProbeOutcome.NotAHarness -> NotAHarness
            ProbeOutcome.TlsFailure -> TlsFailure
            is ProbeOutcome.Other -> Other(outcome.detail)
        }

        /** Map a failure from inside the connection loop's readiness handshake. */
        fun from(failure: GenerationFailure): ConnectFailure = when (failure) {
            is GenerationFailure.MuxTimedOut -> StreamsBlocked
            is GenerationFailure.MuxFailed -> fromKind(failure.kind, failure.message, StreamsBlocked)
            // The ready frame replaced `host.describe` as the last handshake step, so this is where
            // "reached it, could not finish" now lands.
            is GenerationFailure.ReadyFailed -> fromKind(
                TransportFailures.of(failure.error),
                failure.error.message,
                Other(failure.error.message),
            )
        }

        private fun fromKind(
            kind: TransportFailure?,
            message: String?,
            fallback: ConnectFailure,
        ): ConnectFailure = when (kind) {
            TransportFailure.TRUST_FENCE -> TrustFence
            TransportFailure.UNAUTHENTICATED -> Unauthenticated
            TransportFailure.REFUSED -> Refused
            TransportFailure.TIMEOUT, TransportFailure.UNREACHABLE -> Timeout
            TransportFailure.DNS -> DnsFailure
            TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS -> NotAHarness
            TransportFailure.TLS -> TlsFailure
            TransportFailure.OTHER -> message?.takeIf { it.isNotBlank() }?.let { Other(it) } ?: fallback
            null -> fallback
        }
    }
}
