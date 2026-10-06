package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleClaimTest {
    @Test
    fun `claim returns the published value`() {
        val claim = SingleClaim<String>()
        claim.publish("ready")
        assertEquals("ready", claim.claim())
    }

    @Test
    fun `only the first claim receives the value`() {
        val claim = SingleClaim<String>()
        claim.publish("ready")
        assertEquals("ready", claim.claim())
        assertNull(claim.claim())
    }

    @Test
    fun `a value published after the claim is released instead of leaked`() {
        val released = mutableListOf<String>()
        val claim = SingleClaim<String> { released.add(it) }
        assertNull(claim.claim())
        claim.publish("late")
        assertEquals(listOf("late"), released)
    }

    @Test
    fun `the slower backup is released after the winning socket is adopted`() {
        val released = mutableListOf<String>()
        val claim = SingleClaim<String> { released.add(it) }
        claim.publish("winner")
        assertEquals("winner", claim.claim())
        claim.publish("late-backup")
        assertEquals(listOf("late-backup"), released)
    }

    @Test
    fun `a superseded value is released and never claimed`() {
        val released = mutableListOf<String>()
        val claim = SingleClaim<String> { released.add(it) }
        claim.publish("first")
        claim.publish("second")
        assertEquals(listOf("first"), released)
        assertEquals("second", claim.claim())
    }

    @Test
    fun `claiming before publishing leaves the later value released`() {
        val released = mutableListOf<String>()
        val claim = SingleClaim<String> { released.add(it) }
        assertNull(claim.claim())
        claim.publish("late")
        assertEquals(listOf("late"), released)
        assertNull(claim.claim())
    }
}