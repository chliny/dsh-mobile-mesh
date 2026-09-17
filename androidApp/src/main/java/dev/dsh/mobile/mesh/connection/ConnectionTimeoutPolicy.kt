package dev.dsh.mobile.mesh.connection

internal object ConnectionTimeoutPolicy {
    // Authorization is event-driven by the retained tsnet node; this is only a safety bound for
    // the transport resume after the user has completed sign-in.
    const val authorizationResumeMs = 300_000L
    const val transportReadyCallbackMs = 90_000L
}
