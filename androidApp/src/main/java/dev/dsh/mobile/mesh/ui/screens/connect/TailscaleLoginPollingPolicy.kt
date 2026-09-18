package dev.dsh.mobile.mesh.ui.screens.connect

/** A native login URL is authoritative: show it until the URL is cleared after authorization or dismissal. */
internal fun shouldShowTailscaleLogin(loginUrl: String?, authorizationPending: Boolean): Boolean =
    loginUrl?.isNotBlank() == true

/** A login URL means tsnet retained a pending identity and the client must poll it automatically. */
internal fun shouldPollTailscaleLogin(
    loginUrl: String?,
    authorizationPending: Boolean,
    pollActive: Boolean,
): Boolean = shouldShowTailscaleLogin(loginUrl, authorizationPending) && authorizationPending && !pollActive
