package dev.dsh.mobile.mesh.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RememberedHostsStorageTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `cacheDescription cannot overwrite a concurrently confirmed protocol`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        interleaveProtocolUpdate(store, dataStore, original) {
            store.cacheDescription(original.host, original.port, HostDescription("/home/new"))
        }
        val saved = store.hosts.first().single()
        assertEquals("/home/new", saved.lastHome)
        assertEquals(original.lastConnectedAt, saved.lastConnectedAt)
    }

    @Test
    fun `saveLaunchToken cannot overwrite a concurrently confirmed protocol`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        interleaveProtocolUpdate(store, dataStore, original) {
            store.saveLaunchToken(original.id, "paired-token")
        }
        assertEquals("paired-token", store.hosts.first().single().launchToken)
    }

    @Test
    fun `stale upsert merges the protocol and token from its transaction snapshot`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        interleaveProtocolUpdate(store, dataStore, original) {
            store.upsertHost(original.copy(name = "renamed", lastProtocol = HarnessProtocol.UNDETERMINED))
        }
        val saved = store.hosts.first().single()
        assertEquals("renamed", saved.name)
        assertEquals("confirmed-token", saved.launchToken)
    }

    @Test
    fun `touchHost cannot overwrite a concurrently confirmed protocol`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        interleaveProtocolUpdate(store, dataStore, original) {
            store.touchHost(original.host, original.port)
        }
        assertTrue(store.hosts.first().single().lastConnectedAt > original.lastConnectedAt)
    }

    @Test
    fun `rememberHost returns the protocol and stable id committed in its transaction`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        var remembered: HostConfig? = null
        interleaveProtocolUpdate(store, dataStore, original) {
            remembered = store.rememberHost("renamed", original.host, original.port, false)
        }
        assertEquals(original.id, remembered?.id)
        assertEquals(HarnessProtocol.PARENT_CATALOG, remembered?.lastProtocol)
        assertEquals("confirmed-token", remembered?.launchToken)
        assertEquals(store.hosts.first().single(), remembered)
    }

    @Test
    fun `importHosts merges concurrent protocol while retaining replacement id mapping`() = withStorage { store, dataStore ->
        val original = host("first")
        store.upsertHost(original)
        var mapping: Map<String, String>? = null
        interleaveProtocolUpdate(store, dataStore, original) {
            mapping = store.importHosts(listOf(original.copy(id = "imported", name = "imported")), setOf(original.id))
        }
        assertEquals(mapOf("imported" to original.id), mapping)
        assertEquals("imported", store.hosts.first().single().name)
        assertEquals("confirmed-token", store.hosts.first().single().launchToken)
    }

    @Test
    fun `removeHost preserves concurrent updates to other hosts and clears active selection`() = withStorage { store, dataStore ->
        val retained = host("retained")
        val removed = host("removed")
        store.upsertHost(retained)
        store.upsertHost(removed)
        dataStore.edit { it[RememberedHostsStorage.ACTIVE_CONNECTION_ID] = removed.id }
        interleaveProtocolUpdate(store, dataStore, retained) { store.removeHost(removed.id) }
        assertEquals(listOf(retained.id), store.hosts.first().map { it.id })
        assertNull(dataStore.data.first()[RememberedHostsStorage.ACTIVE_CONNECTION_ID])
    }

    @Test
    fun `concurrent upserts retain all independent hosts`() = withStorage { store, _ ->
        val expected = (1..24).map { host("host-$it").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG) }
        coroutineScope { expected.map { async { store.upsertHost(it) } }.awaitAll() }
        val saved = store.hosts.first()
        assertEquals(expected.map { it.id }.toSet(), saved.map { it.id }.toSet())
        assertTrue(saved.all { it.lastProtocol == HarnessProtocol.PARENT_CATALOG })
    }

    @Test
    fun `concurrent remembers of one endpoint return the same persisted id`() = withStorage { store, _ ->
        val remembered = coroutineScope {
            (1..24).map { async { store.rememberHost("name-$it", "same.example", 3080, false) } }.awaitAll()
        }
        assertEquals(1, remembered.map { it.id }.distinct().size)
        assertEquals(remembered.first().id, store.hosts.first().single().id)
    }

    @Test
    fun `confirmed protocol replacement and weak refresh keep existing merge policy`() = withStorage { store, _ ->
        val original = host("first").copy(lastProtocol = HarnessProtocol.PARENT_CATALOG)
        store.upsertHost(original)
        store.upsertHost(original.copy(lastProtocol = HarnessProtocol.LEGACY_SUBAGENTS))
        store.upsertHost(original.copy(lastProtocol = null))
        store.upsertHost(original.copy(lastProtocol = HarnessProtocol.UNDETERMINED))
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, store.hosts.first().single().lastProtocol)
    }

    @Test
    fun `malformed hosts retain empty fallback and a subsequent update repairs storage`() = withStorage { store, dataStore ->
        dataStore.edit { it[stringPreferencesKey("hosts_json")] = "not-json" }
        assertTrue(store.hosts.first().isEmpty())
        store.upsertHost(host("first"))
        assertEquals("first", store.hosts.first().single().id)
        assertFalse(dataStore.data.first()[stringPreferencesKey("hosts_json")].isNullOrEmpty())
    }

    @Test
    fun `remembering changed transport settings clears the old endpoint API`() = withStorage { store, _ ->
        val original = host("first").copy(lastProtocol = HarnessProtocol.LEGACY_SUBAGENTS)
        store.upsertHost(original)
        val unchanged = store.rememberHost("renamed", original.host, original.port, false)
        assertEquals(HarnessProtocol.LEGACY_SUBAGENTS, unchanged.lastProtocol)
        val changed = store.rememberHost("renamed", original.host, original.port, false, useTls = true)
        assertEquals(original.id, changed.id)
        assertNull(changed.lastProtocol)
        assertNull(store.hosts.first().single().lastProtocol)
    }

    /** Hold one writer before its real transaction, then commit a protocol update ahead of it. */
    private suspend fun interleaveProtocolUpdate(
        store: RememberedHostsStorage,
        dataStore: PausingDataStore,
        original: HostConfig,
        update: suspend () -> Unit,
    ) = coroutineScope {
        val gate = dataStore.pauseNextUpdate()
        val delayed = launch(start = CoroutineStart.UNDISPATCHED) { update() }
        try {
            gate.entered.await()
            store.upsertHost(original.copy(
                lastProtocol = HarnessProtocol.PARENT_CATALOG,
                launchToken = "confirmed-token",
            ))
        } finally {
            gate.release.complete(Unit)
        }
        delayed.join()
        assertEquals(HarnessProtocol.PARENT_CATALOG,
            store.hosts.first().single { it.id == original.id }.lastProtocol)
    }

    private fun withStorage(block: suspend (RememberedHostsStorage, PausingDataStore) -> Unit) = runBlocking {
        withTimeout(15_000) {
            val job = SupervisorJob()
            val file = files.newFolder().resolve("hosts.preferences_pb")
            val realDataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file },
            )
            val dataStore = PausingDataStore(realDataStore)
            try {
                block(RememberedHostsStorage(dataStore), dataStore)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    /** Only scheduling is controlled; all reads, transactions and persistence use a real DataStore. */
    private class PausingDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        class Gate {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        private var nextGate: Gate? = null
        override val data: Flow<Preferences> get() = delegate.data

        fun pauseNextUpdate(): Gate = Gate().also {
            check(nextGate == null)
            nextGate = it
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val gate = nextGate
            nextGate = null
            if (gate != null) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            return delegate.updateData(transform)
        }
    }

    private fun host(id: String) = HostConfig(id = id, name = id, host = "$id.example", port = 3080)
}
