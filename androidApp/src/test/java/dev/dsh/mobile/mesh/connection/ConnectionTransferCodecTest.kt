package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionTransferCodecTest {
    @Test
    fun `round trip includes session cookie ssh secrets and mesh custom planet settings`() {
        val transfer = ConnectionTransfer(
            connections = listOf(
                ConnectionTransferEntry(
                    host = HostConfig(
                        id = "mesh-host",
                        name = "Mesh",
                        host = "harness.internal",
                        port = 3080,
                        meshTransport = MeshTransport.ZERO_TIER,
                        zeroTierNetworkId = "0123456789abcdef",
                        zeroTierPlanetBase64 = "planet-payload",
                        launchToken = "dsh-token",
                        sshEnabled = true,
                        sshPort = 36000,
                        sshUsername = "user",
                    ),
                    cookie = "signed-cookie",
                    sshCredentials = SshCredentials("pw", "private-key", "passphrase"),
                ),
            ),
        )

        val decoded = ConnectionTransferCodec.decode(ConnectionTransferCodec.encode(transfer))
        assertEquals(transfer, decoded)
    }

    @Test
    fun `duplicate key uses host port ssh flag and transport`() {
        val original = HostConfig("a", "one", "SERVER", 3080, sshEnabled = true, meshTransport = MeshTransport.ZERO_TIER)
        val match = original.copy(id = "b", name = "renamed", host = "server")
        val differentSsh = original.copy(id = "c", sshEnabled = false)
        val differentPort = original.copy(id = "d", port = 3081)
        val differentTransport = original.copy(id = "e", meshTransport = MeshTransport.TAILSCALE)

        val conflicts = findConnectionImportConflicts(
            imported = listOf(match, differentSsh, differentPort, differentTransport),
            existing = listOf(original),
        )

        assertEquals(1, conflicts.size)
        assertEquals("b", conflicts.single().imported.id)
        assertTrue(conflicts.single().identity.sshEnabled)
        assertFalse(conflicts.single().identity.transport == "tailscale")
    }

    @Test
    fun `rejects unsupported transfer format`() {
        val raw = ConnectionTransferCodec.encode(ConnectionTransfer(format = 1)).replace("\"format\":1", "\"format\":2")
        assertThrows(IllegalArgumentException::class.java) { ConnectionTransferCodec.decode(raw) }
    }
}
