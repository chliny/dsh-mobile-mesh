package dev.dsh.mobile.mesh.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HarnessCookieFlowTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `legacy sessions survive target edits and sharing until last connection is removed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { files.newFile("sessions.preferences_pb") }
            val hosts = RememberedHostsStorage(data)
            val sessions = HarnessSessionStore(data, OkHttpClient())
            val direct = HostConfig("direct", "direct", "10.0.0.9", 3080)
            val gateway = HostConfig("gateway", "gateway", "10.0.0.1", 3080, sshEnabled = true, sshDshHost = "10.0.0.9")
            hosts.upsertHost(direct)
            hosts.upsertHost(gateway)
            data.edit { it[stringPreferencesKey("harness_sessions_json")] = "{\"direct\":\"session=old\"}" }

            val changed = direct.copy(host = "10.0.0.8")
            hosts.upsertHost(changed)
            assertNull(sessions.cookie(changed))
            assertEquals("session=old", sessions.cookie(gateway))
            assertEquals(mapOf("gateway" to "session=old"), sessions.exportCookies(listOf(changed, gateway)))
            sessions.importCookies(mapOf(changed to "session=new"))
            assertEquals("session=new", sessions.cookie(changed))
            hosts.removeHost(gateway.id)
            assertNull(sessions.cookie(gateway))
            assertEquals("session=new", sessions.cookie(changed))
        } finally {
            scope.cancel()
        }
    }
}
