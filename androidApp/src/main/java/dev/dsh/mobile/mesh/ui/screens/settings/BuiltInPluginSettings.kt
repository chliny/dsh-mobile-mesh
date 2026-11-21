package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.util.Locale

internal const val WEB_SETTINGS = "web"
internal const val WEB_MESSAGES_SETTINGS = "web-search-deepseek"
internal const val WEB_API_SETTINGS = "web-deepseek-api"
internal const val SUBAGENT_MODEL_SETTINGS = "subagent-model-selection-settings"
internal const val DEEPSEEK_SEARCH_API = "deepseek-search-api"

// These are the built-in Web settings slots, not a classification of Host inventory entries.
// A card is present only when settings/describe serves one of the namespaces it owns.
internal enum class BuiltInPlugin(val title: Int) {
    Subagent(R.string.plugins_native_builtin_subagent),
    WebSearch(R.string.plugins_native_builtin_web_search),
    AgentLoop(R.string.plugins_native_builtin_agent_loop),
    Shell(R.string.plugins_native_builtin_shell),
}

internal data class BuiltInPluginSettings(
    val kind: BuiltInPlugin,
    val title: String,
    val sections: List<SettingsNamespaceView>,
)

@Composable
internal fun builtInPluginCards(namespaces: List<SettingsNamespaceView>, query: String): List<BuiltInPluginSettings> {
    val served = namespaces.associateBy { it.ns }
    return BuiltInPlugin.entries.mapNotNull { plugin ->
        val sections = when (plugin) {
            BuiltInPlugin.Subagent -> listOfNotNull(served["subagent"], served[SUBAGENT_MODEL_SETTINGS])
            BuiltInPlugin.WebSearch -> served[WEB_MESSAGES_SETTINGS]?.let { messages ->
                val selection = served[WEB_SETTINGS]
                val api = served[WEB_API_SETTINGS]
                if (selection != null && api != null) listOf(selection, messages, api) else listOf(messages)
            }.orEmpty()
            BuiltInPlugin.AgentLoop -> listOfNotNull(served["agent-loop"])
            BuiltInPlugin.Shell -> listOfNotNull(served["pwsh-sandbox"] ?: served["bash-sandbox"])
        }
        val title = stringResource(plugin.title)
        if (sections.isEmpty() || (query.isNotEmpty() && !title.lowercase(Locale.ROOT).contains(query) &&
            sections.none { it.ns.lowercase(Locale.ROOT).contains(query) })) null
        else BuiltInPluginSettings(plugin, title, sections)
    }
}

@Composable
internal fun BuiltInPluginCard(card: BuiltInPluginSettings, readOnly: Boolean, viewModel: PluginManagerViewModel) {
    val colors = DsTheme.colors
    var open by remember(card.kind) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(1.dp, colors.borderL1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.title, style = DsType.large20)
        OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.plugins_native_settings)) }
        if (open) when (card.kind) {
            BuiltInPlugin.WebSearch -> WebSearchSpecialForm(card.sections, readOnly, viewModel)
            else -> card.sections.forEach { section ->
                if (card.sections.size > 1) Text(section.ns, color = colors.labelSecondary)
                if (section.ns == SUBAGENT_MODEL_SETTINGS) {
                    SubagentModelSpecialForm(section, readOnly, viewModel)
                } else {
                    SchemaFields(section, readOnly, viewModel::updateSetting, viewModel::resetSetting)
                }
            }
        }
    }
}
