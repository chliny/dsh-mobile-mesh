package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact h24 chip: a Material badge at rest, a tappable surface when it takes an [onClick].
 *
 * Material's own chips are 32dp and pad like buttons; these sit inline in rows that already carry a
 * title and a timestamp, so they stay at caption height and take Material's semantic container
 * roles instead: `surfaceVariant` at rest, `primaryContainer` when selected, `tertiaryContainer`
 * for warnings. A clickable chip keeps a hairline so the affordance survives a touch screen, which
 * has no hover to reveal it.
 */
@Composable
fun DsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val background = when {
        warn -> MaterialTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        warn -> MaterialTheme.colorScheme.onTertiaryContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick ?: {},
        modifier = modifier.height(24.dp),
        enabled = onClick != null,
        shape = MaterialTheme.shapes.extraLarge,
        color = background,
        contentColor = contentColor,
        border = if (onClick != null && !warn && !selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}
