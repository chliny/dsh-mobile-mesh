package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.components.ToggleRow
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
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
        val label = stringResource(R.string.plugins_native_web_key_status)
        val canEdit = !readOnly && info?.writable == true
        var lease by remember(snapshot.target, active, ref, canEdit) { mutableStateOf<WebCredentialLease?>(null) }
        var input by remember(lease) { mutableStateOf("") }
        fun dismiss() { input = ""; lease = null }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$label: " + stringResource(when {
                info == null -> R.string.plugins_native_loading
                info.configured -> R.string.plugins_native_web_key_configured
                else -> R.string.plugins_native_web_key_missing
            }), Modifier.weight(1f), style = DsType.small13)
            SettingRowButton(if (lease == null) R.string.plugins_native_modify else R.string.plugins_native_save, label,
                enabled = canEdit && (lease == null || input.isNotBlank())) {
                val current = lease
                if (current == null) lease = viewModel.webCredentialLease(active)
                else {
                    val submitted = input
                    dismiss()
                    viewModel.setWebSearchCredential(submitted, current)
                }
            }
        }
        if (info?.writable == false) Text(stringResource(R.string.plugins_native_web_key_readonly),
            color = DsTheme.colors.labelSecondary)
        if (lease != null) {
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text(stringResource(R.string.plugins_native_web_key_input)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            SettingRowButton(R.string.plugins_native_cancel, label, enabled = !readOnly) { dismiss() }
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
    // A local picker can be open while the Host still has selection disabled. The first check
    // enables it atomically; clearing the last check leaves the picker open but disables it on Host.
    var pickingWhileDisabled by remember(snapshot.target, namespace.ns) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) pickingWhileDisabled = false }
    val pickerOpen = enabled || pickingWhileDisabled
    val allowedModels = value?.get("allowedModels")
    val validSelection = allowedModels == null || (allowedModels is JsonArray && allowedModels.hasUniqueModelRoutes())
    val canEdit = !readOnly && validSelection
    ToggleRow(stringResource(R.string.plugins_native_builtin_subagent_model_selection), checked = pickerOpen,
        hint = if (pickingWhileDisabled && !enabled) stringResource(R.string.plugins_native_builtin_subagent_models_required) else null,
        enabled = !readOnly && (enabled || validSelection)) {
        when {
            enabled -> {
                pickingWhileDisabled = false
                viewModel.setBuiltInSubagentModelSelection(namespace, false)
            }
            pickingWhileDisabled -> pickingWhileDisabled = false
            saved.isNotEmpty() -> viewModel.setBuiltInSubagentModelSelection(namespace, true)
            else -> pickingWhileDisabled = true
        }
    }
    if (!pickerOpen) return

    fun update(next: Set<Pair<String, String>>) {
        if (next.isEmpty()) pickingWhileDisabled = true
        viewModel.setSubagentModels(namespace, next)
    }

    Text(stringResource(R.string.plugins_native_subagent_models), style = DsType.std14Strong)
    val catalog = snapshot.modelCatalog
    val status = when {
        !validSelection -> R.string.plugins_native_builtin_subagent_models_invalid
        snapshot.modelCatalogUnavailable -> R.string.plugins_native_subagent_models_unavailable
        catalog == null -> R.string.plugins_native_loading
        catalog.failures.isNotEmpty() -> R.string.plugins_native_subagent_models_partial
        else -> null
    }
    status?.let { Text(stringResource(it), color = DsTheme.colors.labelSecondary) }
    val advertised = catalog?.groups.orEmpty().flatMap { group ->
        group.models.map { model -> (group.id to model.id) to "${group.name} / ${model.name} (${model.id})" }
    }.toMap()
    val unavailable = saved - advertised.keys
    val candidates = advertised + unavailable.associateWith { (provider, model) -> "$provider / $model" }
    var search by remember(snapshot.target, namespace.ns) { mutableStateOf("") }
    if (candidates.isEmpty()) {
        if (status == null) Text(stringResource(R.string.common_none), color = DsTheme.colors.labelSecondary)
        return
    }
    val unknownModelHint = stringResource(R.string.plugins_native_subagent_unknown_model)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        OutlinedButton(onClick = { update(saved + advertised.keys) }, modifier = Modifier.weight(1f),
            enabled = canEdit && !saved.containsAll(advertised.keys)) {
            Text(stringResource(R.string.host_models_select_all), style = DsType.small13)
        }
        OutlinedButton(onClick = { update(emptySet()) }, modifier = Modifier.weight(1f), enabled = canEdit && saved.isNotEmpty()) {
            Text(stringResource(R.string.host_models_deselect_all), style = DsType.small13)
        }
    }
    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
        label = { Text(stringResource(R.string.common_search)) }, modifier = Modifier.fillMaxWidth())
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        val query = search.trim()
        candidates.forEach { (route, title) ->
            if (!title.contains(query, ignoreCase = true) && !route.first.contains(query, ignoreCase = true)) return@forEach
            val checked = route in saved
            Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = canEdit, role = Role.Checkbox) { select ->
                update(if (select) saved + route else saved - route)
            }.heightIn(min = DsSpacing.touchTarget).padding(horizontal = DsSpacing.tiny), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = canEdit)
                Column(Modifier.padding(start = DsSpacing.small)) {
                    Text(title, style = DsType.small13)
                    if (route in unavailable) Text(unknownModelHint, style = DsType.caption11, color = DsTheme.colors.labelSecondary)
                }
            }
        }
    }
}
