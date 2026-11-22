package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostGeneration
import dev.dsh.mobile.mesh.core.wire.RemoteStreamMux
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.HostDescription
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalog
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleRow
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerVersionExemptions
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.robolectric.RuntimeEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class PluginSettingsWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a scoped plugin opens settings and shows an editable field from its Host schema`() {
        openSettings()
        compose.onNodeWithText("Panel heading").assertExists()
        compose.onNodeWithText("Current heading").assertExists()
        compose.onNodeWithContentDescription("Edit: heading").assertIsEnabled()
    }

    @Test fun `inherited object is replaceable but cannot receive unsafe child edits`() {
        openSettings(inherited = """"base":{"display":{"heading":"Current heading"}},""")
        compose.onNodeWithText("Panel heading").assertDoesNotExist()
        // A composite keeps the wholesale replacement editor, now under the shared Edit action.
        compose.onNodeWithContentDescription("Edit: display").assertIsEnabled()
    }

    @Test fun `redacted secret dictionary is not treated as a writable raw object`() {
        openSettings(WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"example","schema":{"uid":3,"refs":{
                "3":{"type":"object","dict":{"credentials":2}},
                "2":{"type":"dict","inner":4},
                "4":{"type":"union","list":[1,5]},
                "1":{"type":"string","meta":{"role":"secret"}},
                "5":{"type":"number"}
              }},"value":{"credentials":{}},"user":{"credentials":{}},
              "secrets":[{"path":["credentials","token"],"set":true}],"applies":"live","revision":4
            }]}
        """.trimIndent()))
        compose.onNodeWithText("Read-only", ignoreCase = true).assertExists()
        compose.onNodeWithText("Add entry", ignoreCase = true).assertDoesNotExist()
    }

    @Test fun `experimental directory can enable an official bundle and open its Host settings`() {
        val experimental = PluginManagerBundleInfo(
            name = "@deepseek-ai/dsh-experimental-auto-review", official = true,
            availability = "installation", enabled = false,
        )
        val thirdParty = PluginManagerBundleInfo(name = "third-party-tool", availability = "profile", enabled = true)
        val state = MutableStateFlow(PluginManagerUiState(bundles = listOf(thirdParty, experimental)))
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(state)
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.Experimental, viewModel = viewModel) } }

        compose.onNodeWithText(thirdParty.name).assertDoesNotExist()
        val toggle = compose.onNodeWithContentDescription(experimental.name)
        toggle.assertIsOff().assertIsEnabled().performTouchInput { click() }
        compose.waitForIdle()
        Mockito.verify(viewModel).toggleBundle(experimental)
        toggle.assertIsOff()

        // Settings become available when the Host reports the loaded row and its namespace.
        val enabled = experimental.copy(enabled = true, rows = listOf(
            PluginManagerBundleRow("auto-review", "auto-review-host", entryId = "include:auto-review"),
        ))
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"auto-review","schema":{"uid":1,"refs":{"1":{"type":"object","dict":{"reviewMode":2}},
              "2":{"type":"string"}}},"value":{"reviewMode":"manual"},"applies":"live","revision":1
            }]}
        """.trimIndent())
        val loadedPlugin = WireJson.decodeFromString<PluginManagerPluginInfo>(
            """{"entryId":"include:auto-review","moduleName":"auto-review-host","enabled":true}""")
        compose.runOnIdle { state.value = state.value.copy(
            bundles = listOf(thirdParty, enabled), plugins = listOf(loadedPlugin), settings = settings) }
        toggle.assertIsOn()
        // Contained plugins are configuration: they stay hidden until the package's settings open.
        compose.onNodeWithContentDescription("auto-review-host").assertDoesNotExist()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNodeWithContentDescription("auto-review-host").assertIsOn()
        compose.onNodeWithText("reviewMode:").assertExists()
        toggle.performTouchInput { click() }
        compose.waitForIdle()
        Mockito.verify(viewModel).toggleBundle(enabled)
    }

    @Test fun `experimental directory shows the Host title for the app language and falls back to English description`() {
        val bundle = WireJson.decodeFromString<PluginManagerBundleInfo>("""
            {"name":"example-experimental","official":true,"availability":"installation","enabled":false,
             "meta":{"title":{"en":"Example tool","es":"Herramienta de ejemplo"},
                     "description":{"en":"An experimental tool from the Host"}}}
        """.trimIndent())
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(MutableStateFlow(PluginManagerUiState(bundles = listOf(bundle))))
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("es-MX")) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.Experimental, viewModel = viewModel) }
            }
        }

        compose.onNodeWithText("Herramienta de ejemplo").assertExists()
        compose.onNodeWithText("An experimental tool from the Host").assertExists()
    }

    @Test fun `built-in web search switches both providers with the current Host revision`() {
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[
              {"ns":"web","schema":{"uid":1,"refs":{"1":{"type":"object","dict":{"searchProvider":2,"fetchProvider":2}},"2":{"type":"string"}}},
               "value":{"searchProvider":"deepseek-official","fetchProvider":"http"},"applies":"live","revision":12},
              {"ns":"web-search-deepseek","schema":{"type":"object","dict":{"maxUses":{"type":"number"}}},
               "value":{"maxUses":3},"applies":"live","revision":1},
              {"ns":"web-deepseek-api","schema":{"type":"object","dict":{"count":{"type":"number"}}},
               "value":{"count":10},"applies":"live","revision":1}
            ]}
        """.trimIndent())
        val edits = listOf(
            SettingsPathOpView.Set(listOf("searchProvider"), JsonPrimitive("deepseek-search-api")),
            SettingsPathOpView.Set(listOf("fetchProvider"), JsonPrimitive("deepseek-fetch-api")),
        )
        val api = Mockito.mock(DshApiClient::class.java)
        runBlocking {
            Mockito.`when`(api.settingsMutate(Mockito.anyString(), Mockito.anyList<SettingsPathOpView>(), Mockito.anyLong()))
                .thenReturn(RpcResult.Ok(settings.namespaces.first()))
        }
        val viewModel = viewModelFor(settings, api)
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.BuiltIn, viewModel = viewModel) } }
        compose.onNodeWithText("Web search").assertExists()
        compose.onNodeWithText("Shell").assertDoesNotExist()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNode(hasText("Use Search and Fetch APIs") and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
        runBlocking { Mockito.verify(api).settingsMutate("web", edits, 12L) }
    }

    @Test fun `built-in setting edits only after opening the field and resets independently`() {
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"web-search-deepseek","schema":{"type":"object","dict":{"maxUses":{"type":"number"}}},
              "value":{"maxUses":3},"applies":"live","revision":7
            }]}
        """.trimIndent())
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(MutableStateFlow(PluginManagerUiState(settings = settings)))
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.BuiltIn, viewModel = viewModel) } }
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()

        val field = compose.onNode(hasText("Max searches per request") and hasSetTextAction())
        field.assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit: Max searches per request").performClick()
        field.performTextReplacement("5")
        compose.onNodeWithContentDescription("Save: Max searches per request").performClick()
        field.assertDoesNotExist()
        val namespace = settings.namespaces.single()
        Mockito.verify(viewModel).updateSetting(namespace, listOf("maxUses"), JsonPrimitive(5))
        compose.onNodeWithContentDescription("Reset: Max searches per request").performClick()
        Mockito.verify(viewModel).resetSetting(namespace, listOf("maxUses"))
    }

    @Test fun `subagent picker enables on first selection and clearing all disables it safely`() {
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"subagent-model-selection-settings","schema":{"type":"object","dict":{}},
              "value":{"enabled":false,"allowedModels":[]},"applies":"live","revision":2
            }]}
        """.trimIndent())
        val catalog = WireJson.decodeFromString<ModelCatalog>("""
            {"default":{"provider":"example","model":"alpha"},"groups":[{"id":"example","name":"Example","models":[
              {"id":"alpha","name":"Alpha"},{"id":"beta","name":"Beta"}
            ]}]}
        """.trimIndent())
        val api = Mockito.mock(DshApiClient::class.java)
        val viewModel = viewModelFor(settings, api)
        val section = settings.namespaces.single()
        val allowed = Json.parseToJsonElement("""[{"provider":"example","model":"alpha"},{"provider":"example","model":"beta"}]""")
        val enabled = section.copy(revision = 3, value = JsonObject(mapOf("enabled" to JsonPrimitive(true), "allowedModels" to allowed)))
        val cleared = section.copy(revision = 4)
        var current = settings
        runBlocking {
            Mockito.`when`(api.sessionModelCatalog()).thenReturn(RpcResult.Ok(catalog))
            Mockito.`when`(api.settingsDescribe()).thenAnswer { RpcResult.Ok(current) }
            Mockito.`when`(api.settingsMutate(Mockito.anyString(), Mockito.anyList<SettingsPathOpView>(), Mockito.anyLong()))
                .thenAnswer { request ->
                    val result = if (request.getArgument<Long>(2) == 2L) enabled else cleared
                    current = current.copy(namespaces = listOf(result))
                    RpcResult.Ok(result)
                }
        }
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.BuiltIn, viewModel = viewModel) } }
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNodeWithText("Select all models").assertDoesNotExist()
        compose.onNode(hasText("Allow subagent model selection") and hasClickAction()).performClick()
        runBlocking {
            Mockito.verify(api, Mockito.never()).settingsMutate(Mockito.anyString(), Mockito.anyList<SettingsPathOpView>(), Mockito.anyLong())
        }
        compose.onNode(hasText("Search") and hasSetTextAction()).performScrollTo().performTextInput("Alpha")
        compose.onNodeWithText("Example / Alpha (alpha)").assertIsOff()
        compose.onNodeWithText("Example / Beta (beta)").assertDoesNotExist()
        compose.onNodeWithText("Select all models").performScrollTo().performClick()
        compose.onNodeWithText("Example / Alpha (alpha)").assertIsOn()
        compose.onNodeWithText("Deselect all models").performScrollTo().performClick()
        compose.onNodeWithText("Example / Alpha (alpha)").assertIsOff()
        runBlocking {
            Mockito.verify(api).settingsMutate(section.ns, listOf(
                SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(true)),
                SettingsPathOpView.Set(listOf("allowedModels"), allowed),
            ), 2L)
            Mockito.verify(api).settingsMutate(section.ns, listOf(
                SettingsPathOpView.Set(listOf("enabled"), JsonPrimitive(false)),
                SettingsPathOpView.Set(listOf("allowedModels"), (cleared.value as JsonObject).getValue("allowedModels")),
            ), 3L)
        }
    }

    @Test fun `the plugin list itself no longer links to the managed directories`() {
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(MutableStateFlow(PluginManagerUiState()))
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, viewModel = viewModel) } }

        compose.onNode(hasText("Built-in plugins") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Experimental plugins") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun `a built-in card shows its plugins with localized names and its own switches`() {
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"subagent","schema":{"type":"object","dict":{}},"value":{},"applies":"live","revision":1
            }]}
        """.trimIndent())
        val row = PluginManagerBundleRow("subagent", "subagent-host", entryId = "include:subagent")
        val bundle = PluginManagerBundleInfo(name = "@deepseek-ai/dsh-subagent", availability = "profile",
            enabled = true, rows = listOf(row))
        val plugin = WireJson.decodeFromString<PluginManagerPluginInfo>("""
            {"entryId":"include:subagent","moduleName":"subagent-host","enabled":true,"patchId":"subagent",
             "meta":{"title":{"en":"Subagent tool","es":"Herramienta subagente"}}}
        """.trimIndent())
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(MutableStateFlow(PluginManagerUiState(
            bundles = listOf(bundle), plugins = listOf(plugin), settings = settings)))
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, initialDirectory = PluginDirectory.BuiltIn, viewModel = viewModel) } }

        compose.onNodeWithText("Subagents").assertExists()
        compose.onNodeWithText("subagent-host").assertDoesNotExist()
        // Contained plugins are configuration: hidden until the group's settings are opened.
        compose.onNodeWithText("Subagent tool").assertDoesNotExist()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNodeWithText("Subagent tool").assertExists()
        compose.onNodeWithContentDescription("Subagent tool").assertIsOn().assertIsEnabled().performTouchInput { click() }
        Mockito.verify(viewModel).togglePlugin(plugin)
    }

    private fun viewModelFor(settings: SettingsDescribeValue, api: DshApiClient): PluginManagerViewModel {
        val manager = Mockito.mock(ConnectionManager::class.java)
        Mockito.`when`(manager.state).thenReturn(MutableStateFlow(ConnectionUiState(
            phase = ConnectionPhase.CONNECTED, host = HostConfig("test-host", "Test host", "example.test", 3080),
        )))
        Mockito.`when`(manager.generation).thenReturn(HostGeneration(HostDescription("/test"), "generation",
            Mockito.mock(RemoteStreamMux::class.java)))
        Mockito.`when`(manager.connectedApi).thenReturn(api)
        runBlocking {
            Mockito.`when`(api.listPluginManagerBundles()).thenReturn(RpcResult.Ok(emptyList()))
            Mockito.`when`(api.listPluginManagerPlugins()).thenReturn(RpcResult.Ok(emptyList()))
            Mockito.`when`(api.settingsDescribe()).thenReturn(RpcResult.Ok(settings))
            Mockito.`when`(api.listPluginManagerVersionExemptions()).thenReturn(RpcResult.Ok(PluginManagerVersionExemptions(emptyMap(), emptyList())))
        }
        return PluginManagerViewModel(manager, RuntimeEnvironment.getApplication())
    }

    private fun openSettings(inherited: String = "") = openSettings(WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"example","schema":{"uid":53,"refs":{
                "53":{"type":"object","dict":{"display":52},"meta":{}},
                "52":{"type":"object","dict":{"heading":51},"meta":{}},
                "51":{"type":"string","meta":{"description":"Panel heading"}}
              }},"value":{"display":{"heading":"Current heading"}},${inherited}"applies":"live","revision":7
            }]}
        """.trimIndent()))

    private fun openSettings(settings: SettingsDescribeValue) {
        val plugin = PluginManagerPluginInfo("include:example", "example-plugin", enabled = true, patchId = "example")
        val viewModel = Mockito.mock(PluginManagerViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(MutableStateFlow(PluginManagerUiState(
            plugins = listOf(plugin), settings = settings,
        )))
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, viewModel = viewModel) } }

        compose.onNode(hasText("Plugins") and hasClickAction()).performClick()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
    }
}
