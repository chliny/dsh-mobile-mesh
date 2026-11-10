package dev.dsh.mobile.mesh.ui.components

import dev.dsh.mobile.mesh.core.wire.dto.ImageAttachmentRef

/**
 * View models for the harness tool cards. Pure data — rendering lives in
 * [ToolCards.kt].
 */

/** A rendered content block produced by a tool call. */
sealed interface ContentBlockView {
    /** Plain text output, rendered in mono. */
    data class TextBlock(val text: String) : ContentBlockView

    /** Reasoning trace, rendered muted. */
    data class ReasoningBlock(val text: String) : ContentBlockView

    /**
     * A raster the tool produced — a screenshot, a rendered chart. The complete wire reference
     * travels with the block: its intrinsic size drives downsampling, and the 0.2.2 read route
     * requires the whole reference rather than a bare id.
     */
    data class ImageBlock(val attachment: ImageAttachmentRef) : ContentBlockView
}

/** One diff hunk: [path] plus the removed [oldText] and/or added [newText] lines. */
data class DiffHunk(
    val path: String,
    val oldText: String? = null,
    val newText: String? = null,
)

/** One file matched by a search, with its matching lines. */
data class SearchFile(
    val path: String,
    val matches: List<SearchMatch>,
)

/** One matching line inside a searched file. */
data class SearchMatch(
    val lineNumber: Int,
    val line: String,
)

/** One numbered line of a file read. */
data class ReadLine(
    val number: Int,
    val text: String,
)

/** A web source citation. */
data class WebSource(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
)

/** Shape of a search result: grouped file matches or a flat list of paths. */
sealed interface SearchMatches {
    data class FileMatches(val files: List<SearchFile>) : SearchMatches
    data class PathList(val paths: List<String>) : SearchMatches
}

/** Shape of a web tool result: an answer with sources, or a fetched URL. */
sealed interface WebCardKind {
    data class Search(val answer: String? = null, val sources: List<WebSource> = emptyList()) : WebCardKind
    data class Fetch(val url: String, val statusCode: Int? = null) : WebCardKind
}

/** A harness tool card in display form. */
sealed interface ToolCardView {
    /** Generic tool: pretty-printed input JSON, locations, and content blocks. */
    data class GenericCard(
        val title: String? = null,
        val kind: String? = null,
        val rawInput: String? = null,
        val locations: List<String>? = null,
        val content: List<ContentBlockView>? = null,
        /**
         * Grammar hint for [content] text. A shell tool that fell back to the generic card — a
         * persistent shell, an error, or a multi-block result — still shows shell output, so the
         * language travels with the card instead of being re-derived while rendering.
         */
        val language: String? = null,
    ) : ToolCardView

    /** Terminal run: output stream plus exit status. */
    data class TerminalCard(
        val title: String? = null,
        val description: String? = null,
        val command: String? = null,
        val cwd: String? = null,
        val output: String? = null,
        val outputBlocks: List<String> = emptyList(),
        val exitCode: Int? = null,
        val signal: String? = null,
        val running: Boolean? = null,
    ) : ToolCardView

    /**
     * Diff: per-path hunks with added/removed line counts.
     *
     * [partial] marks a multi-file patch whose Host result metadata reported that applying stopped
     * before every operation finished. The hunks are still exactly the operations that were
     * applied, but the card must not read as a complete success.
     *
     * [detail] carries the Host's own result text for that stopped outcome — which operations
     * failed or were not executed — because the hunks alone cannot say why applying stopped. It
     * is verbatim Host output, never a client guess, and absent for a complete patch.
     */
    data class DiffCard(
        val title: String? = null,
        val diffs: List<DiffHunk>,
        val partial: Boolean = false,
        val detail: String? = null,
    ) : ToolCardView

    /** Search: file matches or path list, with truncation info. */
    data class SearchCard(
        val title: String? = null,
        val matches: SearchMatches,
        val truncated: Boolean = false,
        val total: Int? = null,
    ) : ToolCardView

    /** File read: numbered lines with a line-number gutter. */
    data class ReadCard(
        val label: String,
        val path: String? = null,
        val lines: List<ReadLine> = emptyList(),
        val totalLines: Int = lines.size,
        val lang: String? = null,
    ) : ToolCardView

    /** Web: search answer with sources, or a fetched URL with status. */
    data class WebCard(val title: String? = null, val kind: WebCardKind) : ToolCardView
}

/** True when expanding this card can reveal at least one visible detail. */
fun ToolCardView.hasExpandableContent(): Boolean = when (this) {
    is ToolCardView.GenericCard -> !rawInput.isNullOrBlank() ||
        locations.orEmpty().any { it.isNotBlank() } ||
        content.orEmpty().any { block ->
            when (block) {
                is ContentBlockView.TextBlock -> block.text.isNotBlank()
                is ContentBlockView.ReasoningBlock -> block.text.isNotBlank()
                is ContentBlockView.ImageBlock -> block.attachment.attachmentId.isNotBlank()
            }
        }
    is ToolCardView.TerminalCard -> !description.isNullOrBlank() || !command.isNullOrBlank() ||
        !cwd.isNullOrBlank() || !output.isNullOrBlank() || outputBlocks.isNotEmpty() ||
        exitCode != null || !signal.isNullOrBlank()
    is ToolCardView.DiffCard -> diffs.isNotEmpty()
    is ToolCardView.SearchCard -> when (val value = matches) {
        is SearchMatches.FileMatches -> value.files.isNotEmpty() || truncated
        is SearchMatches.PathList -> value.paths.isNotEmpty() || truncated
    }
    is ToolCardView.ReadCard -> lines.isNotEmpty() || totalLines > 0
    is ToolCardView.WebCard -> when (val value = kind) {
        is WebCardKind.Search -> !value.answer.isNullOrBlank() || value.sources.isNotEmpty()
        is WebCardKind.Fetch -> value.url.isNotBlank() || value.statusCode != null
    }
}
