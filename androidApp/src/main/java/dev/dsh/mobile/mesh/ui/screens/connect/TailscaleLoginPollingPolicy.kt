package dev.dsh.mobile.mesh.ui.screens.connect

/** A login URL means tsnet retained a pending identity and the client must poll it automatically. */
internal fun shouldPollTailscaleLogin(loginUrl: String?, pollActive: Boolean): Boolean =
    loginUrl != null && !pollActive
