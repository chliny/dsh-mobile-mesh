package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.ui.theme.DshTheme

/** One choice in a [DsSegmented] track. */
data class DsSegment(val key: String, val label: String)

/**
 * A segmented control of mutually exclusive choices, on Material's single-choice segmented button.
 *
 * @param role how assistive tech announced the segment in the old custom control. Material's
 *   segmented buttons announce themselves as a radio group; the parameter stays so call sites keep
 *   compiling and can be removed when they are migrated to the Material component directly.
 * @param stretch divide the track equally between the segments instead of letting each hug its
 *   label.
 */
@Composable
fun DsSegmented(
    segments: List<DsSegment>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    stretch: Boolean = false,
) {
    @Suppress("UNUSED_EXPRESSION")
    role
    SingleChoiceSegmentedButtonRow(modifier = if (stretch) modifier.fillMaxWidth() else modifier) {
        segments.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = segment.key == selectedKey,
                onClick = { onSelect(segment.key) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
                modifier = if (stretch) Modifier.weight(1f) else Modifier,
                label = {
                    Text(segment.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsSegmentedPreview() {
    DshTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DsSegmented(
                segments = listOf(
                    DsSegment("off", "Off"),
                    DsSegment("low", "Low"),
                    DsSegment("high", "High"),
                    DsSegment("max", "Max"),
                ),
                selectedKey = "high",
                onSelect = {},
            )
            // The two shapes side by side: inline hugs its labels, stretched divides the track.
            DsSegmented(
                segments = listOf(
                    DsSegment("lan", "Local network"),
                    DsSegment("relay", "Relay"),
                ),
                selectedKey = "relay",
                onSelect = {},
                stretch = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
