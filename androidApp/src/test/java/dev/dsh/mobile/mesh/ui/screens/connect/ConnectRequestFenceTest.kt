package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRequestFenceTest {
    @Test
    fun `rapid host switch rejects previous asynchronous result`() {
        val fence = ConnectRequestFence()
        val first = fence.next()
        val second = fence.next()

        assertFalse(fence.accepts(first))
        assertTrue(fence.accepts(second))
    }

    @Test
    fun `request remains current until a replacement is selected`() {
        val fence = ConnectRequestFence()
        val request = fence.next()

        assertTrue(fence.accepts(request))
        assertTrue(fence.accepts(request))
    }
}
