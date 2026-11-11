package dev.dsh.mobile.mesh.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.dsh.mobile.mesh.core.wire.HarnessSession
import dev.dsh.mobile.mesh.core.wire.SessionExchange
import dev.dsh.mobile.mesh.core.wire.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private val legacyKey = stringPreferencesKey("harness_sessions_json")
private val endpointKey = stringPreferencesKey("harness_endpoint_sessions_json")
private val hostsKey = stringPreferencesKey("hosts_json")
private val cookieSerializer = MapSerializer(String.serializer(), String.serializer())
private val hostsSerializer = ListSerializer(HostConfig.serializer())

private fun readCookies(prefs: Preferences, key: Preferences.Key<String>): Map<String, String> =
    prefs[key]?.let { runCatching { WireJson.decodeFromString(cookieSerializer, it) }.getOrNull() }.orEmpty()

private fun readHosts(prefs: Preferences): List<HostConfig> =
    prefs[hostsKey]?.let { runCatching { WireJson.decodeFromString(hostsSerializer, it) }.getOrNull() }.orEmpty()

/** Called inside the same transaction, before changing remembered hosts. */
internal fun migrateHarnessCookies(prefs: MutablePreferences, hosts: List<HostConfig>) {
    val legacy = readCookies(prefs, legacyKey)
    if (legacy.isEmpty()) return
    val endpoints = readCookies(prefs, endpointKey).toMutableMap()
    val remaining = legacy.toMutableMap()
    hosts.forEach { host ->
        remaining.remove(host.id)?.takeIf { it.isNotBlank() }?.let {
            endpoints.putIfAbsent(host.authEndpointKey(), it)
        }
    }
    prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, endpoints)
    // Orphaned host IDs must not attach to a subsequently imported or newly created profile.
    prefs.remove(legacyKey)
}

internal fun removeUnusedHarnessCookie(prefs: MutablePreferences, remainingHosts: List<HostConfig>, removedHost: HostConfig) {
    if (remainingHosts.none { it.authEndpointKey() == removedHost.authEndpointKey() }) {
        prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, readCookies(prefs, endpointKey) - removedHost.authEndpointKey())
    }
}

/** Browser sessions are shared by the actual DSH authentication endpoint. */
@Singleton
class HarnessSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val okHttpClient: OkHttpClient,
) {
    suspend fun cookie(host: HostConfig): String? = endpointCookies()[host.authEndpointKey()]

    suspend fun hasSession(host: HostConfig): Boolean = cookie(host) != null

    suspend fun pair(host: HostConfig, baseUrl: String, tokenInput: String, hostHeader: String? = null): SessionExchange {
        val token = HarnessSession.tokenFrom(tokenInput) ?: return SessionExchange.Refused(0)
        val outcome = HarnessSession.exchange(baseUrl, token, okHttpClient, hostHeader = hostHeader)
        if (outcome is SessionExchange.Granted) {
            dataStore.edit { prefs ->
                migrateHarnessCookies(prefs, readHosts(prefs))
                prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, readCookies(prefs, endpointKey) + (host.authEndpointKey() to outcome.cookie))
            }
        }
        return outcome
    }

    suspend fun exportCookies(hosts: List<HostConfig>): Map<String, String> {
        val cookies = endpointCookies()
        return hosts.mapNotNull { host -> cookies[host.authEndpointKey()]?.let { host.id to it } }.toMap()
    }

    /**
     * Current endpoint cookies, migrating id-keyed sessions only when some are still stored.
     *
     * Ordinary reads must not open a write transaction: this is called for every client the app
     * builds (including reconnects), and DataStore writes its file for every edit.
     */
    private suspend fun endpointCookies(): Map<String, String> {
        val prefs = dataStore.data.first()
        if (prefs[legacyKey] == null) return readCookies(prefs, endpointKey)
        var migrated = emptyMap<String, String>()
        dataStore.edit { editable ->
            migrateHarnessCookies(editable, readHosts(editable))
            migrated = readCookies(editable, endpointKey)
        }
        return migrated
    }

    suspend fun importCookies(cookies: Map<HostConfig, String>) {
        if (cookies.isEmpty()) return
        dataStore.edit { prefs ->
            migrateHarnessCookies(prefs, readHosts(prefs))
            val current = readCookies(prefs, endpointKey).toMutableMap()
            cookies.forEach { (host, cookie) -> if (cookie.isNotBlank()) current[host.authEndpointKey()] = cookie }
            prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, current)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(legacyKey); it.remove(endpointKey) }
    }
}
