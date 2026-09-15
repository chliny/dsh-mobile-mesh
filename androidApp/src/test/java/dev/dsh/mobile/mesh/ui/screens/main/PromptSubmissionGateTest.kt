package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSubmissionGateTest {
    @Test
    fun `same session and text cannot be submitted twice concurrently`() {
        val gate = PromptSubmissionGate()
        assertTrue(gate.tryAcquire("s1", "hello"))
        assertFalse(gate.tryAcquire("s1", "hello"))
    }

    @Test
    fun `different sessions or text remain independent`() {
        val gate = PromptSubmissionGate()
        assertTrue(gate.tryAcquire("s1", "hello"))
        assertTrue(gate.tryAcquire("s1", "world"))
        assertTrue(gate.tryAcquire("s2", "hello"))
    }

    @Test
    fun `release permits a later retry`() {
        val gate = PromptSubmissionGate()
        assertTrue(gate.tryAcquire("s1", "hello"))
        gate.release("s1", "hello")
        assertTrue(gate.tryAcquire("s1", "hello"))
    }
}
