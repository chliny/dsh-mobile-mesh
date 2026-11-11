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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Context-free storage; every remembered-host read/modify/write is one DataStore transaction. */
internal class RememberedHostsStorage(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val HOSTS = stringPreferencesKey("hosts_json")
        private val PROTOCOLS = stringPreferencesKey("harness_endpoint_protocols_json")
        val ACTIVE_CONNECTION_ID = stringPreferencesKey("active_connection_id")
        private val serializer = ListSerializer(HostConfig.serializer())
        private val protocolsSerializer = MapSerializer(String.serializer(), String.serializer())
    }

    val hosts: Flow<List<HostConfig>> = dataStore.data.map { prefs ->
        val hosts = readHosts(prefs)
        val tokens = hosts.filter { it.launchToken.isNotBlank() }.groupBy { it.authEndpointKey() }
        hosts.map { host -> host.copy(launchToken = tokens[host.authEndpointKey()]?.first()?.launchToken.orEmpty()) }
    }

    private fun readHosts(prefs: Preferences): List<HostConfig> = prefs[HOSTS]?.let { raw ->
        runCatching {
            WireJson.decodeFromString(serializer, raw).sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }.orEmpty()

    private fun readProtocols(prefs: Preferences, hosts: List<HostConfig>): MutableMap<String, HarnessProtocol> {
        val stored = prefs[PROTOCOLS]?.let { raw ->
            runCatching { WireJson.decodeFromString(protocolsSerializer, raw) }.getOrNull()
        }.orEmpty()
        val protocols = stored.mapNotNull { (key, value) ->
            HarnessProtocol.entries.firstOrNull { it.name == value && it != HarnessProtocol.UNDETERMINED }
                ?.let { key to it }
        }.toMap().toMutableMap()
        // Migrate the last confirmed observation from each pre-existing saved host. Never replace
        // a newer endpoint observation with a potentially stale snapshot embedded in a host row.
        hosts.forEach { host ->
            host.lastProtocol?.takeUnless { it == HarnessProtocol.UNDETERMINED }?.let {
                protocols.putIfAbsent(host.harnessEndpointKey(), it)
            }
        }
        return protocols
    }

    suspend fun protocolFor(config: HostConfig): HarnessProtocol? {
        val prefs = dataStore.data.first()
        return readProtocols(prefs, readHosts(prefs))[config.harnessEndpointKey()]
    }

    private class UpdateResult<T>(val value: T)

    private suspend fun <T> update(
        transform: (MutableList<HostConfig>, MutablePreferences, MutableMap<String, HarnessProtocol>) -> T,
    ): T {
        lateinit var result: UpdateResult<T>
        dataStore.edit { prefs ->
            val current = readHosts(prefs).toMutableList()
            migrateHarnessCookies(prefs, current)
            val protocols = readProtocols(prefs, current)
            result = UpdateResult(transform(current, prefs, protocols))
            prefs[HOSTS] = WireJson.encodeToString(serializer, current)
            prefs[PROTOCOLS] = WireJson.encodeToString(protocolsSerializer, protocols.mapValues { it.value.name })
        }
        return result.value
    }

    suspend fun upsertHost(config: HostConfig) {
        update { current, _, protocols ->
            val existing = current.firstOrNull { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
            val key = config.harnessEndpointKey()
            val observed = config.lastProtocol?.takeUnless { it == HarnessProtocol.UNDETERMINED }
                ?.takeIf { existing == null || sameHarnessEndpoint(existing, config) }
            if (observed != null) protocols.putIfAbsent(key, observed)
            val sameAuth = existing != null && existing.authEndpointKey() == config.authEndpointKey()
            val peerToken = current.firstOrNull {
                it.id != existing?.id && it.authEndpointKey() == config.authEndpointKey() && it.launchToken.isNotBlank()
            }?.launchToken.orEmpty()
            val suppliedToken = config.launchToken.takeIf { it.isNotBlank() && (existing == null || it != existing.launchToken) }
            val token = suppliedToken ?: peerToken.ifBlank { if (sameAuth) existing?.launchToken.orEmpty() else "" }
            val merged = mergeRememberedHost(existing, config.copy(lastProtocol = protocols[key])).copy(launchToken = token)
            current.removeAll { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
            if (token.isNotBlank()) current.replaceAll { if (it.authEndpointKey() == config.authEndpointKey()) it.copy(launchToken = token) else it }
            current.add(0, merged)
        }
    }

    /** Update only the still-existing matching endpoint, never a stale connection snapshot. */
    suspend fun cacheProtocol(config: HostConfig, protocol: HarnessProtocol) {
        if (protocol == HarnessProtocol.UNDETERMINED) return
        update { current, _, protocols ->
            current.replaceAll {
                if (it.id == config.id && sameHarnessEndpoint(it, config)) {
                    protocols[it.harnessEndpointKey()] = protocol
                    it.copy(lastProtocol = protocol)
                } else it
            }
        }
    }

    suspend fun touchHost(host: String, port: Int) {
        update { current, _, _ ->
            current.replaceAll {
                if (it.host == host && it.port == port) it.copy(lastConnectedAt = System.currentTimeMillis()) else it
            }
        }
    }

    suspend fun saveLaunchToken(hostId: String, token: String) {
        update { current, _, _ ->
            val endpoint = current.firstOrNull { it.id == hostId }?.authEndpointKey() ?: return@update
            current.replaceAll { if (it.authEndpointKey() == endpoint) it.copy(launchToken = token) else it }
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
    ): HostConfig = update { current, _, protocols ->
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
        val peerToken = current.firstOrNull {
            it.authEndpointKey() == config.authEndpointKey() && it.launchToken.isNotBlank()
        }?.launchToken.orEmpty()
        val merged = mergeRememberedHost(existing, config.copy(lastProtocol = protocols[config.harnessEndpointKey()]))
            .copy(launchToken = peerToken)
        current.removeAll { it.id == config.id || it.connectionIdentity() == config.connectionIdentity() }
        current.add(0, merged)
        merged
    }

    suspend fun cacheDescription(host: String, port: Int, description: HostDescription) {
        update { current, _, _ ->
            current.replaceAll {
                if (it.host == host && it.port == port) it.copy(lastHome = description.home) else it
            }
        }
    }

    suspend fun importHosts(imported: List<HostConfig>, replaceExistingIds: Set<String>): Map<String, String> {
        if (imported.isEmpty()) return emptyMap()
        return update { current, _, protocols ->
            val idMapping = linkedMapOf<String, String>()
            imported.distinctBy(HostConfig::connectionIdentity).forEach { config ->
                val existing = current.firstOrNull { it.connectionIdentity() == config.connectionIdentity() }
                if (existing != null && existing.id !in replaceExistingIds) return@forEach
                val importedConfig = if (existing != null) config.copy(id = existing.id) else config
                val key = importedConfig.harnessEndpointKey()
                val observed = importedConfig.lastProtocol?.takeUnless { it == HarnessProtocol.UNDETERMINED }
                if (observed != null) protocols.putIfAbsent(key, observed)
                current.removeAll { it.id == importedConfig.id }
                current.add(mergeRememberedHost(existing, importedConfig.copy(lastProtocol = protocols[key])))
                idMapping[config.id] = importedConfig.id
            }
            idMapping
        }
    }

    suspend fun removeHost(id: String) {
        update { current, prefs, _ ->
            val removed = current.firstOrNull { it.id == id }
            current.removeAll { it.id == id }
            if (removed != null) removeUnusedHarnessCookie(prefs, current, removed)
            if (prefs[ACTIVE_CONNECTION_ID] == id) prefs.remove(ACTIVE_CONNECTION_ID)
        }
    }
}
