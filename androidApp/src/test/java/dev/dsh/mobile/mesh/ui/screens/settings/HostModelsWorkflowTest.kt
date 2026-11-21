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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class HostModelsWorkflowTest {
    @Test fun `custom model discovery and add persist the provider while storing the key separately`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = mock(ConnectionManager::class.java)
        val host = ModelHost()
        val api = DshApiClient(host)
        val generation = HostGeneration(HostDescription("/host"), "g1", mock(RemoteStreamMux::class.java))
        `when`(manager.generation).thenReturn(generation)
        `when`(manager.connectedApi).thenReturn(api)
        `when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState()))
        `when`(manager.connectedGenerations).thenReturn(MutableSharedFlow<HostGeneration>())
        val viewModel = HostModelsViewModel(manager)
        try {
            viewModel.refresh()
            advanceUntilIdle()
            val settings = requireNotNull(viewModel.state.value.customNamespace)
            viewModel.discover(settings, null, "https://models.example/v1", "openai-completions", INPUT_KEY)
            advanceUntilIdle()
            val model = viewModel.state.value.discovered.orEmpty().single()
            assertEquals("demo", model.id)
            assertFalse(host.hasProfile)
            assertFalse(host.keyStored)

            viewModel.saveProvider(settings, null, "example-provider", mapOf(
                "baseURL" to JsonPrimitive("https://models.example/v1"),
                "api" to JsonPrimitive("openai-completions"),
                "models" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(model.id))))),
            ), INPUT_KEY)
            advanceUntilIdle()
            val provider = viewModel.state.value.providers.single()
            assertEquals("example-provider", provider.entry.provider)
            assertTrue(provider.credential?.configured == true)
            assertTrue(host.keyStored)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class ModelHost : RpcTransport {
        private var profile: JsonObject? = null
        val hasProfile get() = profile != null
        var keyStored = false
            private set
        private var revision = 12L

        private fun namespace() = """{"ns":"llm-pi-ai","schema":{},"value":{"providers":${providers()}},"user":{"providers":${providers()}},"applies":"live","revision":$revision}"""
        private fun providers() = profile?.let { """{"example-provider":$it}""" } ?: "{}"

        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val request = WireJson.parseToJsonElement(body).jsonObject
            val id = request.getValue("rpcId").jsonPrimitive.content
            val args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            val result = when (path) {
                "/api/settings/describe" -> """{"writable":true,"hasDocument":true,"namespaces":[${namespace()}]}"""
                "/api/llm/listProviders" -> "[]"
                "/api/llm/listConfigurableProviders" -> if (hasProfile)
                    """[{"provider":"example-provider","displayName":"Example","settingsNs":"llm-pi-ai","settingsPath":["providers","example-provider"],"declared":true}]""" else "[]"
                "/api/llm/discoverModels" -> {
                    assertEquals(INPUT_KEY, args.getValue("request").jsonObject.getValue("apiKey").jsonPrimitive.content)
                    """[{"id":"demo","inputModalities":["text","image"]}]"""
                }
                "/api/credentials/describe" -> """{"EXAMPLE_PROVIDER_API_KEY":{"configured":$keyStored,"writable":true}}"""
                "/api/settings/mutate" -> {
                    assertFalse(body.contains(INPUT_KEY))
                    assertEquals(revision, args.getValue("expectedRevision").jsonPrimitive.content.toLong())
                    val operation = args.getValue("ops").jsonArray.single().jsonObject
                    assertEquals(listOf("providers", "example-provider"), operation.getValue("path").jsonArray.map { it.jsonPrimitive.content })
                    profile = operation.getValue("value").jsonObject
                    assertEquals("EXAMPLE_PROVIDER_API_KEY", profile?.get("apiKeyEnv")?.jsonPrimitive?.content)
                    revision++
                    namespace()
                }
                "/api/credentials/set" -> {
                    assertTrue(hasProfile)
                    assertEquals("EXAMPLE_PROVIDER_API_KEY", args.getValue("ref").jsonPrimitive.content)
                    assertEquals(INPUT_KEY, args.getValue("value").jsonPrimitive.content)
                    keyStored = true
                    "{}"
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

    companion object { private const val INPUT_KEY = "fake-key-for-test" }
}
