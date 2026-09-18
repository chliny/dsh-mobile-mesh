package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportReadyCallbackPolicyTest {
    @Test
    fun `ordinary carrier recovery does not repeat launch token pairing`() {
        assertFalse(shouldRunTransportReadyCallback(reconnect = true, preservePendingIdentity = false))
    }

    @Test
    fun `initial connect pairs launch token`() {
        assertTrue(shouldRunTransportReadyCallback(reconnect = false, preservePendingIdentity = false))
    }

    @Test
    fun `authorization resume pairs after retained identity is ready`() {
        assertTrue(shouldRunTransportReadyCallback(reconnect = false, preservePendingIdentity = true))
    }
}
