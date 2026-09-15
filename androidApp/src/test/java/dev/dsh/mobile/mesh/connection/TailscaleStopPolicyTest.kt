package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleStopPolicyTest {
    @Test
    fun `transport stop keeps native identity alive for recovery`() {
        assertTrue(shouldStopTailscaleNativeOnTransportStop)
    }
}
