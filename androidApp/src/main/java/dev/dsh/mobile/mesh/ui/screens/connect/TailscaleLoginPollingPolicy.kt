package dev.dsh.mobile.mesh.ui.screens.connect

/** The login page is visible only while the retained tsnet identity still needs authorization. */
internal fun shouldShowTailscaleLogin(loginUrl: String?, authorizationPending: Boolean): Boolean =
    loginUrl != null && authorizationPending

/** A login URL means tsnet retained a pending identity and the client must poll it automatically. */
internal fun shouldPollTailscaleLogin(
    loginUrl: String?,
    authorizationPending: Boolean,
    pollActive: Boolean,
): Boolean = shouldShowTailscaleLogin(loginUrl, authorizationPending) && !pollActive
