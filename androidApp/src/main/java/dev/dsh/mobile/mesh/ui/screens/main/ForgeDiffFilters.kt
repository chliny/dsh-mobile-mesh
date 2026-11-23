package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffFile
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

internal const val FORGE_LAYER_ALL: String = "all"

/** Files the reader's layer lens and test-file switch leave visible. */
internal fun filterForgeFiles(
    files: List<ForgeDiffFile>,
    lens: String,
    hideTests: Boolean,
): List<ForgeDiffFile> = files.filter { file ->
    (lens == FORGE_LAYER_ALL || file.layer == lens) && !(hideTests && file.layer == "test")
}

internal fun forgeLayerLabelKey(layer: String): Int = when (layer) {
    "interface" -> R.string.forge_layer_interface
    "test" -> R.string.forge_layer_test
    "implementation" -> R.string.forge_layer_implementation
    else -> R.string.forge_layer_all
}

/** Layer lens and test-file visibility, with the counts of the Host-provided layers. */
@Composable
internal fun ForgeLensSheet(
    lens: String,
    hideTests: Boolean,
    files: List<ForgeDiffFile>,
    onSelect: (String) -> Unit,
    onHideTestsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val counts = files.groupingBy { it.layer }.eachCount()
    DsBottomSheet(title = stringResource(R.string.forge_files_filter_title), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (listOf(FORGE_LAYER_ALL) + listOf("interface", "implementation", "test")).forEach { layer ->
                val count = if (layer == FORGE_LAYER_ALL) files.size else counts[layer] ?: 0
                ForgeSheetRow(
                    label = stringResource(forgeLayerLabelKey(layer)),
                    selected = lens == layer && !(hideTests && layer == "test"),
                    trailing = count.toString(),
                    onClick = { onSelect(layer) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.forge_hide_tests),
                    style = DsType.std14,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = hideTests,
                    onCheckedChange = { checked ->
                        onHideTestsChange(checked)
                        if (checked && lens == "test") onSelect(FORGE_LAYER_ALL)
                    },
                )
            }
        }
    }
}

/** One selectable sheet row with an optional trailing count. */
@Composable
internal fun ForgeSheetRow(
    label: String,
    selected: Boolean,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = DsType.std14, color = colors.labelPrimary, modifier = Modifier.weight(1f))
        trailing?.let {
            Text(it, style = DsType.caption11, color = colors.labelTertiary, modifier = Modifier.padding(end = 8.dp))
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        }
    }
}
