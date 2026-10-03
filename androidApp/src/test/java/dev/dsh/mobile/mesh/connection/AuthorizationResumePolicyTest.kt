package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationResumePolicyTest {
    @Test
    fun `pending eligible authorization starts one resume`() {
        assertTrue(shouldStartAuthorizationResume(true, true, false))
    }

    @Test
    fun `paused lifecycle authorization remains resumable while mayRun is false`() {
        val lifecycle = ConnectionLifecycleCoordinator<String>().apply {
            foreground()
            request("host")
            pauseForAuthorization()
        }
        assertFalse(lifecycle.mayRun())
        assertTrue(shouldStartAuthorizationResume(
            authorizationPending = true,
            lifecycleForegroundOrRetained = lifecycle.mayResumeAuthorization(),
            operationInFlight = false,
        ))
        lifecycle.resumeAfterAuthorization()
        assertTrue(lifecycle.mayRun())
    }

    @Test
    fun `active authorization resume is not restarted by polling`() {
        assertFalse(shouldStartAuthorizationResume(true, true, true))
    }

    @Test
    fun `background-disabled authorization does not resume`() {
        assertFalse(shouldStartAuthorizationResume(true, false, false))
    }

    @Test
    fun `settled authorization does not resume`() {
        assertFalse(shouldStartAuthorizationResume(false, true, false))
    }
}
