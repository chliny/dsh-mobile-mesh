package dev.dsh.mobile.mesh.connection

/** Pending authorization stays visible while one bounded resume attempt is in flight. */
internal fun shouldStartAuthorizationResume(
    authorizationPending: Boolean,
    lifecycleMayRun: Boolean,
    operationInFlight: Boolean,
): Boolean = authorizationPending && lifecycleMayRun && !operationInFlight
