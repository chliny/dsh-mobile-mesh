package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLifecycleCoordinatorTest {
    @Test
    fun `latest target invalidates previous startup`() {
        val coordinator = foregroundCoordinator()
        val first = coordinator.request("A")
        val second = coordinator.request("B")
        assertFalse(coordinator.accepts(first.token))
        assertTrue(coordinator.accepts(second.token))
    }

    @Test
    fun `background suspension preserves target but invalidates operation`() {
        val coordinator = foregroundCoordinator()
        val startup = coordinator.request("A")
        val suspended = coordinator.background()
        assertFalse(coordinator.accepts(startup.token))
        assertFalse(coordinator.accepts(suspended!!.token))
        val resumed = coordinator.foreground()
        assertTrue(coordinator.accepts(resumed!!.token))
    }

    @Test
    fun `background retention keeps operation eligible`() {
        val coordinator = foregroundCoordinator()
        coordinator.setRetainInBackground(true)
        val startup = coordinator.request("A")
        coordinator.background()
        assertTrue(coordinator.accepts(startup.token))
    }

    @Test
    fun `duplicate foreground signal does not invalidate active startup`() {
        val coordinator = foregroundCoordinator()
        val startup = coordinator.request("A")
        coordinator.foreground()
        assertTrue(coordinator.accepts(startup.token))
    }

    @Test
    fun `disconnect prevents foreground auto restart`() {
        val coordinator = foregroundCoordinator()
        coordinator.request("A")
        coordinator.background()
        coordinator.disconnect()
        assertNull(coordinator.foreground())
    }

    @Test
    fun `foreground exposes pending target after request made while backgrounded`() {
        val coordinator = ConnectionLifecycleCoordinator<String>()
        val requested = coordinator.request("gmk.tailscale.chliny.me")
        assertFalse(coordinator.accepts(requested.token))
        val resumed = coordinator.foreground()
        assertTrue(resumed!!.value == "gmk.tailscale.chliny.me")
        assertTrue(coordinator.accepts(resumed.token))
    }

    @Test
    fun `retry invalidates failed operation without changing target`() {
        val coordinator = foregroundCoordinator()
        val failed = coordinator.request("A")
        val retry = coordinator.retryToken()
        assertFalse(coordinator.accepts(failed.token))
        assertTrue(coordinator.accepts(retry!!.token))
        assertTrue(retry.value == "A")
    }

    private fun foregroundCoordinator() = ConnectionLifecycleCoordinator<String>().apply { foreground() }
}
