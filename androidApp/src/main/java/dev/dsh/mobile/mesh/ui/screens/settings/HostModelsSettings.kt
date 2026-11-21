package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.LlmDiscoveredModel
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

@Composable
internal fun HostModelsSettings(onClose: () -> Unit, viewModel: HostModelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled = !state.loading && !state.busy
    val writable = state.settings?.writable == true && enabled
    var editing by remember { mutableStateOf<HostModelProvider?>(null) }
    var creatingAt by remember { mutableStateOf<SettingsNamespaceView?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HostModelProvider?>(null) }
    var clearing by remember { mutableStateOf<HostModelProvider?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(state.settings) {
        if (editing != null && state.providers.none { it === editing }) editing = null
        if (state.customNamespace !== creatingAt) creatingAt = null
    }
    val custom = state.customNamespace
    val available = state.providers.filter { it.profile != null || it.live || it.entry.settingsPath.isEmpty() }
    val catalog = state.providers.filter { it.profile == null && !it.live && it.editable && !it.entry.settingsPath.isEmpty() }
    HostSettingsPage(stringResource(R.string.models_title), onClose, action = {
        TextButton(onClick = viewModel::refresh, enabled = enabled) { Text(stringResource(R.string.plugins_native_refresh)) }
    }) {
        if (!enabled) Text(stringResource(R.string.common_loading))
        state.failure?.let { Text(it, color = DsTheme.colors.error) }
        if (state.settings?.writable == false) Text(stringResource(R.string.plugins_native_read_only))
        if (state.settings != null && available.isEmpty()) Text(stringResource(R.string.common_none))
        if (catalog.isNotEmpty()) OutlinedButton(onClick = { catalogOpen = true }, enabled = writable) {
            Text(stringResource(R.string.host_models_add_provider))
        }
        if (custom != null && custom.providerPathIsAddressable(listOf("providers")) &&
            ModelSettingsSchema(custom.schema).protocols(listOf("providers", "probe")).isNotEmpty()
        ) {
            OutlinedButton(onClick = { viewModel.clearDiscovery(); creatingAt = custom }, enabled = writable) {
                Text(stringResource(R.string.host_models_custom_provider))
            }
        }
        available.forEach { row ->
            HorizontalDivider()
            Text(row.entry.displayName)
            Text(row.entry.provider, color = DsTheme.colors.labelSecondary)
            row.entry.error?.let { Text(it, color = DsTheme.colors.error) }
            row.credential?.let {
                Text(stringResource(R.string.plugins_native_web_key_status) + ": " + stringResource(
                    if (it.configured) R.string.plugins_native_web_key_configured else R.string.plugins_native_web_key_missing,
                ))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.editable) TextButton(onClick = { viewModel.clearDiscovery(); editing = row }, enabled = writable) {
                    Text(stringResource(R.string.common_edit))
                }
                if (row.removable) TextButton(onClick = { deleting = row }, enabled = writable) {
                    Text(stringResource(R.string.common_delete))
                }
            }
            if (row.clearableCredential) {
                TextButton(onClick = { clearing = row }, enabled = writable) { Text(stringResource(R.string.host_models_clear_key)) }
            }
        }
    }
    if (catalogOpen) AlertDialog(onDismissRequest = { catalogOpen = false },
        title = { Text(stringResource(R.string.host_models_add_provider)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            catalog.forEach { row ->
                TextButton(onClick = { catalogOpen = false; viewModel.clearDiscovery(); editing = row }, enabled = writable) {
                    Text(row.entry.displayName)
                }
            }
        } }, confirmButton = { TextButton(onClick = { catalogOpen = false }) { Text(stringResource(R.string.common_close)) } })
    deleting?.let { row -> ModelActionConfirmation(R.string.common_delete, row.entry.displayName, writable,
        onDismiss = { deleting = null }, onConfirm = { deleting = null; viewModel.removeProvider(row) }) }
    clearing?.let { row -> ModelActionConfirmation(R.string.host_models_clear_key, row.keyRef, writable,
        onDismiss = { clearing = null }, onConfirm = { clearing = null; viewModel.clearCredential(row) }) }
    val current = editing?.takeIf { row -> state.providers.any { it === row } }
    val namespace = current?.namespace ?: custom?.takeIf { it === creatingAt }
    if (namespace != null) {
        ProviderSettingsEditor(namespace, current, writable, enabled, state.discovered, viewModel,
            onClose = { creatingAt = null; editing = null; viewModel.clearDiscovery() })
    }
}

