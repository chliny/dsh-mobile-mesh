package dev.dsh.mobile.mesh.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.dsh.mobile.mesh.DshApplication
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal class SettingUpdateSerialiser {
    private val mutex = Mutex()

    suspend fun run(update: suspend () -> Unit) = mutex.withLock { update() }
}

/** Persists remembered hosts and app settings. */
@Singleton
class HostsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HOSTS = stringPreferencesKey("hosts_json")
        val AUTO_LAST = booleanPreferencesKey("auto_last")
        val AUTO_LAN = booleanPreferencesKey("auto_lan")
        val AUTO_LOOPBACK = booleanPreferencesKey("auto_loopback")
        val BACKGROUND = booleanPreferencesKey("background")
        val NOTIFY_TURN = booleanPreferencesKey("notify_turn")
        val NOTIFY_GOAL = booleanPreferencesKey("notify_goal")
        val NOTIFY_ACTION = booleanPreferencesKey("notify_action")
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val PORTS = stringPreferencesKey("ports_json")
        val SESSION_SORT = stringPreferencesKey("session_sort")
        val WORKSPACE_EXPANSION = stringPreferencesKey("workspace_expansion_json")
        val UPDATE_CHECK = booleanPreferencesKey("update_check")
        val DISMISSED_UPDATE = stringPreferencesKey("dismissed_update")
        val CONNECTION_DRAFT = stringPreferencesKey("connection_draft_json")
        val ACTIVE_CONNECTION_ID = stringPreferencesKey("active_connection_id")
    }

    private val hostsSerializer = ListSerializer(HostConfig.serializer())
    private val workspaceExpansionSerializer = MapSerializer(String.serializer(), Boolean.serializer())
    private val settingUpdates = SettingUpdateSerialiser()

    val workspaceExpansion: Flow<Map<String, Boolean>> = dataStore.data.map { prefs ->
        prefs[Keys.WORKSPACE_EXPANSION]?.let { raw ->
            runCatching { WireJson.decodeFromString(workspaceExpansionSerializer, raw) }.getOrNull()
        } ?: emptyMap()
    }

    suspend fun setWorkspaceExpanded(workspaceId: String, expanded: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.WORKSPACE_EXPANSION]?.let { raw ->
                runCatching { WireJson.decodeFromString(workspaceExpansionSerializer, raw) }.getOrNull()
            }.orEmpty()
            prefs[Keys.WORKSPACE_EXPANSION] = WireJson.encodeToString(
                workspaceExpansionSerializer,
                current + (workspaceId to expanded),
            )
        }
    }

    val connectionDraft: Flow<ConnectionDraft> = dataStore.data.map { prefs ->
        prefs[Keys.CONNECTION_DRAFT]?.let {
            runCatching { WireJson.decodeFromString(ConnectionDraft.serializer(), it) }.getOrNull()
        } ?: ConnectionDraft()
    }

    suspend fun saveConnectionDraft(draft: ConnectionDraft) {
        dataStore.edit { it[Keys.CONNECTION_DRAFT] = WireJson.encodeToString(ConnectionDraft.serializer(), draft) }
    }

    suspend fun clearConnectionDraft() {
        dataStore.edit { it.remove(Keys.CONNECTION_DRAFT) }
    }

    val hosts: Flow<List<HostConfig>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.HOSTS] ?: return@map emptyList()
        runCatching {
            WireJson.decodeFromString(hostsSerializer, raw).sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    val settings: Flow<AppSettings> = dataStore.data.map(::settingsFrom)

    suspend fun settingsOnce(): AppSettings = settings.first()

    suspend fun upsertHost(config: HostConfig) {
        val current = hosts.first().toMutableList()
        val existing = current.firstOrNull { it.id == config.id || (it.host == config.host && it.port == config.port) }
        val merged = mergeRememberedHost(existing, config)
        current.removeAll { it.id == config.id || (it.host == config.host && it.port == config.port) }
        current.add(0, merged)
        persist(current)
    }

    suspend fun touchHost(host: String, port: Int) {
        val current = hosts.first().map {
            if (it.host == host && it.port == port) it.copy(lastConnectedAt = System.currentTimeMillis()) else it
        }
        persist(current)
    }

    /** Replace the process-startup token only for its associated saved connection. */
    suspend fun saveLaunchToken(hostId: String, token: String) {
        val current = hosts.first().map {
            if (it.id == hostId) it.copy(launchToken = token) else it
        }
        persist(current)
    }

    /**
     * Remember (or refresh) one endpoint.
     *
     * The id is stable across reconnects: `host:port` is the identity, so a returning harness keeps
     * the id it already had. Minting a fresh UUID every time made the id useless as a list key and
     * as a handle for anything stored per host. Cached describe fields survive a call that does not
     * supply them.
     */
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
        /** Discovery/direct connections use HTTP/WebSocket; SSH must be explicitly enabled. */
        sshEnabled: Boolean = false,
        sshPort: Int = 22,
        sshUsername: String? = null,
        sshAuthentication: SshAuthentication = SshAuthentication.PASSWORD,
        sshDshHost: String = "127.0.0.1",
    ): HostConfig {
        val existing = hosts.first().firstOrNull { it.host == host && it.port == port }
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
            zeroTierPlanetId = zeroTierPlanetId?.takeIf { meshTransport == MeshTransport.ZERO_TIER },
            zeroTierPlanetBase64 = zeroTierPlanetBase64?.takeIf { meshTransport == MeshTransport.ZERO_TIER },
            tailscaleHostname = tailscaleHostname?.takeIf { meshTransport == MeshTransport.TAILSCALE },
            sshEnabled = sshEnabled,
            sshPort = sshPort,
            sshUsername = sshUsername,
            sshAuthentication = sshAuthentication,
            sshDshHost = sshDshHost,
            launchToken = existing?.launchToken.orEmpty(),
        )
        upsertHost(config)
        return config
    }

    /** Fold a fresh host description into the remembered entry without touching its recency. */
    suspend fun cacheDescription(host: String, port: Int, description: HostDescription) {
        val current = hosts.first()
        if (current.none { it.host == host && it.port == port }) return
        persist(
            current.map {
                if (it.host == host && it.port == port) {
                    it.copy(lastHome = description.home)
                } else {
                    it
                }
            },
        )
    }

    suspend fun importHosts(imported: List<HostConfig>): Map<String, String> {
        if (imported.isEmpty()) return emptyMap()
        val current = hosts.first().toMutableList()
        val idMapping = linkedMapOf<String, String>()
        imported.forEach { config ->
            val existing = current.firstOrNull { it.host == config.host && it.port == config.port }
            current.removeAll { it.id == config.id || (it.host == config.host && it.port == config.port) }
            val importedConfig = if (existing != null && existing.id != config.id) {
                config.copy(id = existing.id)
            } else config
            current.add(mergeRememberedHost(existing, importedConfig))
            idMapping[config.id] = importedConfig.id
        }
        persist(current)
        return idMapping
    }

    suspend fun removeHost(id: String) {
        persist(hosts.first().filterNot { it.id == id })
        dataStore.edit { prefs ->
            if (prefs[Keys.ACTIVE_CONNECTION_ID] == id) prefs.remove(Keys.ACTIVE_CONNECTION_ID)
        }
    }

    suspend fun activeConnectionId(): String? = dataStore.data.first()[Keys.ACTIVE_CONNECTION_ID]

    suspend fun setActiveConnectionId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_CONNECTION_ID) else prefs[Keys.ACTIVE_CONNECTION_ID] = id
        }
    }

    /**
     * The session last opened on [hostKey] (`"host:port"`), or null when this harness has not been
     * used before. Keyed per host because session ids are host-scoped — one global key would try to
     * reopen a stale id from a different harness after every host switch.
     */
    /** Drawer session ordering: `"manual"` follows the workspace order, `"updated"` sorts by recency. */
    val sessionSort: Flow<String> = dataStore.data.map { it[Keys.SESSION_SORT] ?: "manual" }

    suspend fun setSessionSort(value: String) {
        dataStore.edit { it[Keys.SESSION_SORT] = value }
    }

    /** Remember that this release was declined, so it is not offered again. */
    suspend fun setDismissedUpdate(version: String) {
        dataStore.edit { it[Keys.DISMISSED_UPDATE] = version }
    }

    suspend fun addKnownPort(port: Int) {
        val s = settingsOnce()
        val ports = (s.knownPorts + port).distinct().take(8)
        dataStore.edit { it[Keys.PORTS] = ports.joinToString(",") }
    }

    suspend fun setSetting(transform: (AppSettings) -> AppSettings) = settingUpdates.run {
        var themePreference: String? = null
        dataStore.edit { prefs ->
            val next = transform(settingsFrom(prefs))
            prefs[Keys.AUTO_LAST] = next.autoConnectLast
            prefs[Keys.AUTO_LAN] = next.autoConnectLan
            prefs[Keys.AUTO_LOOPBACK] = next.autoConnectLoopback
            prefs[Keys.BACKGROUND] = next.keepConnectedInBackground
            prefs[Keys.NOTIFY_TURN] = next.notifyTurnComplete
            prefs[Keys.NOTIFY_GOAL] = next.notifyGoal
            prefs[Keys.NOTIFY_ACTION] = next.notifyNeedsAction
            prefs[Keys.THEME] = next.themePreference
            prefs[Keys.UPDATE_CHECK] = next.updateCheckEnabled
            next.localeOverride?.let { prefs[Keys.LOCALE] = it } ?: prefs.remove(Keys.LOCALE)
            themePreference = next.themePreference
        }
        // Mirror only the value whose DataStore transaction committed. Serializing this write with
        // the transaction prevents an older concurrent call from overwriting the newer mirror.
        DshApplication.storeThemePreference(context, checkNotNull(themePreference))
    }

    private fun settingsFrom(prefs: Preferences): AppSettings {
        val ports = prefs[Keys.PORTS]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(3080)
        return AppSettings(
            autoConnectLast = prefs[Keys.AUTO_LAST] ?: true,
            autoConnectLan = prefs[Keys.AUTO_LAN] ?: false,
            autoConnectLoopback = prefs[Keys.AUTO_LOOPBACK] ?: true,
            keepConnectedInBackground = prefs[Keys.BACKGROUND] ?: false,
            notifyTurnComplete = prefs[Keys.NOTIFY_TURN] ?: true,
            notifyGoal = prefs[Keys.NOTIFY_GOAL] ?: true,
            notifyNeedsAction = prefs[Keys.NOTIFY_ACTION] ?: true,
            themePreference = prefs[Keys.THEME] ?: "system",
            localeOverride = prefs[Keys.LOCALE],
            knownPorts = ports,
            updateCheckEnabled = prefs[Keys.UPDATE_CHECK] ?: true,
            dismissedUpdate = prefs[Keys.DISMISSED_UPDATE],
        )
    }

    private suspend fun persist(list: List<HostConfig>) {
        dataStore.edit { it[Keys.HOSTS] = WireJson.encodeToString(hostsSerializer, list) }
    }
}
