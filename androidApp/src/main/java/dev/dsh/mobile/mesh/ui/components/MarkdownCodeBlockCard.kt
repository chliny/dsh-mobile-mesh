package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.ui.theme.DsShapes
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

/** Markdown code rendered inside the same expandable DisclosureRow/tool-card surfaces as Bash/Edit. */
@Composable
internal fun MarkdownCodeBlockCard(
    code: String,
    language: String?,
    copyLabel: String,
    onCopy: () -> Unit,
    textMate: TextMateCodeHighlighter?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var expanded by remember(code) { mutableStateOf(true) }
    val normalizedLanguage = language?.takeIf(String::isNotBlank)
    val title = normalizedLanguage ?: "code"
    val lineCount = code.lineSequence().count().coerceAtLeast(1)
    val summary = "$lineCount ${if (lineCount == 1) "line" else "lines"}"
    val icon = if (normalizedLanguage in setOf("bash", "sh", "shell", "zsh", "fish")) {
        FeatherIcons.Terminal
    } else {
        FeatherIcons.Code
    }

    DisclosureRow(
        title = title,
        summary = summary,
        icon = icon,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        trailingContent = {
            Text(
                copyLabel,
                style = DsType.caption11Strong,
                color = colors.accent,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onCopy)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        },
    ) {
        ToolCardSurface(contentPadding = 6.dp, spacing = 4.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DsShapes.block)
                    .background(colors.bgModulePlatform)
                    .padding(8.dp),
            ) {
                HighlightedCodeLines(
                    code = code,
                    language = normalizedLanguage ?: "text",
                    textMate = textMate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The shared outer surface used by expanded tool cards and Markdown code cards. */
@Composable
internal fun ToolCardSurface(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.codeBlockBg)
            .border(1.dp, colors.borderL1, DsShapes.block)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}
