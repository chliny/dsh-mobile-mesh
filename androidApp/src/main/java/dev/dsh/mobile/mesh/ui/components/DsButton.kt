package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Button variants mirroring the harness primary/info/ghost/outline/danger palette. */
enum class DsButtonVariant { Primary, Info, Ghost, Outline, Danger }

/** Button sizes: [Normal] is h40, [Small] is h32. */
enum class DsButtonSize { Normal, Small }

/**
 * The app's button, on Material 3: filled for primary/info, outlined for outline, text for ghost,
 * and an error-coloured filled button for destructive actions.
 *
 * The wrapper survives because call sites speak in harness verbs (`Info`, `Ghost`, `Danger`) and
 * because they also pass a test tag; the widget underneath, its paddings and its type are
 * Material's.
 */
@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: DsButtonVariant = DsButtonVariant.Primary,
    size: DsButtonSize = DsButtonSize.Normal,
    icon: ImageVector? = null,
    testTag: String? = null,
) {
    val normal = size == DsButtonSize.Normal
    val tagged = if (testTag == null) modifier else modifier.testTag(testTag)
    val sized = tagged.height(if (normal) 40.dp else 32.dp)
    val padding = PaddingValues(horizontal = if (normal) 20.dp else 12.dp)
    val glyph = if (normal) 18.dp else 16.dp
    val label: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(glyph))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = if (normal) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
    when (variant) {
        DsButtonVariant.Primary, DsButtonVariant.Info -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
        ) { label() }
        DsButtonVariant.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
        ) { label() }
        DsButtonVariant.Ghost -> TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
        ) { label() }
        DsButtonVariant.Danger -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { label() }
    }
}
