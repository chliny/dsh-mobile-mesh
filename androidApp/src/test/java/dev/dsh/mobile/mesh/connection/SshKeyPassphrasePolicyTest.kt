package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyPassphrasePolicyTest {
    @Test
    fun `missing or blank passphrase uses unencrypted key loading`() {
        assertFalse(shouldUseSshKeyPassphrase(null))
        assertFalse(shouldUseSshKeyPassphrase(""))
        assertFalse(shouldUseSshKeyPassphrase("   "))
    }

    @Test
    fun `nonblank passphrase uses protected key loading`() {
        assertTrue(shouldUseSshKeyPassphrase("secret"))
    }
}
