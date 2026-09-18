package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class HostConfigPersistencePolicyTest {
    @Test
    fun `blank update cannot erase paired token for the same host`() {
        val existing = host("second", token = "paired-token")
        val incoming = existing.copy(name = "renamed", launchToken = "")

        assertEquals("paired-token", mergeRememberedHost(existing, incoming).launchToken)
    }

    @Test
    fun `new token replaces the old token for the same host`() {
        val existing = host("second", token = "old-token")
        val incoming = existing.copy(launchToken = "new-token")

        assertEquals("new-token", mergeRememberedHost(existing, incoming).launchToken)
    }

    @Test
    fun `switching hosts does not borrow another host token`() {
        val first = host("first", token = "first-token")
        val second = host("second", token = "second-token")

        assertEquals("second-token", mergeRememberedHost(second, second.copy(name = "selected")).launchToken)
        assertEquals("first-token", first.launchToken)
    }

    private fun host(id: String, token: String) = HostConfig(
        id = id,
        name = id,
        host = "$id.example",
        port = 3080,
        meshTransport = MeshTransport.ZERO_TIER,
        zeroTierNetworkId = "0123456789abcdef",
        sshEnabled = false,
        launchToken = token,
    )
}
