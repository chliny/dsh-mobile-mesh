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
private val refreshKey = stringPreferencesKey("harness_endpoint_session_refresh_json")
private val hostsKey = stringPreferencesKey("hosts_json")
private val cookieSerializer = MapSerializer(String.serializer(), String.serializer())
private val refreshSerializer = MapSerializer(String.serializer(), Long.serializer())
private val hostsSerializer = ListSerializer(HostConfig.serializer())

private fun readCookies(prefs: Preferences, key: Preferences.Key<String>): Map<String, String> =
    prefs[key]?.let { runCatching { WireJson.decodeFromString(cookieSerializer, it) }.getOrNull() }.orEmpty()

private fun readRefreshes(prefs: Preferences): Map<String, Long> =
    prefs[refreshKey]?.let { runCatching { WireJson.decodeFromString(refreshSerializer, it) }.getOrNull() }.orEmpty()

private fun readHosts(prefs: Preferences): List<HostConfig> =
    prefs[hostsKey]?.let { runCatching { WireJson.decodeFromString(hostsSerializer, it) }.getOrNull() }.orEmpty()

/**
 * When a foreground app should exchange the launch token again: at 75% of the minted lifetime.
 *
 * A quarter of the lifetime stays as the window in which a failed renewal is still harmless; the
 * host's cookie lifetime is absolute, so waiting until the expiry would guarantee an outage. A
 * host that declares no expiry cannot be refreshed ahead of time and returns null.
 */
internal fun sessionRefreshDeadlineMillis(issuedAtMillis: Long, expiresAtMillis: Long?): Long? {
    val expiresAt = expiresAtMillis?.takeIf { it > issuedAtMillis } ?: return null
    val lifetime = expiresAt - issuedAtMillis
    return issuedAtMillis + (lifetime / 4L) * 3L
}

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
    val endpoint = removedHost.authEndpointKey()
    if (remainingHosts.none { it.authEndpointKey() == endpoint }) {
        prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, readCookies(prefs, endpointKey) - endpoint)
        prefs[refreshKey] = WireJson.encodeToString(refreshSerializer, readRefreshes(prefs) - endpoint)
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
            val endpoint = host.authEndpointKey()
            // The exchange time is the mint time the host signed into the cookie.
            val refreshAt = sessionRefreshDeadlineMillis(System.currentTimeMillis(), outcome.expiresAtMillis)
            dataStore.edit { prefs ->
                migrateHarnessCookies(prefs, readHosts(prefs))
                prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, readCookies(prefs, endpointKey) + (endpoint to outcome.cookie))
                val refreshes = readRefreshes(prefs).toMutableMap()
                if (refreshAt != null) refreshes[endpoint] = refreshAt else refreshes.remove(endpoint)
                prefs[refreshKey] = WireJson.encodeToString(refreshSerializer, refreshes)
            }
        }
        return outcome
    }

    /**
     * When a foreground renewal is due for [host], or null when its cookie declared no expiry.
     *
     * Read on the same pass as the cookie itself; a host whose stored session predates expiry
     * tracking simply waits for the authoritative 401 and the silent exchange that follows it.
     */
    suspend fun refreshAt(host: HostConfig): Long? = readRefreshes(dataStore.data.first())[host.authEndpointKey()]

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
            val refreshes = readRefreshes(prefs).toMutableMap()
            cookies.forEach { (host, cookie) ->
                if (cookie.isNotBlank()) {
                    val endpoint = host.authEndpointKey()
                    current[endpoint] = cookie
                    // An imported value carries no provenance, so a deadline from an older cookie
                    // of the same endpoint would renew on the wrong schedule.
                    refreshes.remove(endpoint)
                }
            }
            prefs[endpointKey] = WireJson.encodeToString(cookieSerializer, current)
            prefs[refreshKey] = WireJson.encodeToString(refreshSerializer, refreshes)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(legacyKey); it.remove(endpointKey); it.remove(refreshKey) }
    }
}
