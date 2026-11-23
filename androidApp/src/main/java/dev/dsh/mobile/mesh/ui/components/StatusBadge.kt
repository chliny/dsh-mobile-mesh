package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A compact Material state marker: a tinted capsule with a one-line label.
 *
 * Material's chips start at 32dp and pad like buttons, which is too much for a row that already
 * carries a title and a timestamp. This keeps the same semantic colours at caption height.
 */
@Composable
fun StatusBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
) {
    Surface(color = containerColor, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
