package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRequestFenceTest {
    @Test
    fun `late preview result and completion cannot overwrite a newer request`() {
        val fence = PreviewRequestFence()
        val first = fence.next()
        val second = fence.next()
        var visible = "loading second"
        assertFalse(fence.runIfCurrent(first) { visible = "stale first result" })
        assertFalse(fence.runIfCurrent(first) { visible = "stale first completion" })
        assertEquals("loading second", visible)
        assertTrue(fence.runIfCurrent(second) { visible = "second result" })
        assertEquals("second result", visible)
    }

    @Test
    fun `reset invalidates in-flight preview even when workspace key is reused`() {
        val fence = PreviewRequestFence()
        val pending = fence.next()
        fence.next()
        var changed = false
        assertFalse(fence.runIfCurrent(pending) { changed = true })
        assertFalse(changed)
    }
}
