package dev.dsh.mobile.mesh.ui

/** Which page the application root renders. */
internal enum class AppRootPage {
    CONNECTIONS,
    SETTINGS,
    CONNECT_FORM,
    SESSION_LIST,
    MAIN,
    NONE,
}

/**
 * The single source of truth for the root page, evaluated in precedence order.
 *
 * Session list/transcript are gated by [sessionPagesAvailable] here rather than at the call site:
 * they read live state from the connected host, so a cold start, a process-death restore or an
 * explicit disconnect must land on the connection list instead of remembered session content.
 *
 * The recovery scrim also keys its system-Back blocker on this value, so a page cannot be added to
 * the root without also taking part in the scrim's re-registration order.
 */
internal fun resolveAppRootPage(
    connectionsVisible: Boolean,
    settingsVisible: Boolean,
    connectFormVisible: Boolean,
    sessionPagesAvailable: Boolean,
    sessionListRequested: Boolean,
): AppRootPage = when {
    connectionsVisible -> AppRootPage.CONNECTIONS
    settingsVisible -> AppRootPage.SETTINGS
    connectFormVisible -> AppRootPage.CONNECT_FORM
    sessionPagesAvailable && sessionListRequested -> AppRootPage.SESSION_LIST
    sessionPagesAvailable -> AppRootPage.MAIN
    else -> AppRootPage.NONE
}

/**
 * Session list/content read live state from the connected host, so they exist only while that
 * connection is owned.
 *
 * [hasConnected] is sticky through a transient reconnect or lifecycle verification — that is what
 * keeps the transcript behind the recovery scrim instead of flashing the connection list. A cold
 * start, a process-death restore, or an explicit disconnect leaves no live connection, and a
 * remembered page flag must not reopen stale session content in those states: the app belongs on
 * the connection list until a connection is established.
 */
internal fun shouldShowSessionPages(hasConnected: Boolean, activeHostId: String?): Boolean =
    hasConnected && activeHostId != null

internal fun shouldRouteAfterRecovery(
    keepingExistingPage: Boolean,
    explicitSelectionPending: Boolean,
): Boolean = !keepingExistingPage || explicitSelectionPending
