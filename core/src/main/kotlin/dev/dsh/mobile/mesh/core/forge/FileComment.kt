package dev.dsh.mobile.mesh.core.forge

/**
 * A comment on consecutive lines of a file opened in the content viewer.
 *
 * This is deliberately not a [ForgeReviewComment]: a content page has no old/new diff side and
 * no patch hunk to quote. It is the frozen excerpt of the file the reader saw.
 */
data class FileLineComment(
    /** Workspace path shown in the file content page. */
    val path: String,
    val startLine: Int,
    val endLine: Int,
    /** Selected lines exactly as the reader saw them, without line-number gutters. */
    val excerpt: String,
    val body: String = "",
)

/**
 * Format a frozen file-content comment for the composer.
 *
 * It mirrors the shape of [formatForgeReviewComment] so a reader's comment arrives with the file,
 * a stable line range, the exact excerpt, and the comment itself. The fence grows past any
 * backtick run inside the excerpt so Markdown code stays inside the block.
 */
fun formatFileLineComment(comment: FileLineComment, index: Int): String {
    val range = if (comment.startLine == comment.endLine) {
        comment.startLine.toString()
    } else {
        "${comment.startLine}-${comment.endLine}"
    }
    val longestFence = Regex("`+").findAll(comment.excerpt).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestFence + 1))
    return buildString {
        append("## File Comment ").append(index).append('\n')
        append("File: ").append(comment.path).append('\n')
        append("Lines: ").append(range).append('\n')
        append("Content:\n")
        append(fence).append("text\n")
        append(comment.excerpt).append('\n')
        append(fence).append('\n')
        append("Comment:\n")
        append(comment.body)
    }
}
