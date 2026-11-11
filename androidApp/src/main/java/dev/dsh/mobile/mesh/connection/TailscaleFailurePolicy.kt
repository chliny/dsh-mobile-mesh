package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.TransportFailure
import dev.dsh.mobile.mesh.core.wire.TransportFailures
import java.io.IOException

/** Free-form tsnet error strings that mean "try again", not "ask the user to act". */
private val TRANSIENT_TAILSCALE_FAILURES = setOf(
    TransportFailure.TIMEOUT,
    TransportFailure.UNREACHABLE,
)

/**
 * True when the Go relay reported a self-healing carrier problem rather than a terminal state.
 *
 * tsnet surfaces transport failures as plain strings, so classify them like the carrier-level
 * exceptions. Authorization and sign-in states deliberately stay false: those need the user.
 */
internal fun isTransientTailscaleFailure(error: String): Boolean =
    TransportFailures.classify(IOException(error)) in TRANSIENT_TAILSCALE_FAILURES ||
        error.contains("context deadline exceeded", ignoreCase = true) ||
        error == "Tailscale relay is no longer available" ||
        error == "Tailscale server is not running"
