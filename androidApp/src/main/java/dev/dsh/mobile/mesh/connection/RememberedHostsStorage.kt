package dev.dsh.mobile.mesh.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** Context-free storage; every remembered-host read/modify/write is one DataStore transaction. */
internal class RememberedHostsStorage(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val HOSTS = stringPreferencesKey("hosts_json")
        val ACTIVE_CONNECTION_ID = stringPreferencesKey("active_connection_id")
        private val serializer = ListSerializer(HostConfig.serializer())
    }

    val hosts: Flow<List<HostConfig>> = dataStore.data.map(::readHosts)

    private fun readHosts(prefs: Preferences): List<HostConfig> = prefs[HOSTS]?.let { raw ->
        runCatching {
            WireJson.decodeFromString(serializer, raw).sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }.orEmpty()

    private class UpdateResult<T>(val value: T)

    private suspend fun <T> update(
        transform: (MutableList<HostConfig>, MutablePreferences) -> T,
    ): T {
        lateinit var result: UpdateResult<T>
        dataStore.edit { prefs ->
            val current = readHosts(prefs).toMutableList()
            result = UpdateResult(transform(current, prefs))
            prefs[HOSTS] = WireJson.encodeToString(serializer, current)
        }
        return result.value
    }

    suspend fun upsertHost(config: HostConfig) {
        update { current, _ ->
            val existing = current.firstOrNull { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
            val merged = mergeRememberedHost(existing, config)
            current.removeAll { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
            current.add(0, merged)
        }
    }

    suspend fun touchHost(host: String, port: Int) {
        update { current, _ ->
            current.replaceAll {
                if (it.host == host && it.port == port) it.copy(lastConnectedAt = System.currentTimeMillis()) else it
            }
        }
    }

    suspend fun saveLaunchToken(hostId: String, token: String) {
        update { current, _ ->
            current.replaceAll { if (it.id == hostId) it.copy(launchToken = token) else it }
        }
    }

    suspend fun rememberHost(
        name: String,
        host: String,
        port: Int,
        isLoopback: Boolean,
        useTls: Boolean = false,
        description: HostDescription? = null,
        meshTransport: MeshTransport? = null,
        zeroTierNetworkId: String? = null,
        zeroTierPlanetId: String? = null,
        zeroTierPlanetBase64: String? = null,
        tailscaleHostname: String? = null,
        sshEnabled: Boolean = false,
        sshPort: Int = 22,
        sshUsername: String? = null,
        sshAuthentication: SshAuthentication = SshAuthentication.PASSWORD,
        sshDshHost: String = "127.0.0.1",
    ): HostConfig = update { current, _ ->
        val incomingIdentity = ConnectionIdentity(host.trim().lowercase(), port, sshEnabled, meshTransport?.storedValue ?: "direct")
        val existing = current.firstOrNull { it.connectionIdentity() == incomingIdentity }
        val config = HostConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { existing?.name ?: host },
            host = host,
            port = port,
            isLoopback = isLoopback,
            lastConnectedAt = System.currentTimeMillis(),
            lastHome = description?.home ?: existing?.lastHome,
            useTls = useTls,
            meshTransport = meshTransport,
            zeroTierNetworkId = zeroTierNetworkId?.takeIf { meshTransport == MeshTransport.ZERO_TIER },
            zeroTierPlanetId = retainedZeroTierPlanetId(meshTransport, zeroTierPlanetId, existing?.zeroTierPlanetId),
            zeroTierPlanetBase64 = (zeroTierPlanetBase64 ?: existing?.zeroTierPlanetBase64)
                ?.takeIf { meshTransport == MeshTransport.ZERO_TIER },
            tailscaleHostname = tailscaleHostname?.takeIf { meshTransport == MeshTransport.TAILSCALE },
            sshEnabled = sshEnabled,
            sshPort = sshPort,
            sshUsername = sshUsername,
            sshAuthentication = sshAuthentication,
            sshDshHost = sshDshHost,
            launchToken = existing?.launchToken.orEmpty(),
        )
        val merged = mergeRememberedHost(existing, config)
        current.removeAll { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
        current.add(0, merged)
        merged
    }

    suspend fun cacheDescription(host: String, port: Int, description: HostDescription) {
        update { current, _ ->
            current.replaceAll {
                if (it.host == host && it.port == port) it.copy(lastHome = description.home) else it
            }
        }
    }

    suspend fun importHosts(imported: List<HostConfig>, replaceExistingIds: Set<String>): Map<String, String> {
        if (imported.isEmpty()) return emptyMap()
        return update { current, _ ->
            val idMapping = linkedMapOf<String, String>()
            imported.distinctBy(HostConfig::connectionIdentity).forEach { config ->
                val existing = current.firstOrNull { it.connectionIdentity() == config.connectionIdentity() }
                if (existing != null && existing.id !in replaceExistingIds) return@forEach
                val importedConfig = if (existing != null) config.copy(id = existing.id) else config
                current.removeAll { it.id == importedConfig.id }
                current.add(mergeRememberedHost(existing, importedConfig))
                idMapping[config.id] = importedConfig.id
            }
            idMapping
        }
    }

    suspend fun removeHost(id: String) {
        update { current, prefs ->
            current.removeAll { it.id == id }
            if (prefs[ACTIVE_CONNECTION_ID] == id) prefs.remove(ACTIVE_CONNECTION_ID)
        }
    }
}
