package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTokenPairingPolicyTest {
    @Test
    fun `persisted session suppresses remembered launch token pairing`() {
        assertFalse(shouldPairLaunchToken(hasSession = true, token = "stale-token"))
        assertTrue(shouldPairLaunchToken(hasSession = false, token = "fresh-token"))
        assertFalse(shouldPairLaunchToken(hasSession = false, token = null))
    }
}
