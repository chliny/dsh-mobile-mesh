package dev.dsh.mobile.mesh.ui.screens.connect

/** The system dialog back action is the explicit cancel path for an unfinished Tailscale login. */
internal fun shouldCancelTailscaleLoginOnBack(
    loginVisible: Boolean,
    authorizationPending: Boolean,
): Boolean = loginVisible && authorizationPending
