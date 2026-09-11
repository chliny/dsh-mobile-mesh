package dev.dsh.mobile.mesh.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.dsh.mobile.mesh.core.wire.HarnessSession
import dev.dsh.mobile.mesh.core.wire.SessionExchange
import dev.dsh.mobile.mesh.core.wire.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harness browser sessions, one per host.
 *
 * Harness 0.1.2 answers 401 to every `/api` request without one, so a direct connection cannot do
 * anything at all until this store holds a cookie for that host. The cookie is obtained once, by
 * exchanging the launch token the harness prints at startup, and survives harness restarts — the
 * signing secret is durable upstream even though the token itself rotates.
 *
     * The cookie is stored with the app's private DataStore so a launch token only needs to be
     * exchanged once per host.
 */
@Singleton
class HarnessSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val okHttpClient: OkHttpClient,
) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** The `Cookie` value for [hostId], or null when this device has no session for it. */
    suspend fun cookie(hostId: String): String? = sessions()[hostId]

    /**
     * Exchange [tokenInput] for a session and remember it.
     *
     * [tokenInput] may be the bare token, the whole `?token=…` URL, or the entire startup line the
     * harness printed — see [HarnessSession.tokenFrom].
     */
    suspend fun pair(
        hostId: String,
        baseUrl: String,
        tokenInput: String,
        hostHeader: String? = null,
    ): SessionExchange {
        val token = HarnessSession.tokenFrom(tokenInput)
            ?: return SessionExchange.Refused(0)
        val outcome = HarnessSession.exchange(baseUrl, token, okHttpClient, hostHeader = hostHeader)
        if (outcome is SessionExchange.Granted) {
            write(sessions() + (hostId to outcome.cookie))
        }
        return outcome
    }

    /** Forget the session for [hostId]. Safe to call when there is none. */
    suspend fun remove(hostId: String) {
        val current = sessions()
        if (hostId !in current) return
        write(current - hostId)
    }

    /** Forget every session — the Settings "clear data" action. */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private suspend fun sessions(): Map<String, String> {
        val raw = dataStore.data.first()[KEY] ?: return emptyMap()
        return runCatching { WireJson.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private suspend fun write(next: Map<String, String>) {
        dataStore.edit { it[KEY] = WireJson.encodeToString(serializer, next) }
    }

    private companion object {
        val KEY = stringPreferencesKey("harness_sessions_json")
    }
}
