package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedSessionOrderTest {
    @Test fun `pinned section follows server order and omits nested or invisible rows`() {
        assertEquals(
            listOf("b", "a"),
            pinnedRootIds(listOf("b", "missing", "child", "a"), setOf("a", "b", "child"), setOf("child")),
        )
    }
}
