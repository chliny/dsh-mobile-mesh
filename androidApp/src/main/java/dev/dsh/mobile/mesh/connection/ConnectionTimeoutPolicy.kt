package dev.dsh.mobile.mesh.connection

internal object ConnectionTimeoutPolicy {
    const val authorizationResumeMs = 90_000L
    const val transportReadyCallbackMs = 90_000L
}
