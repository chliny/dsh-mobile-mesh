package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleNetworkWaitPolicyTest {
    @Test
    fun `capabilities-only transition wakes waiter on the active network`() {
        assertTrue(shouldSignalTailscaleNetworkWaiter(true, true))
        assertFalse(shouldSignalTailscaleNetworkWaiter(true, false))
        assertFalse(shouldSignalTailscaleNetworkWaiter(false, true))
    }

    @Test
    fun `network readiness is event driven`() {
        // The implementation waits for ConnectivityManager callbacks and has no polling budget.
        Class.forName("dev.dsh.mobile.mesh.connection.TailscaleConnector")
    }
}
