package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon button with a Material 3 [IconButton] underneath: 48dp touch target, ripple and disabled
 * state are the framework's; [iconSize] only chooses how big the glyph draws.
 *
 * The glyph is centred in its own [Box] and sized with `requiredSize`, not `size`: Material
 * layouts propagate min constraints, so a plain `size` on the icon is clamped back up to the touch
 * target and every icon renders at 48dp.
 */
@Composable
fun DsIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 20.dp,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize),
        )
    }
}
