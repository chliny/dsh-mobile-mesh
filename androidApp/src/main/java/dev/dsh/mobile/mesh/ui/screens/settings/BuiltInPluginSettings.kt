package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerPluginInfo
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
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
internal fun BuiltInPluginCard(card: BuiltInPluginSettings, busy: Boolean, settingsWritable: Boolean,
    viewModel: PluginManagerViewModel, plugins: List<PluginManagerPluginInfo>, locale: Locale,
) {
    var open by remember(card.kind) { mutableStateOf(false) }
    val readOnly = busy || !settingsWritable
    SettingsBlock {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(card.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { open = !open }) { Text(stringResource(R.string.plugins_native_settings)) }
        }
        // The contained plugins are configuration, not a summary: they stay hidden until the
        // settings are opened, the same as every other row inside this block.
        if (!open) return@SettingsBlock
        plugins.forEach { plugin ->
            // Same resolution the Web rows use: localized meta title, module name as the fallback.
            val title = plugin.meta.localizedMetaText("title", locale) ?: plugin.moduleName
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    plugin.readOnlyReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    plugin.diagnostic?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { viewModel.togglePlugin(plugin) },
                    modifier = Modifier.semantics { contentDescription = title },
                    enabled = !busy && plugin.patchId != null && plugin.readOnlyReason == null,
                )
            }
        }
        when (card.kind) {
            BuiltInPlugin.WebSearch -> WebSearchSpecialForm(card.sections, readOnly, viewModel)
            else -> card.sections.forEach { section ->
                if (card.sections.size > 1) Text(section.ns, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (section.ns == SUBAGENT_MODEL_SETTINGS) {
                    SubagentModelSpecialForm(section, readOnly, viewModel)
                } else {
                    SchemaFields(section, readOnly, viewModel::updateSetting, viewModel::resetSetting)
                }
            }
        }
    }
}
