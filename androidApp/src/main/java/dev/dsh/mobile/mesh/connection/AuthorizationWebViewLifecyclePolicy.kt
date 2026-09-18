package dev.dsh.mobile.mesh.connection

/**
 * Google sign-in may recreate the Activity while tsnet still owns a pending authorization.
 * Foreground recovery must leave that identity untouched so the root can remount the WebView.
 */
internal fun shouldDeferForegroundRecoveryForAuthorization(
    authorizationPending: Boolean,
    loginUrl: String?,
): Boolean = authorizationPending || loginUrl?.isNotBlank() == true
