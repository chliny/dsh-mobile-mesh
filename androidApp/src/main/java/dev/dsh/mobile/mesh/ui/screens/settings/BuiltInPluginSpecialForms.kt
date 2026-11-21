package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.components.ToggleRow
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Only the selected provider's fields are shown; the API key is not a settings value. */
@Composable
internal fun WebSearchSpecialForm(sections: List<SettingsNamespaceView>, readOnly: Boolean,
    viewModel: PluginManagerViewModel,
) {
    val snapshot by viewModel.state.collectAsStateWithLifecycle()
    val selection = sections.firstOrNull { it.ns == WEB_SETTINGS }
    val apiMode = ((selection?.value as? JsonObject)?.get("searchProvider") as? JsonPrimitive)?.content == DEEPSEEK_SEARCH_API
    val active = sections.firstOrNull { it.ns == if (apiMode) WEB_API_SETTINGS else WEB_MESSAGES_SETTINGS }
    selection?.let { scope ->
        ToggleRow(stringResource(R.string.plugins_native_builtin_web_api), checked = apiMode, enabled = !readOnly) {
            viewModel.setBuiltInWebSearchApi(scope, !apiMode)
        }
    }
    Text(stringResource(if (apiMode) R.string.plugins_native_web_search_api_mode else R.string.plugins_native_web_messages_mode),
        color = DsTheme.colors.labelSecondary)
    if (active != null) {
        // Hide the credential reference and inactive provider's settings; only this mode's
        // endpoint and request limit belong on the built-in Web search form.
        SchemaFields(active, readOnly, viewModel::updateSetting, viewModel::resetSetting,
            allowedFields = setOf("baseURL", if (apiMode) "count" else "maxUses"),
            labels = mapOf("baseURL" to R.string.plugins_native_web_base_url,
                "count" to R.string.plugins_native_web_count, "maxUses" to R.string.plugins_native_web_max_uses))
        val ref = (active.value as? JsonObject)?.get("apiKeyEnv")
            ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: "DEEPSEEK_API_KEY"
        val info = snapshot.webCredential.takeIf { snapshot.webCredentialRef == ref }
        Text(stringResource(R.string.plugins_native_web_key_status) + ": " + stringResource(when {
            info == null -> R.string.plugins_native_loading
            info.configured -> R.string.plugins_native_web_key_configured
            else -> R.string.plugins_native_web_key_missing
        }))
        var editing by remember(active.ns, ref, active.revision) { mutableStateOf<WebCredentialLease?>(null) }
        OutlinedButton(onClick = { editing = viewModel.webCredentialLease(active) },
            enabled = !readOnly && info?.writable == true) {
            Text(stringResource(R.string.plugins_native_web_key_edit))
        }
        if (info?.writable == false) Text(stringResource(R.string.plugins_native_web_key_readonly),
            color = DsTheme.colors.labelSecondary)
        val lease = editing
        if (lease != null && !readOnly && info?.writable == true) {
            var input by remember(lease) { mutableStateOf("") }
            fun dismiss() { input = ""; editing = null }
            AlertDialog(onDismissRequest = ::dismiss,
                title = { Text(stringResource(R.string.plugins_native_web_key_edit)) },
                text = { OutlinedTextField(value = input, onValueChange = { input = it },
                    label = { Text(stringResource(R.string.plugins_native_web_key_input)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = {
                    val submitted = input
                    dismiss()
                    viewModel.setWebSearchCredential(submitted, lease)
                }) { Text(stringResource(R.string.plugins_native_save)) } },
                dismissButton = { TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.plugins_native_cancel))
                } })
        }
    }
}

/** Host catalog candidates plus saved entries missing from the current catalog. */
@Composable
internal fun SubagentModelSpecialForm(namespace: SettingsNamespaceView, readOnly: Boolean,
    viewModel: PluginManagerViewModel,
) {
    val snapshot by viewModel.state.collectAsStateWithLifecycle()
    val value = namespace.value as? JsonObject
    val enabled = (value?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
    val saved = (value?.get("allowedModels") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .mapNotNull { route ->
            val provider = (route["provider"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val model = (route["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (provider.isNullOrBlank() || model.isNullOrBlank()) null else provider to model
        }.toSet()
    ToggleRow(stringResource(R.string.plugins_native_builtin_subagent_model_selection), checked = enabled,
        hint = if (!enabled && saved.isEmpty()) stringResource(R.string.plugins_native_builtin_subagent_models_required) else null,
        enabled = !readOnly && (enabled || saved.isNotEmpty())) {
        viewModel.setBuiltInSubagentModelSelection(namespace, !enabled)
    }
    Text(stringResource(R.string.plugins_native_subagent_models))
    val catalog = snapshot.modelCatalog
    if (snapshot.modelCatalogUnavailable) Text(stringResource(R.string.plugins_native_subagent_models_unavailable),
        color = DsTheme.colors.labelSecondary)
    else if (catalog == null) Text(stringResource(R.string.plugins_native_loading),
        color = DsTheme.colors.labelSecondary)
    else if (catalog.failures.isNotEmpty()) Text(stringResource(R.string.plugins_native_subagent_models_partial),
        color = DsTheme.colors.labelSecondary)
    val advertised = catalog?.groups.orEmpty().flatMap { group -> group.models.map { group.id to it.id } }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        catalog?.groups.orEmpty().forEach { group ->
            if (group.models.isNotEmpty()) Text(group.name, color = DsTheme.colors.labelSecondary)
            group.models.forEach { model ->
                ToggleRow(model.name + " (" + model.id + ")", checked = (group.id to model.id) in saved,
                    enabled = !readOnly) { viewModel.toggleSubagentModel(namespace, group.id, model.id) }
            }
        }
        (saved - advertised).forEach { (provider, model) ->
            ToggleRow("$provider / $model", checked = true,
                hint = stringResource(R.string.plugins_native_subagent_unknown_model), enabled = !readOnly) {
                viewModel.toggleSubagentModel(namespace, provider, model)
            }
        }
    }
}