@Composable
private fun ModelActionConfirmation(label: Int, name: String, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(label)) }, text = { Text(name) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(label)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun ProviderSettingsEditor(namespace: SettingsNamespaceView, row: HostModelProvider?, writable: Boolean,
    enabled: Boolean, discovered: List<LlmDiscoveredModel>?, viewModel: HostModelsViewModel, onClose: () -> Unit,
) {
    val path = row?.entry?.settingsPath ?: listOf("providers", "probe")
    val schema = remember(namespace.schema) { ModelSettingsSchema(namespace.schema) }
    val protocols = remember(schema, path) { schema.protocols(path) }
    val reasoning = remember(schema, path) { schema.reasoning(path) }
    val modalityField = if (namespace.ns == PI_AI) "input" else "inputModalities"
    val custom = row == null || row.entry.declared == true
    val inherited = row?.inherited
    var route by remember(namespace, row) { mutableStateOf(row?.entry?.provider.orEmpty()) }
    var name by remember(namespace, row) { mutableStateOf(row?.raw.modelString("displayName").orEmpty()) }
    var endpoint by remember(namespace, row) { mutableStateOf(row?.raw.modelString("baseURL").orEmpty()) }
    var protocol by remember(namespace, row) {
        mutableStateOf(row?.raw.modelString("api") ?: row?.profile.modelString("api") ?: protocols.firstOrNull().orEmpty())
    }
    var key by remember(namespace, row) { mutableStateOf("") }
    val defaultModels = (inherited?.get("models") as? JsonArray)
        ?: (((schema.field(path + "models")?.get("meta") as? JsonObject)?.get("default")) as? JsonArray)
    var modelsOverride by remember(namespace, row) { mutableStateOf(row == null || row.raw?.get("models") is JsonArray) }
    var models by remember(namespace, row) {
        mutableStateOf(((row?.profile?.get("models") as? JsonArray) ?: defaultModels).orEmpty().filterIsInstance<JsonObject>())
    }
    var selection by remember(discovered) { mutableStateOf(discovered.orEmpty().mapTo(mutableSetOf()) { it.id }.toSet()) }
    var search by remember(discovered) { mutableStateOf("") }
    var showDiscovery by remember(namespace, row) { mutableStateOf(false) }
    var editIndex by remember(namespace, row) { mutableStateOf<Int?>(null) }
    val effectiveEndpoint = endpoint.trim().ifEmpty { inherited.modelString("baseURL") ?: row?.entry?.defaultBaseURL.orEmpty() }
    val valid = (row != null || providerRoutePattern.matches(route)) &&
        (endpoint.isBlank() && !custom || modelEndpointValid(effectiveEndpoint)) &&
        (!custom || (protocol in protocols && models.isNotEmpty())) &&
        models.mapNotNull { it.modelString("id") }.distinct().size == models.size
    AlertDialog(onDismissRequest = { if (enabled) { key = ""; onClose() } },
        title = { Text(row?.entry?.displayName ?: stringResource(R.string.host_models_custom_provider)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row == null) ModelTextField(R.string.host_models_provider_id, route, enabled) { route = it }
                if (custom) ModelTextField(R.string.host_models_display_name, name, enabled) { name = it }
                ModelTextField(R.string.host_models_endpoint, endpoint, enabled, placeholder = inherited.modelString("baseURL") ?: row?.entry?.defaultBaseURL) { endpoint = it }
                if (custom && protocols.isNotEmpty()) ModelChoice(R.string.host_models_protocol, protocol, protocols, enabled) { protocol = it }
                if (row == null || row.credential?.writable == true) {
                    ModelTextField(R.string.plugins_native_web_key_input, key, enabled, password = true) { key = it }
                }
                OutlinedButton(onClick = {
                    showDiscovery = true
                    viewModel.discover(namespace, row, effectiveEndpoint.takeIf { it.isNotBlank() },
                        protocol.takeIf { it.isNotBlank() }, key.trim().takeIf { it.isNotEmpty() })
                }, enabled = enabled && (row != null || modelEndpointValid(effectiveEndpoint))) {
                    Text(stringResource(R.string.host_models_discover))
                }
                if (showDiscovery && discovered != null) {
                    if (discovered.isEmpty()) Text(stringResource(R.string.common_none))
                    else {
                        ModelTextField(R.string.common_search, search, enabled) { search = it }
                        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            discovered.filter { it.id.contains(search, ignoreCase = true) || it.name?.contains(search, ignoreCase = true) == true }
                                .forEach { model -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = model.id in selection, onCheckedChange = {
                                        selection = if (it) selection + model.id else selection - model.id
                                    }, enabled = enabled)
                                    Text(model.name ?: model.id)
                                } }
                        }
                        TextButton(onClick = {
                            val prior = models.associateBy { it.modelString("id") }
                            models = discovered.filter { it.id in selection }.map { prior[it.id] ?: it.toSettings(modalityField) }
                            modelsOverride = true
                            showDiscovery = false
                        }, enabled = enabled && selection.isNotEmpty()) { Text(stringResource(R.string.host_models_use_selected)) }
                    }
                }
                Text(stringResource(R.string.host_models_catalog))
                models.forEachIndexed { index, model ->
                    Text(model.modelString("name") ?: model.modelString("id").orEmpty())
                    Row {
                        TextButton(onClick = { editIndex = index }, enabled = enabled) { Text(stringResource(R.string.common_edit)) }
                        TextButton(onClick = { models = models.filterIndexed { i, _ -> i != index }; modelsOverride = true }, enabled = enabled) {
                            Text(stringResource(R.string.common_remove))
                        }
                        TextButton(onClick = {
                            models = models.toMutableList().apply { add(index - 1, removeAt(index)) }
                            modelsOverride = true
                        }, enabled = enabled && index > 0) { Text(stringResource(R.string.host_models_move_up)) }
                    }
                }
                TextButton(onClick = { editIndex = models.size }, enabled = enabled) { Text(stringResource(R.string.host_models_add_model)) }
                if (row != null && modelsOverride && (!custom || !defaultModels.isNullOrEmpty())) TextButton(onClick = {
                    models = defaultModels.orEmpty().filterIsInstance<JsonObject>()
                    modelsOverride = false
                }, enabled = enabled) { Text(stringResource(R.string.plugins_native_reset)) }
            }
        }, confirmButton = {
            TextButton(enabled = writable && valid, onClick = {
                val value = key.trim().takeIf { it.isNotEmpty() }
                key = ""
                val updates = buildMap<String, JsonElement?> {
                    put("baseURL", endpoint.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
                    if (custom) {
                        put("displayName", name.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
                        put("api", JsonPrimitive(protocol))
                    }
                    put("models", JsonArray(models).takeIf { modelsOverride })
                }
                viewModel.saveProvider(namespace, row, route, updates, value)
            }) { Text(stringResource(R.string.common_save)) }
        }, dismissButton = { TextButton(onClick = { key = ""; onClose() }, enabled = enabled) { Text(stringResource(R.string.common_close)) } })
    editIndex?.let { index -> ModelDirectoryEditor(models.getOrNull(index), modalityField, reasoning,
        existingIds = models.filterIndexed { i, _ -> i != index }.mapNotNull { it.modelString("id") }.toSet(),
        onDismiss = { editIndex = null }, onSave = { updated ->
            models = models.toMutableList().apply { if (index < size) set(index, updated) else add(updated) }
            modelsOverride = true
            editIndex = null
        }) }
}

