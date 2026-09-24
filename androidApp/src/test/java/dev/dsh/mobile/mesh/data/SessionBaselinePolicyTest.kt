package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBaselinePolicyTest {
    @Test
    fun `same host session baseline is reusable`() {
        assertFalse(shouldRefreshForConnectedGeneration("host-a", "host-a"))
    }

    @Test
    fun `new host generation refreshes session baseline`() {
        assertTrue(shouldRefreshForConnectedGeneration("host-b", "host-a"))
    }

    @Test
    fun `unknown host does not trigger a baseline`() {
        assertFalse(shouldRefreshForConnectedGeneration(null, "host-a"))
    }

    @Test
    fun `late old-host result cannot replace current host list`() {
        assertFalse(isCurrentHostResult("generation-a", "generation-b", "host-a", "host-b"))
        assertTrue(isCurrentHostResult("generation-b", "generation-b", "host-b", "host-b"))
    }
}
