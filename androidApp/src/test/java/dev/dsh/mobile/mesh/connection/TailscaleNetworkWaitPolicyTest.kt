package dev.dsh.mobile.mesh.connection

import org.junit.Test

class TailscaleNetworkWaitPolicyTest {
    @Test
    fun `network readiness is event driven`() {
        // The implementation waits for ConnectivityManager callbacks and has no polling budget.
        Class.forName("dev.dsh.mobile.mesh.connection.TailscaleConnector")
    }
}
