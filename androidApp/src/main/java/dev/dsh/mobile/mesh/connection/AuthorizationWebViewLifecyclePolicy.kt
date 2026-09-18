package dev.dsh.mobile.mesh.connection

/**
 * Google sign-in may recreate the Activity while tsnet still owns a pending authorization.
 * Foreground/background recovery must leave that identity untouched so the root can remount the WebView.
 */
internal fun shouldDeferForegroundRecoveryForAuthorization(
    authorizationPending: Boolean,
    loginUrl: String?,
): Boolean = authorizationPending || loginUrl?.isNotBlank() == true

/** Do not tear down a pending tsnet identity while an external sign-in activity is foreground. */
internal fun shouldPreserveAuthorizationOnBackground(
    authorizationPending: Boolean,
    loginUrl: String?,
): Boolean = shouldDeferForegroundRecoveryForAuthorization(authorizationPending, loginUrl)

/** A pending native identity should be checked immediately when returning from external sign-in. */
internal fun shouldResumeAuthorizationOnForeground(
    authorizationPending: Boolean,
    loginUrl: String?,
): Boolean = authorizationPending && loginUrl?.isNotBlank() == true
