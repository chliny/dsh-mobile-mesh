package dev.dsh.mobile.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootPagePolicyTest {
    @Test
    fun `session pages require a live connection, not a remembered page`() {
        // Regression: the session list/transcript used to be retained from a rememberSaveable
        // "page was rendered" flag plus the last host id. After an explicit disconnect (and after
        // a process-death restore) Back out of settings landed on the session list, or even the
        // transcript, while nothing was connected.
        assertFalse(shouldShowSessionPages(hasConnected = false, activeHostId = "host-1"))
        assertFalse(shouldShowSessionPages(hasConnected = false, activeHostId = null))
        assertFalse(shouldShowSessionPages(hasConnected = true, activeHostId = null))
        assertTrue(shouldShowSessionPages(hasConnected = true, activeHostId = "host-1"))
    }

    @Test
    fun `a disconnected app cannot resolve to a session page`() {
        // Regression: with the session pages gated only inside the `when`, a remembered
        // showSessionList flag let Back out of settings reopen the stale session list. Even with
        // the connection list not showing, an unconnected app must not render a session page.
        assertEquals(
            AppRootPage.CONNECTIONS,
            resolveAppRootPage(
                connectionsVisible = true,
                settingsVisible = false,
                connectFormVisible = false,
                sessionPagesAvailable = false,
                sessionListRequested = true,
            ),
        )
        assertEquals(
            AppRootPage.NONE,
            resolveAppRootPage(
                connectionsVisible = false,
                settingsVisible = false,
                connectFormVisible = false,
                sessionPagesAvailable = false,
                sessionListRequested = true,
            ),
        )
    }

    @Test
    fun `root pages keep connection and settings precedence over session pages`() {
        // The connection pages stay reachable while connected (settings -> connection list), and
        // the connect form/settings win over a retained session page.
        assertEquals(
            AppRootPage.CONNECTIONS,
            resolveAppRootPage(true, settingsVisible = true, connectFormVisible = true, sessionPagesAvailable = true, sessionListRequested = true),
        )
        assertEquals(
            AppRootPage.SETTINGS,
            resolveAppRootPage(connectionsVisible = false, settingsVisible = true, connectFormVisible = true, sessionPagesAvailable = true, sessionListRequested = true),
        )
        assertEquals(
            AppRootPage.CONNECT_FORM,
            resolveAppRootPage(connectionsVisible = false, settingsVisible = false, connectFormVisible = true, sessionPagesAvailable = true, sessionListRequested = true),
        )
    }

    @Test
    fun `a connected app resolves the remembered session page`() {
        assertEquals(
            AppRootPage.SESSION_LIST,
            resolveAppRootPage(
                connectionsVisible = false,
                settingsVisible = false,
                connectFormVisible = false,
                sessionPagesAvailable = true,
                sessionListRequested = true,
            ),
        )
        assertEquals(
            AppRootPage.MAIN,
            resolveAppRootPage(
                connectionsVisible = false,
                settingsVisible = false,
                connectFormVisible = false,
                sessionPagesAvailable = true,
                sessionListRequested = false,
            ),
        )
    }
}
