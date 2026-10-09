package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

/** Shared CommonMark renderer for transcript content and workspace Markdown files. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    imageResolver: (suspend (String) -> String?)? = null,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    val renderAsMarkdown by rememberCommonMarkDecision(text)
    when (renderAsMarkdown) {
        true -> CommonMarkMarkdown(
            text = text,
            modifier = modifier,
            imageResolver = imageResolver,
            onOpenLink = onOpenLink ?: uriHandler::openUri,
        )
        null -> MarkdownLoadingPreview(text, modifier)
        false -> SelectionContainer {
            Text(
                text = text,
                modifier = modifier.fillMaxWidth(),
                style = DsType.mdBody.copy(color = colors.labelPrimary),
            )
        }
    }
}

internal const val MARKDOWN_LOADING_PREVIEW_LIMIT = 8_192

internal fun markdownLoadingPreviewText(text: String): String =
    if (text.length > MARKDOWN_LOADING_PREVIEW_LIMIT) text.take(MARKDOWN_LOADING_PREVIEW_LIMIT) + "…" else text

@Composable
internal fun MarkdownLoadingPreview(text: String, modifier: Modifier = Modifier) {
    val preview = markdownLoadingPreviewText(text)
    SelectionContainer {
        Text(
            text = preview,
            modifier = modifier.fillMaxWidth(),
            style = DsType.mdBody.copy(color = DsTheme.colors.labelPrimary),
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
