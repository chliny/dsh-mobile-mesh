package dev.dsh.mobile.mesh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

/** Shared CommonMark renderer for transcript content and workspace Markdown files. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    imageResolver: (suspend (String) -> String?)? = null,
    onOpenLink: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    CommonMarkMarkdown(
        text = text,
        modifier = modifier,
        imageResolver = imageResolver,
        onOpenLink = onOpenLink ?: uriHandler::openUri,
    )
}
