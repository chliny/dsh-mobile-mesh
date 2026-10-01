package dev.dsh.mobile.mesh.connection

import dev.dsh.mobile.mesh.core.wire.WebSocketClosedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeTransportDiagnosticTest {
    @Test
    fun `websocket close code is retained without leaking peer close reason`() {
        val diagnostic = safeTransportDiagnostic(WebSocketClosedException(1001, "private peer detail"))
        assertEquals("WebSocketClosedException:code=1001:OTHER", diagnostic)
        assertFalse(diagnostic.contains("private peer detail"))
    }
}
