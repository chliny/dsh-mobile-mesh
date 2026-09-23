package dev.dsh.mobile.mesh.connection

import kotlinx.serialization.Serializable

/** Portable, user-selected backup of remembered Harness connections and their credentials. */
@Serializable
data class ConnectionTransfer(
    val format: Int = CURRENT_FORMAT,
    val connections: List<ConnectionTransferEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

@Serializable
data class ConnectionTransferEntry(
    val host: HostConfig,
    val cookie: String? = null,
    val sshCredentials: SshCredentials? = null,
)
