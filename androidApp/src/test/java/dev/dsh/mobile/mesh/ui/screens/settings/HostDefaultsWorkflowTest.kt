package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcHttpResponse
import dev.dsh.mobile.mesh.core.wire.RpcTransport
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.FULL_ACCESS_PRESET
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class HostDefaultsWorkflowTest {
    @Test fun `full access approval is scoped to the Host generation that opened the dialog`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = mock(ConnectionManager::class.java)
        val transport = SettingsHost()
        val api = DshApiClient(transport)
        val mux = mock(RemoteStreamMux::class.java)
        var generation = HostGeneration(HostDescription("/host"), "first", mux)
        `when`(manager.generation).thenAnswer { generation }
        `when`(manager.connectedApi).thenReturn(api)
        `when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState()))
        `when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        val viewModel = HostDefaultsSettingsViewModel(manager)
        try {
            viewModel.refresh()
            advanceUntilIdle()
            val first = requireNotNull(viewModel.state.value.permissionConfirmationKey)
            viewModel.setDefaultPermission(FULL_ACCESS_PRESET)
            advanceUntilIdle()
            assertEquals(0, transport.writes)

            generation = HostGeneration(HostDescription("/host"), "second", mux)
            viewModel.refresh()
            advanceUntilIdle()
            viewModel.setDefaultPermission(FULL_ACCESS_PRESET, acceptFullAccess = true, confirmationKey = first)
            advanceUntilIdle()
            assertEquals(0, transport.writes)

            val second = requireNotNull(viewModel.state.value.permissionConfirmationKey)
            viewModel.setDefaultPermission(FULL_ACCESS_PRESET, acceptFullAccess = true, confirmationKey = second)
            advanceUntilIdle()
            assertEquals(1, transport.writes)
            assertEquals(FULL_ACCESS_PRESET, viewModel.state.value.defaultPermission)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class SettingsHost : RpcTransport {
        var writes = 0
        private var selected = "workspace-write"
        private var revision = 4L

        private fun namespace() = """{"ns":"permission","schema":{},"value":{"defaultPreset":"$selected"},"applies":"live","revision":$revision}"""

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val request = WireJson.parseToJsonElement(body).jsonObject
            val id = request.getValue("rpcId").jsonPrimitive.content
            val args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            val result = when (path) {
                "/api/settings/describe" -> """{"writable":true,"hasDocument":true,"namespaces":[${namespace()}]}"""
                "/api/permissionPresets/catalog" -> """{"defaultOptions":[{"value":"workspace-write","name":"Workspace"},{"value":"danger-full-access","name":"Full access"}]}"""
                "/api/agentPresets/list" -> """{"presets":[]}"""
                "/api/settings/mutate" -> {
                    assertEquals("permission", args.getValue("ns").jsonPrimitive.content)
                    assertEquals(revision, args.getValue("expectedRevision").jsonPrimitive.content.toLong())
                    val operation = args.getValue("ops").jsonArray.single().jsonObject
                    assertEquals("defaultPreset", operation.getValue("path").jsonArray.single().jsonPrimitive.content)
                    selected = operation.getValue("value").jsonPrimitive.content
                    writes++
                    revision++
                    namespace()
                }
                else -> error("Unexpected endpoint $path")
            }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$id","result":{"ok":true,"value":$result}}""")
        }

        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("unused")
    }
}
