package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R

/**
 * A fenced Markdown code block on one Material3 surface.
 *
 * The block used to be an expandable disclosure card wrapping a bordered tool-card surface, which
 * read as two nested frames around the same code. It is now a single tonal container with no
 * border: the language sits quietly in a short header and the copy action is a compact icon that
 * does not compete with the code.
 */
@Composable
internal fun MarkdownCodeBlockCard(
    code: String,
    language: String?,
    onCopy: () -> Unit,
    textMate: TextMateCodeHighlighter?,
    modifier: Modifier = Modifier,
) {
    val normalizedLanguage = language?.takeIf(String::isNotBlank)
    val copyLabel = stringResource(R.string.markdown_copy_code)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (normalizedLanguage != null) {
                    Text(
                        text = normalizedLanguage,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onCopy),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = copyLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (normalizedLanguage.equals("diff", ignoreCase = true) ||
                normalizedLanguage.equals("patch", ignoreCase = true)
            ) {
                val lines = diffTextLines(code)
                val lineKinds = markdownDiffLineKinds(lines)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        lines.forEachIndexed { index, line ->
                            val kind = lineKinds[index]
                            val foreground = when (kind) {
                                '-' -> MaterialTheme.colorScheme.error
                                '+' -> MaterialTheme.colorScheme.primary
                                '@' -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                            val background = when (kind) {
                                '-' -> MaterialTheme.colorScheme.errorContainer
                                '+' -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                            Text(
                                text = line.ifEmpty { "\u00a0" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = foreground,
                                softWrap = false,
                                modifier = Modifier.background(background),
                            )
                        }
                    }
                }
            } else {
                HighlightedCodeLines(
                    code = code,
                    language = normalizedLanguage ?: "text",
                    textMate = textMate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** Classify file headers before hunks while treating `+++`/`---` inside a hunk as source lines. */
internal fun markdownDiffLineKinds(lines: List<String>): List<Char> {
    var inHunk = false
    return lines.map { line ->
        when {
            line.startsWith("diff ") -> {
                inHunk = false
                '@'
            }
            line.startsWith("@@") -> {
                inHunk = true
                '@'
            }
            line.startsWith("index ") -> '@'
            !inHunk && (line.startsWith("+++") || line.startsWith("---")) -> '@'
            line.startsWith('+') -> '+'
            line.startsWith('-') -> '-'
            else -> ' '
        }
    }
}
