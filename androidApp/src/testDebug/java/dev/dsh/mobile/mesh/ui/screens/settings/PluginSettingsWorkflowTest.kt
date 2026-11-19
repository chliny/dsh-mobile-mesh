package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
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
