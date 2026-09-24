package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCredentialsPolicyTest {
    @Test
    fun `password authentication requires saved password`() {
        assertFalse(SshCredentials().hasCredentialFor(SshAuthentication.PASSWORD))
        assertFalse(SshCredentials(password = "").hasCredentialFor(SshAuthentication.PASSWORD))
        assertTrue(SshCredentials(password = "secret").hasCredentialFor(SshAuthentication.PASSWORD))
    }

    @Test
    fun `key authentication requires saved private key`() {
        assertFalse(SshCredentials().hasCredentialFor(SshAuthentication.PRIVATE_KEY))
        assertFalse(SshCredentials(privateKey = "  ").hasCredentialFor(SshAuthentication.PRIVATE_KEY))
        assertTrue(SshCredentials(privateKey = "key-data").hasCredentialFor(SshAuthentication.PRIVATE_KEY))
    }

    @Test
    fun `credential for other authentication mode does not satisfy requirement`() {
        assertFalse(
            SshCredentials(password = "secret").hasCredentialFor(SshAuthentication.PRIVATE_KEY),
        )
        assertFalse(
            SshCredentials(privateKey = "key-data").hasCredentialFor(SshAuthentication.PASSWORD),
        )
    }
}
