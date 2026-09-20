package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedGenerationPolicyTest {
    @Test
    fun `replaced generation keeps liveness fence when prior recovery was visible`() {
        assertTrue(shouldProbePublishedGeneration(true, true, false))
        assertTrue(shouldProbePublishedGeneration(false, true, false))
        assertTrue(shouldProbePublishedGeneration(false, false, true))
        assertFalse(shouldProbePublishedGeneration(false, false, false))
    }
}
