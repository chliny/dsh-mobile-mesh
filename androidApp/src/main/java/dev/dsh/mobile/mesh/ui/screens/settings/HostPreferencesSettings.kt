package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.ToggleRow
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Composable
internal fun HostPreferencesContent(state: HostPreferencesState, viewModel: HostPreferencesViewModel) {
    val settings = state.settings
    val transcript = settings.namespace("ui-chat")
    val workView = settings.namespace("ui-settings")
    val writable = settings?.writable == true && !state.busy && !state.loading
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        if (state.loading) Text(stringResource(R.string.common_loading), color = DsTheme.colors.labelSecondary)
        state.error?.let { Text(it, color = DsTheme.colors.error) }
        if (!state.loading && state.error == null && transcript == null && workView == null) {
            Text(stringResource(R.string.host_preferences_unavailable), color = DsTheme.colors.labelSecondary)
        }
        if (settings?.writable == false) Text(stringResource(R.string.plugins_native_read_only), color = DsTheme.colors.labelSecondary)
        if (transcript != null) {
            val display = when (val value = transcript.stringValue("transcriptView")) {
                null, "normal", "expanded" -> "detailed"
                else -> value
            }
            HostPreferenceChoice(R.string.host_preferences_display, display, writable, listOf(
                "compact" to R.string.host_preferences_compact,
                "standard" to R.string.host_preferences_standard,
                "detailed" to R.string.host_preferences_detailed,
                "verbose" to R.string.host_preferences_verbose,
            )) { viewModel.set(transcript, "transcriptView", JsonPrimitive(it)) }
            if (display != "verbose") {
                if (transcript.hasField("collapseTiming")) {
                    HostPreferenceChoice(R.string.host_preferences_collapse, transcript.stringValue("collapseTiming") ?: "completion",
                        writable, listOf("completion" to R.string.host_preferences_on_complete,
                            "next-input" to R.string.host_preferences_on_next_message)) {
                        viewModel.set(transcript, "collapseTiming", JsonPrimitive(it))
                    }
                }
                if (transcript.hasField("foldedReplies")) {
                    HostPreferenceChoice(R.string.host_preferences_replies, transcript.stringValue("foldedReplies") ?: "all",
                        writable, listOf("all" to R.string.host_preferences_all_replies,
                            "final" to R.string.host_preferences_final_reply)) {
                        viewModel.set(transcript, "foldedReplies", JsonPrimitive(it))
                    }
                }
            }
        }
        if (workView != null && workView.hasField("enabled")) {
            val enabled = ((workView.value as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true
            ToggleRow(stringResource(R.string.host_preferences_work_view), checked = enabled,
                hint = stringResource(R.string.host_preferences_work_view_hint), enabled = writable) {
                viewModel.set(workView, "enabled", JsonPrimitive(!enabled))
            }
        }
    }
}

/** Older hosts can serve the namespace while omitting a field that was added later. */
private fun SettingsNamespaceView.hasField(field: String): Boolean {
    if ((value as? JsonObject)?.containsKey(field) == true) return true
    val envelope = schema as? JsonObject ?: return false
    val root = if (envelope["refs"] is JsonObject) {
        val id = (envelope["uid"] as? JsonPrimitive)?.content ?: return false
        (envelope["refs"] as JsonObject)[id] as? JsonObject
    } else envelope
    return (root?.get("dict") as? JsonObject)?.containsKey(field) == true
}

@Composable
private fun HostPreferenceChoice(label: Int, current: String, writable: Boolean, options: List<Pair<String, Int>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second?.let { stringResource(it) } ?: current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = DsSpacing.small))
        Box(Modifier.weight(1f, fill = false)) {
            OutlinedButton(onClick = { expanded = true }, enabled = writable) {
                Text(currentLabel, style = DsType.small13)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = {
                        expanded = false
                        if (current != value && writable) select(value)
                    })
                }
            }
        }
    }
}

@Composable
internal fun HostSettingsPage(title: String, onClose: () -> Unit, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val colors = DsTheme.colors
    Surface(Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DsSpacing.comfortable).padding(top = DsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), onClick = onClose)
                Text(title, Modifier.weight(1f), style = DsType.large20, color = colors.labelPrimary)
                action()
            }
            CompositionLocalProvider(LocalTextStyle provides DsType.std14) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpacing.comfortable).padding(top = DsSpacing.comfortable, bottom = DsSpacing.xlarge),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable)) { content() }
            }
        }
    }
}
