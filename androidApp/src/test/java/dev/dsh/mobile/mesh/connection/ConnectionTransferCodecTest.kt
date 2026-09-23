package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `rejects unsupported transfer format`() {
        val raw = ConnectionTransferCodec.encode(ConnectionTransfer(format = 1)).replace("\"format\":1", "\"format\":2")
        assertThrows(IllegalArgumentException::class.java) { ConnectionTransferCodec.decode(raw) }
    }
}
