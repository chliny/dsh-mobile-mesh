package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectedGenerationPolicyTest {
    @Test
    fun `new generation is not probed merely because prior recovery was visible`() {
        assertFalse(shouldProbePublishedGeneration(true, true, false))
        assertFalse(shouldProbePublishedGeneration(false, true, false))
        assertFalse(shouldProbePublishedGeneration(false, false, true))
        assertFalse(shouldProbePublishedGeneration(false, false, false))
    }
}
