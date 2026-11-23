package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.LlmDiscoveredModel
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        IconButton(onClick = viewModel::refresh, enabled = enabled) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.plugins_native_refresh),
            )
        }
    }) {
        if (!enabled) Text(stringResource(R.string.common_loading))
        state.failure?.let { Text(it, color = DsTheme.colors.error) }
        if (state.settings?.writable == false) Text(stringResource(R.string.plugins_native_read_only))
        if (state.settings != null && available.isEmpty()) Text(stringResource(R.string.common_none))
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            if (catalog.isNotEmpty()) OutlinedButton(onClick = { catalogOpen = true }, enabled = writable,
                modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.host_models_add_provider))
            }
            if (custom != null && custom.providerPathIsAddressable(listOf("providers")) &&
                ModelSettingsSchema(custom.schema).protocols(listOf("providers", "probe")).isNotEmpty()
            ) {
                OutlinedButton(onClick = { viewModel.clearDiscovery(); creatingAt = custom }, enabled = writable,
                    modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.host_models_custom_provider))
                }
            }
        }
        available.forEach { row ->
            SettingsBlock {
                Text(row.entry.displayName, style = DsType.std14Strong)
                Text(row.entry.provider, style = DsType.caption11, color = DsTheme.colors.labelSecondary)
                row.entry.error?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.error) }
                row.credential?.let {
                    Text(stringResource(R.string.plugins_native_web_key_status) + ": " + stringResource(
                        if (it.configured) R.string.plugins_native_web_key_configured else R.string.plugins_native_web_key_missing,
                    ), style = DsType.caption11, color = DsTheme.colors.labelSecondary)
                }
                if (row.editable || row.removable || row.clearableCredential) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
                        if (row.editable) ModelProviderAction(R.string.common_edit, writable, Modifier.weight(1f)) {
                            viewModel.clearDiscovery(); editing = row
                        }
                        if (row.removable) ModelProviderAction(R.string.common_delete, writable, Modifier.weight(1f)) {
                            deleting = row
                        }
                        if (row.clearableCredential) ModelProviderAction(R.string.host_models_clear_key, writable, Modifier.weight(1.5f)) {
                            clearing = row
                        }
                    }
                }
            }
        }
    }
    if (catalogOpen) AlertDialog(onDismissRequest = { catalogOpen = false },
        title = { Text(stringResource(R.string.host_models_add_provider)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
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
    var editingName by remember(namespace, row) { mutableStateOf(false) }
    var editingEndpoint by remember(namespace, row) { mutableStateOf(false) }
    var editingKey by remember(namespace, row) { mutableStateOf(false) }
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
    var search by remember(discovered) { mutableStateOf("") }
    var showDiscovery by remember(namespace, row) { mutableStateOf(false) }
    var editIndex by remember(namespace, row) { mutableStateOf<Int?>(null) }
    val canEditName = custom || schema.field(path + "displayName") != null
    val savedName = row?.profile.modelString("displayName")?.takeIf { it.isNotBlank() } ?: row?.entry?.displayName
    val fallbackEndpoint = inherited.modelString("baseURL")?.takeIf { it.isNotBlank() }
        ?: row?.entry?.defaultBaseURL?.takeIf { it.isNotBlank() }
    val savedEndpoint = row?.profile.modelString("baseURL")?.takeIf { it.isNotBlank() } ?: fallbackEndpoint
    val effectiveEndpoint = if (editingEndpoint) endpoint.trim().ifEmpty { fallbackEndpoint.orEmpty() } else savedEndpoint.orEmpty()
    val validEndpoint = (!editingEndpoint && row != null) || (!custom && endpoint.isBlank()) || modelEndpointValid(effectiveEndpoint)
    val valid = (row != null || providerRoutePattern.matches(route)) && validEndpoint &&
        (!custom || (protocol in protocols && models.isNotEmpty())) &&
        models.mapNotNull { it.modelString("id") }.distinct().size == models.size
    val editorScroll = rememberScrollState()
    var editorViewport by remember { mutableStateOf(Rect.Zero) }
    AlertDialog(onDismissRequest = { if (enabled) { key = ""; onClose() } },
        title = { Text(row?.entry?.displayName ?: stringResource(R.string.host_models_custom_provider)) },
        text = { Box(Modifier.onGloballyPositioned { editorViewport = it.boundsInRoot() }) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(editorScroll),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                if (row == null) ModelTextField(R.string.host_models_provider_id, route, enabled) { route = it }
                ModelSettingRow(R.string.host_models_display_name, savedName ?: stringResource(R.string.common_none),
                    writable && canEditName && !editingName, onEdit = if (canEditName) ({ editingName = true }) else null)
                if (editingName) {
                    ModelTextField(R.string.host_models_display_name, name, writable, placeholder = savedName) { name = it }
                }
                ModelSettingRow(R.string.host_models_endpoint, savedEndpoint.orEmpty(), writable && !editingEndpoint,
                    onEdit = { editingEndpoint = true })
                if (editingEndpoint) {
                    ModelTextField(R.string.host_models_endpoint, endpoint, writable, placeholder = fallbackEndpoint) { endpoint = it }
                }
                if (custom && protocols.isNotEmpty()) ModelChoice(R.string.host_models_protocol, protocol, protocols, writable) { protocol = it }
                val canEditKey = row == null || row.credential?.writable == true
                if (row == null || row.credential != null) {
                    ModelSettingRow(R.string.plugins_native_web_key_status, "***", writable && canEditKey && !editingKey,
                        onEdit = if (canEditKey) ({ editingKey = true }) else null)
                }
                if (editingKey) {
                    ModelTextField(R.string.plugins_native_web_key_input, key, writable, password = true) { key = it }
                }
                OutlinedButton(onClick = {
                    showDiscovery = true
                    viewModel.clearDiscovery()
                    viewModel.discover(namespace, row, effectiveEndpoint.takeIf { it.isNotBlank() },
                        protocol.takeIf { it.isNotBlank() }, key.trim().takeIf { it.isNotEmpty() })
                }, enabled = enabled && (row != null || modelEndpointValid(effectiveEndpoint))) {
                    Text(stringResource(R.string.host_models_discover))
                }
                if (showDiscovery && discovered != null) {
                    if (discovered.isEmpty()) Text(stringResource(R.string.common_none))
                    else {
                        // Selecting a discovered model edits the provider catalog directly: a check
                        // adds it and an uncheck removes it, so no separate apply step is needed.
                        val catalogIds = models.mapNotNull { it.modelString("id") }.toSet()
                        val discoveredIds = discovered.mapTo(mutableSetOf()) { it.id }
                        val allSelected = discovered.all { it.id in catalogIds }
                        val checkState = when {
                            allSelected -> ToggleableState.On
                            discovered.none { it.id in catalogIds } -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        }
                        fun setDiscoveredEnabled(value: Boolean) {
                            models = if (value) {
                                models + discovered.filter { it.id !in catalogIds }.map { it.toSettings(modalityField) }
                            } else {
                                models.filterNot { it.modelString("id") in discoveredIds }
                            }
                            modelsOverride = true
                        }
                        Row(Modifier.fillMaxWidth().triStateToggleable(
                            state = checkState, enabled = enabled, role = Role.Checkbox,
                            onClick = { setDiscoveredEnabled(!allSelected) },
                        ).heightIn(min = DsSpacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state = checkState, onClick = null, enabled = enabled)
                            Text(stringResource(if (allSelected) R.string.host_models_deselect_all else R.string.host_models_select_all),
                                Modifier.padding(start = DsSpacing.tiny), style = DsType.small13)
                        }
                        ModelTextField(R.string.common_search, search, enabled) { search = it }
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            discovered.filter { it.id.contains(search, ignoreCase = true) || it.name?.contains(search, ignoreCase = true) == true }
                                .forEach { model ->
                                    val selected = model.id in catalogIds
                                    Row(Modifier.fillMaxWidth()
                                        .toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { checked ->
                                            models = when {
                                                checked && !selected -> models + model.toSettings(modalityField)
                                                !checked -> models.filterNot { it.modelString("id") == model.id }
                                                else -> models
                                            }
                                            modelsOverride = true
                                        }
                                        .heightIn(min = DsSpacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                                        Text(model.name ?: model.id, style = DsType.std14)
                                    }
                                }
                        }
                    }
                }
                Text(stringResource(R.string.host_models_catalog))
                ReorderableModelCatalog(models, writable, editorScroll, editorViewport,
                    onEdit = { editIndex = it },
                    onRemove = { index ->
                        models = models.filterIndexed { i, _ -> i != index }
                        modelsOverride = true
                    },
                    onMove = { from, to ->
                        models = models.toMutableList().apply { add(to, removeAt(from)) }
                        modelsOverride = true
                    },
                )
                OutlinedButton(onClick = { editIndex = models.size }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), contentPadding = ModelActionPadding) {
                    Text(stringResource(R.string.host_models_add_model))
                }
                if (row != null && modelsOverride && (!custom || !defaultModels.isNullOrEmpty())) OutlinedButton(onClick = {
                    models = defaultModels.orEmpty().filterIsInstance<JsonObject>()
                    modelsOverride = false
                }, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = ModelActionPadding) {
                    Text(stringResource(R.string.plugins_native_reset))
                }
            }
        } }, confirmButton = {
            TextButton(enabled = writable && valid, onClick = {
                val value = key.trim().takeIf { it.isNotEmpty() }
                key = ""
                val updates = buildMap<String, JsonElement?> {
                    if (editingEndpoint) put("baseURL", endpoint.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
                    if (editingName) put("displayName", name.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))
                    if (custom) put("api", JsonPrimitive(protocol))
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

private data class CatalogDrag(val index: Int, val scrollStart: Int, val pointerStartY: Float)

@Composable
private fun ReorderableModelCatalog(
    models: List<JsonObject>, writable: Boolean, scroll: ScrollState, viewport: Rect,
    onEdit: (Int) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit,
) {
    val canReorder = writable && models.size > 1
    val rowPositions = remember { mutableMapOf<Int, LayoutCoordinates>() }
    val move by rememberUpdatedState(onMove)
    var drag by remember(models, canReorder) { mutableStateOf<CatalogDrag?>(null) }
    var deltaY by remember { mutableStateOf(0f) }
    val edge = with(LocalDensity.current) { DsSpacing.touchTarget.toPx() }
    val step = with(LocalDensity.current) { DsSpacing.small.toPx() }
    val scrollDirection = drag?.let {
        val pointerY = it.pointerStartY + deltaY
        when {
            viewport.height <= 0f -> 0
            pointerY < viewport.top + edge && deltaY < 0f -> -1
            pointerY > viewport.bottom - edge && deltaY > 0f -> 1
            else -> 0
        }
    } ?: 0
    LaunchedEffect(drag?.index, scrollDirection) {
        if (scrollDirection != 0) {
            while (true) {
                withFrameNanos { }
                if (scroll.scrollBy(scrollDirection * step) == 0f) break
            }
        }
    }

    val moveUp = stringResource(R.string.host_models_move_up)
    val moveDown = stringResource(R.string.host_models_move_down)
    Column(Modifier.fillMaxWidth()) {
        models.forEachIndexed { index, model ->
            val modifier = if (!canReorder) Modifier else Modifier
                .onGloballyPositioned { rowPositions[index] = it }
                .pointerInput(models, index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val coordinates = rowPositions[index]?.takeIf { it.isAttached }
                            if (coordinates != null) {
                                deltaY = 0f
                                drag = CatalogDrag(index, scroll.value, coordinates.localToRoot(start).y)
                            }
                        },
                        onDrag = { change, amount ->
                            if (drag?.index == index) {
                                change.consume()
                                deltaY += amount.y
                            }
                        },
                        onDragCancel = { drag = null },
                        onDragEnd = {
                            val active = drag
                            drag = null
                            val source = rowPositions[index]?.takeIf { it.isAttached }
                            if (active?.index == index && source != null) {
                                val center = source.positionInParent().y + source.size.height / 2f +
                                    deltaY + scroll.value - active.scrollStart
                                val target = models.indices.count { other ->
                                    val coordinates = rowPositions[other]
                                    other != index && coordinates != null && coordinates.isAttached &&
                                        coordinates.positionInParent().y + coordinates.size.height / 2f < center
                                }
                                if (target != index) move(index, target)
                            }
                        },
                    )
                }
                .semantics {
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUp) { move(index, index - 1); true })
                        if (index < models.lastIndex) add(CustomAccessibilityAction(moveDown) { move(index, index + 1); true })
                    }
                }
            val active = drag?.takeIf { it.index == index }
            Box(Modifier.fillMaxWidth().zIndex(if (active != null) 1f else 0f).then(modifier)) {
                Column(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = if (active != null) deltaY + scroll.value - active.scrollStart else 0f
                }.then(if (active != null) Modifier.background(DsTheme.colors.bgLayer1) else Modifier)
                    .padding(vertical = DsSpacing.tiny)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.modelString("name") ?: model.modelString("id").orEmpty(), style = DsType.std14Strong)
                            Text(model.modelString("id").orEmpty(), style = DsType.caption11, color = DsTheme.colors.labelSecondary)
                        }
                        if (canReorder) Icon(Icons.Default.DragHandle, contentDescription = null,
                            modifier = Modifier.size(DsSpacing.large), tint = DsTheme.colors.labelSecondary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        ModelProviderAction(R.string.common_edit, writable, Modifier.weight(1f)) { onEdit(index) }
                        ModelProviderAction(R.string.common_remove, writable, Modifier.weight(1f)) { onRemove(index) }
                    }
                }
            }
        }
    }
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
    OutlinedTextField(value, onValueChange = edit,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(it) } }, enabled = enabled, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
}

private val ModelActionPadding = PaddingValues(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)

@Composable
private fun ModelProviderAction(label: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled,
        contentPadding = PaddingValues(horizontal = DsSpacing.tiny, vertical = DsSpacing.tiny)) {
        Text(stringResource(label), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ModelSettingRow(label: Int, value: String, enabled: Boolean, onEdit: (() -> Unit)?) {
    val fieldLabel = stringResource(label)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$fieldLabel:", Modifier.weight(0.8f), style = DsType.small13)
        Text(value, Modifier.weight(1.2f), style = DsType.small13, color = DsTheme.colors.labelSecondary)
        if (onEdit != null) {
            val editLabel = stringResource(R.string.common_edit)
            OutlinedButton(onClick = onEdit, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$editLabel: $fieldLabel" },
                contentPadding = PaddingValues(horizontal = DsSpacing.small, vertical = DsSpacing.tiny)) {
                Text(editLabel)
            }
        }
    }
}

@Composable
private fun ModelChoice(label: Int, current: String, options: List<String>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label) + ":", Modifier.weight(1f), style = DsType.small13)
        androidx.compose.foundation.layout.Box(Modifier.weight(1.5f, fill = false)) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(current) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; select(option) }) }
            }
        }
    }
}
