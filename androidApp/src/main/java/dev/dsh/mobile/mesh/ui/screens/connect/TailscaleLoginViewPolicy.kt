package dev.dsh.mobile.mesh.ui.screens.connect

/** Do not reset a WebView after Tailscale redirects it during authentication. */
internal fun shouldLoadTailscaleLoginUrl(currentUrl: String?): Boolean = currentUrl.isNullOrBlank()
