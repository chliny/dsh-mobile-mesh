package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class SignInHostResolutionTest {
    @Test
    fun `token submission resolves active SSH host when attempted address is SSH authority`() {
        val host = HostConfig(
            id = "gmkzt",
            name = "gmkzt",
            host = "gmkzt.chliny.me",
            port = 3080,
            sshEnabled = true,
            sshPort = 22,
        )

        assertEquals(
            host,
            resolveSignInHost(
                signInHostId = null,
                remembered = emptyList(),
                activeHost = host,
                attempted = "gmkzt.chliny.me:22",
            ),
        )
    }
}
