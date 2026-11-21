package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
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
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerBundleRow
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerVersionExemptions
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.robolectric.RuntimeEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class PluginSettingsWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a scoped plugin opens settings and shows an editable field from its Host schema`() {
        openSettings()
        compose.onNodeWithText("Panel heading").assertExists()
        compose.onNodeWithText("\"Current heading\"").assertExists()
        compose.onNode(hasText("Save", ignoreCase = true) and hasClickAction()).assertIsEnabled()
    }

    @Test fun `inherited object is replaceable but cannot receive unsafe child edits`() {
        openSettings(inherited = """"base":{"display":{"heading":"Current heading"}},""")
        compose.onNodeWithText("Panel heading").assertDoesNotExist()
        compose.onNode(hasText("Edit JSON", ignoreCase = true) and hasClickAction()).assertIsEnabled()
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
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, viewModel = viewModel) } }

        compose.onNode(hasText("Experimental plugins") and hasClickAction()).performClick()
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
        compose.runOnIdle { state.value = state.value.copy(bundles = listOf(thirdParty, enabled), settings = settings) }
        toggle.assertIsOn()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNodeWithText("reviewMode").assertExists()
        toggle.performTouchInput { click() }
        compose.waitForIdle()
        Mockito.verify(viewModel).toggleBundle(enabled)
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
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, viewModel = viewModel) } }
        compose.onNode(hasText("Built-in plugin settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Web search").assertExists()
        compose.onNodeWithText("Shell").assertDoesNotExist()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNode(hasText("Use Search and Fetch APIs") and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
        runBlocking { Mockito.verify(api).settingsMutate("web", edits, 12L) }
    }

    @Test fun `built-in settings do not clear the last allowed model while selection is on`() {
        val settings = WireJson.decodeFromString<SettingsDescribeValue>("""
            {"writable":true,"hasDocument":true,"namespaces":[{
              "ns":"subagent-model-selection-settings","schema":{"uid":1,"refs":{
                "1":{"type":"object","dict":{"enabled":2,"allowedModels":3}},"2":{"type":"boolean"},
                "3":{"type":"array","inner":4},"4":{"type":"object","dict":{"provider":5,"model":5}},"5":{"type":"string"}
              }},"value":{"enabled":true,"allowedModels":[{"provider":"example","model":"model"}]},
              "base":{"enabled":false,"allowedModels":[]},"applies":"live","revision":2
             }]}
        """.trimIndent())
        val api = Mockito.mock(DshApiClient::class.java)
        runBlocking {
            Mockito.`when`(api.settingsMutate(Mockito.anyString(), Mockito.anyList<SettingsPathOpView>(), Mockito.anyLong()))
                .thenReturn(RpcResult.Ok(settings.namespaces.single()))
        }
        val viewModel = viewModelFor(settings, api)
        compose.setContent { DshTheme { PluginManagerScreen(onClose = {}, viewModel = viewModel) } }
        compose.onNode(hasText("Built-in plugin settings") and hasClickAction()).performClick()
        compose.onNodeWithText("Subagents").assertExists()
        compose.onNodeWithText("Settings", ignoreCase = true).performClick()
        compose.onNode(hasText("example / model") and hasClickAction()).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Add at least one allowed model before enabling model selection.").assertExists()
        runBlocking {
            Mockito.verify(api, Mockito.never()).settingsMutate(Mockito.anyString(), Mockito.anyList<SettingsPathOpView>(), Mockito.anyLong())
        }
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
