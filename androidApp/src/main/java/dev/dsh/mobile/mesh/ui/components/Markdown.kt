package dev.dsh.mobile.mesh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
    if (renderAsMarkdown) {
        CommonMarkMarkdown(
            text = text,
            modifier = modifier,
            imageResolver = imageResolver,
            onOpenLink = onOpenLink ?: uriHandler::openUri,
        )
    } else {
        SelectionContainer {
            Text(
                text = text,
                modifier = modifier.fillMaxWidth(),
                style = DsType.mdBody.copy(color = colors.labelPrimary),
            )
        }
    }
}
