package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.dto.HostDescription

/**
 * What answered — or did not — at one `host:port`.
 *
 * [DiscoveryEngine.probe] used to return `HostDescription?`, which made a firewall, a loopback-only
 * bind, a trust-fence rejection and a typo the same event. They need opposite instructions, so the
 * probe keeps the distinction and the connect screen spends it.
 */
sealed interface ProbeOutcome {

    /**
     * A harness answered.
     *
     * [description] is null when the endpoint proved itself but its home directory was not read —
     * on the LAN sweep, which does not spend a second request for a caption, and on any host whose
     * directory listing is unavailable. Reachability and description were one call through 0.1.1;
     * from 0.1.2 they are not, so "reachable" no longer implies "described".
     */
    data class Reachable(val description: HostDescription?) : ProbeOutcome

    /** HTTP 403 — the harness is there and its `Host` trust fence refused this address. */
    data object TrustFence : ProbeOutcome

    /**
     * HTTP 401 — the harness is there, accepted the address, and has no browser session for this
     * client.
     *
     * New with harness 0.1.2, which authenticates the whole `/api` surface against a signed cookie
     * obtained by exchanging a launch token. Deliberately not folded into [TrustFence]: a 403 is
     * about where the request came from and is fixed on the harness, while this is about who is
     * asking and is fixed by exchanging a token from the harness's startup URL.
     */
    data object Unauthenticated : ProbeOutcome


    /** The host answered the network but nothing listens on the port. */
    data object Refused : ProbeOutcome

    /** Nothing answered at all: the packets were dropped rather than refused. */
    data object Timeout : ProbeOutcome

    /** The name did not resolve. */
    data object DnsFailure : ProbeOutcome

    /** No route to the host — usually a different network entirely. */
    data object Unreachable : ProbeOutcome

    /** Something is listening, but it does not speak the harness protocol. */
    data object NotAHarness : ProbeOutcome

    /**
     * The socket opened but the TLS handshake failed — a certificate this phone does not trust,
     * or `https://` aimed at a plain-HTTP server.
     */
    data object TlsFailure : ProbeOutcome

    /** Anything else; [detail] is the carrier's own words, for the fallback message. */
    data class Other(val detail: String) : ProbeOutcome
}

/** Connect/read budgets for a probe. */
data class ProbeTimeouts(val connectMs: Long, val readMs: Long) {
    companion object {
        /**
         * The first pass of a sweep: a bare TCP connect, nothing more.
         *
         * On a /24 almost every address is dead or refuses instantly, and both answers arrive in
         * well under this budget. Only the few that open a socket go on to pay for HTTP, so the
         * deadline that decides the length of a scan is this one — not [Sweep].
         */
        val Knock = ProbeTimeouts(connectMs = 300, readMs = 300)

        /** Sweeping 254 addresses: fail fast, most of them are nothing. */
        val Sweep = ProbeTimeouts(connectMs = 700, readMs = 1_500)

        /**
         * One address the user typed: worth more patience than a sweep entry, but still bounded —
         * this runs behind a progress indicator, not a frozen button.
         */
        val Manual = ProbeTimeouts(connectMs = 2_500, readMs = 4_000)
    }
}
