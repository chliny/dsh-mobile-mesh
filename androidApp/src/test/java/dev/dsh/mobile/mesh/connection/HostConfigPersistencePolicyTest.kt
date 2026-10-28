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

    @Test
    fun `weak network host refresh cannot erase last known API version`() {
        val existing = host("first", token = "token").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG)
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            mergeRememberedHost(existing, existing.copy(lastProtocol = null)).lastProtocol)
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            mergeRememberedHost(existing, existing.copy(lastProtocol = HarnessProtocol.UNDETERMINED)).lastProtocol)
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS,
            mergeRememberedHost(existing, existing.copy(lastProtocol = HarnessProtocol.LEGACY_SUBAGENTS)).lastProtocol)
    }

    @Test
    fun `confirmed API generation survives persisted host serialization and transient refresh`() = kotlinx.coroutines.runBlocking {
        val confirmed = host("first", token = "token").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG)
        val encoded = dev.dsh.mobile.mesh.core.wire.WireJson.encodeToString(
            HostConfig.serializer(), confirmed,
        )
        val restored = dev.dsh.mobile.mesh.core.wire.WireJson.decodeFromString(
            HostConfig.serializer(), encoded,
        )

        assertEquals(HarnessProtocol.PARENT_CATALOG, restored.lastProtocol)
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            mergeRememberedHost(restored, restored.copy(lastProtocol = null)).lastProtocol)
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            HarnessProtocolSelection(restored.lastProtocol).let { it.detect { error("probe timeout") } })
    }

    @Test
    fun `editing a connection target cannot inherit another endpoints API`() {
        val existing = host("first", "token").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG)
        val editedTargets = listOf(
            existing.copy(host = "other.example", lastProtocol = null),
            existing.copy(port = 443, lastProtocol = null),
            existing.copy(useTls = true, lastProtocol = null),
            existing.copy(meshTransport = MeshTransport.TAILSCALE, lastProtocol = null),
            existing.copy(zeroTierNetworkId = "fedcba9876543210", lastProtocol = null),
            existing.copy(zeroTierPlanetId = "other-planet", lastProtocol = null),
            existing.copy(sshEnabled = true, lastProtocol = null),
        )
        for (edited in editedTargets) {
            org.junit.Assert.assertNull(mergeRememberedHost(existing, edited).lastProtocol)
        }
        val ssh = existing.copy(sshEnabled = true, sshUsername = "first")
        for (edited in listOf(
            ssh.copy(sshPort = 2222, lastProtocol = null),
            ssh.copy(sshUsername = "second", lastProtocol = null),
            ssh.copy(sshDshHost = "other.internal", lastProtocol = null),
        )) {
            org.junit.Assert.assertNull(mergeRememberedHost(ssh, edited).lastProtocol)
        }
    }

    @Test
    fun `renaming a connection preserves its own API cache`() {
        val existing = host("first", "token").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG)
        val renamed = existing.copy(name = "renamed", host = " FIRST.EXAMPLE ", lastProtocol = null)
        assertEquals(HarnessProtocol.PARENT_CATALOG, mergeRememberedHost(existing, renamed).lastProtocol)
        val other = host("second", "other-token")
        org.junit.Assert.assertNull(mergeRememberedHost(other, other.copy(name = "selected")).lastProtocol)
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
