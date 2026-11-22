package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.mobile.mesh.core.wire.dto.AgentPresetEntry
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN")
class AgentSettingsDropdownWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `localized defaults keep the Host identifiers when selected`() {
        val viewModel = mock(HostDefaultsSettingsViewModel::class.java)
        val state = HostDefaultsSettingsUiState(
            permissionOptions = listOf(
                HostPermissionOption("workspace-write", "Workspace Write", null),
                HostPermissionOption("read-only", "read-only", null),
            ),
            defaultPermission = "workspace-write",
            permissionWritable = true,
            agentPresets = listOf(
                AgentPresetEntry("standard", isDefault = true),
                AgentPresetEntry("minimal", isDefault = false),
            ),
            selectedAgentPreset = "standard",
            agentPresetWritable = true,
        )
        compose.setContent { DshTheme { HostDefaultsContent(state, viewModel) } }

        compose.onNodeWithText("工作区内修改").performClick()
        compose.onNodeWithText("仅可查看").performClick()
        verify(viewModel).setDefaultPermission("read-only")

        compose.onNodeWithText("标准模式").performClick()
        compose.onNodeWithText("极简模式").performClick()
        compose.onNodeWithText("设为默认", substring = true).performClick()
        verify(viewModel).selectDefaultAgentPreset("minimal")
    }
}