@Composable
private fun ModelDirectoryEditor(original: JsonObject?, modalityField: String, reasoning: List<String>, existingIds: Set<String>, onDismiss: () -> Unit,
    onSave: (JsonObject) -> Unit,
) {
    var id by remember { mutableStateOf(original.modelString("id").orEmpty()) }
    var name by remember { mutableStateOf(original.modelString("name").orEmpty()) }
    var context by remember { mutableStateOf((original?.get("contextWindow") as? JsonPrimitive)?.contentOrNull.orEmpty()) }
    var maximum by remember { mutableStateOf((original?.get("maxTokens") as? JsonPrimitive)?.contentOrNull.orEmpty()) }
    val modalities = (original?.get(modalityField) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    var text by remember { mutableStateOf(modalities == null || "text" in modalities) }
    var image by remember { mutableStateOf("image" in modalities.orEmpty()) }
    var inputChanged by remember { mutableStateOf(false) }
    var efforts by remember { mutableStateOf((original?.get("reasoningEfforts") as? JsonObject).orEmpty().mapValues { (_, value) ->
        (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    }) }
    var effortDisabled by remember { mutableStateOf(original?.get("reasoningEfforts") == JsonPrimitive(false)) }
    var effortsChanged by remember { mutableStateOf(false) }
    val valid = id.isNotBlank() && id.trim() !in existingIds &&
        (context.isBlank() || parseModelCapacity(context) != null) && (maximum.isBlank() || parseModelCapacity(maximum) != null) &&
        (!inputChanged || text || image) &&
        (!effortsChanged || effortDisabled || efforts.values.all { it.isBlank() } ||
            efforts.any { (level, wire) -> level != "off" && wire.isNotBlank() })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.host_models_model_id)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelTextField(R.string.host_models_model_id, id, true) { id = it }
            ModelTextField(R.string.host_models_display_name, name, true) { name = it }
            ModelTextField(R.string.host_models_context, context, true) { context = it }
            ModelTextField(R.string.host_models_max_output, maximum, true) { maximum = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(text, onCheckedChange = { text = it; inputChanged = true }); Text(stringResource(R.string.host_models_text))
                Checkbox(image, onCheckedChange = { image = it; inputChanged = true }); Text(stringResource(R.string.host_models_image))
            }
            if (reasoning.isNotEmpty()) {
                Text(stringResource(R.string.models_reasoning_effort))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(effortDisabled, onCheckedChange = { effortDisabled = it; effortsChanged = true })
                    Text(stringResource(R.string.host_models_reasoning_disabled))
                }
                if (!effortDisabled) reasoning.forEach { level ->
                    OutlinedTextField(efforts[level].orEmpty(), onValueChange = {
                        efforts = efforts + (level to it); effortsChanged = true
                    }, label = { Text(level) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        } }, confirmButton = { TextButton(enabled = valid, onClick = {
            val draft = original.orEmpty().toMutableMap()
            draft["id"] = JsonPrimitive(id.trim())
            fun set(field: String, value: JsonElement?) { if (value == null) draft.remove(field) else draft[field] = value }
            set("name", name.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
            set("contextWindow", parseModelCapacity(context)?.let(::JsonPrimitive))
            set("maxTokens", parseModelCapacity(maximum)?.let(::JsonPrimitive))
            if (inputChanged) set(modalityField, JsonArray(buildList {
                if (text) add(JsonPrimitive("text")); if (image) add(JsonPrimitive("image"))
            }))
            if (effortsChanged) set("reasoningEfforts", if (effortDisabled) JsonPrimitive(false)
                else efforts.filterValues { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                    ?.let { JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value.trim()) }) })
            onSave(JsonObject(draft))
        }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

private val modelCapacityPattern = Regex("([0-9]+)\\s*([kKmM]?)")
private fun parseModelCapacity(input: String): Long? {
    val match = modelCapacityPattern.matchEntire(input.trim()) ?: return null
    val value = match.groupValues[1].toLongOrNull() ?: return null
    val factor = when (match.groupValues[2].lowercase(Locale.ROOT)) { "k" -> 1_024L; "m" -> 1_048_576L; else -> 1L }
    return value.takeIf { it in 1..(9_007_199_254_740_991L / factor) }?.times(factor)
}

private fun LlmDiscoveredModel.toSettings(modalityField: String): JsonObject = JsonObject(buildMap {
    put("id", JsonPrimitive(id))
    name?.let { put("name", JsonPrimitive(it)) }
    contextWindow?.let { put("contextWindow", JsonPrimitive(it)) }
    maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
    inputModalities?.let { put(modalityField, JsonArray(it.map(::JsonPrimitive))) }
})

@Composable
private fun ModelTextField(label: Int, value: String, enabled: Boolean, placeholder: String? = null,
    password: Boolean = false, edit: (String) -> Unit,
) {
    OutlinedTextField(value, onValueChange = edit, label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(it) } }, enabled = enabled, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ModelChoice(label: Int, current: String, options: List<String>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(stringResource(label) + ": " + current) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; select(option) }) }
        }
    }
}
