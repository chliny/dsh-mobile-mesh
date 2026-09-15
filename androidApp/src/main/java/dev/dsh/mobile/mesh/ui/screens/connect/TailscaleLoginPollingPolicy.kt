package dev.dsh.mobile.mesh.ui.screens.connect

/** A login URL means tsnet retained a pending identity and the client must poll it automatically. */
internal fun shouldPollTailscaleLogin(
    loginUrl: String?,
    authorizationPending: Boolean,
    pollActive: Boolean,
): Boolean = loginUrl != null && authorizationPending && !pollActive
