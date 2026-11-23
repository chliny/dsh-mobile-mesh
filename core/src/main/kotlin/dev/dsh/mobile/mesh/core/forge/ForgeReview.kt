package dev.dsh.mobile.mesh.core.forge

import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffFile
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffHunk

/**
 * One displayed diff line with the line numbers its hunk gives it.
 *
 * The App renders a single column; the old and new line-number gutters stay separate so a tap can
 * select the left (old) or right (new) side exactly as the Web's two-column diff does.
 */
data class ForgeDiffRow(
    /** Index of the hunk the row belongs to. */
    val hunkIndex: Int,
    /** ` `, `+`, `-`, or `\` when the patch records a missing final newline. */
    val marker: Char,
    /** Line content without the leading patch marker. */
    val text: String,
    /** Old-side line number, or null for an added line. */
    val oldLine: Int?,
    /** New-side line number, or null for a removed line. */
    val newLine: Int?,
)

/**
 * Expand hunks into display rows and track both line-number counters.
 *
 * A `\ No newline at end of file` marker has no line number on either side, matching Git's patch
 * semantics and the Web renderer.
 */
fun forgeDiffRows(hunks: List<ForgeDiffHunk>): List<ForgeDiffRow> {
    val rows = ArrayList<ForgeDiffRow>()
    hunks.forEachIndexed { hunkIndex, hunk ->
        var oldLine = hunk.oldStart
        var newLine = hunk.newStart
        for (line in hunk.lines) {
            val marker = line.firstOrNull()?.takeIf { it == ' ' || it == '+' || it == '-' || it == '\\' } ?: ' '
            val text = if (line.isEmpty()) line else line.drop(1)
            when (marker) {
                '+' -> {
                    rows += ForgeDiffRow(hunkIndex, marker, text, null, newLine)
                    newLine++
                }
                '-' -> {
                    rows += ForgeDiffRow(hunkIndex, marker, text, oldLine, null)
                    oldLine++
                }
                '\\' -> rows += ForgeDiffRow(hunkIndex, marker, text, null, null)
                else -> {
                    rows += ForgeDiffRow(hunkIndex, marker, text, oldLine, newLine)
                    oldLine++
                    newLine++
                }
            }
        }
    }
    return rows
}

/** A comment on consecutive lines of one side of one displayed hunk. */
data class ForgeReviewComment(
    /** Repository-relative destination path, including for renamed files. */
    val path: String,
    /** Old (`L`) or new (`R`) side of the displayed diff. */
    val side: String,
    val startLine: Int,
    val endLine: Int,
    /** Complete displayed hunk captured when the comment was created. */
    val hunk: String,
    val body: String = "",
)

/**
 * Where a comment was selected. Local sources name the scope and the comparison the reader saw;
 * change-request sources name the URL and the exact commit whose new side is displayed.
 */
sealed interface ForgeReviewSource {
    /** Consecutive commits the reader showed, or null for the whole comparison. */
    val commits: ForgeCommitRange?

    data class Local(
        /** One of the `ForgeLocalScope` kinds: branch, uncommitted, unstaged, staged. */
        val scope: String,
        /** Human-readable base the branch scope compared with, or null for other scopes/automatic. */
        val baseLabel: String?,
        /** Ref the branch scope compared with, or null for the automatic fork point. */
        val baseRef: String?,
        override val commits: ForgeCommitRange?,
    ) : ForgeReviewSource

    data class Change(
        val url: String,
        /** Head commit, or the last chosen commit shown by the diff. */
        val headSha: String,
        override val commits: ForgeCommitRange?,
    ) : ForgeReviewSource
}

/** One selection ready to be turned into a composer reference. */
data class ForgeReviewSelection(
    val source: ForgeReviewSource,
    val comment: ForgeReviewComment,
)

/**
 * Capture a range only when both endpoints belong to the same displayed hunk on the same side.
 *
 * @return the frozen comment, or null when the range crosses a hunk or lies outside the side.
 */
fun selectForgeComment(
    file: ForgeDiffFile,
    side: String,
    from: Int,
    to: Int,
): ForgeReviewComment? {
    if (side != "L" && side != "R") return null
    val startLine = minOf(from, to)
    val endLine = maxOf(from, to)
    if (startLine <= 0) return null
    val hunk = file.hunks.firstOrNull { hunk ->
        val start = if (side == "L") hunk.oldStart else hunk.newStart
        val count = if (side == "L") hunk.oldLines else hunk.newLines
        startLine >= start && endLine < start + count
    } ?: return null
    val hunkText = forgeHunkText(hunk)
    return ForgeReviewComment(
        path = file.path,
        side = side,
        startLine = startLine,
        endLine = endLine,
        hunk = hunkText,
        body = "",
    )
}

/** One hunk as the patch text a reviewer sees, including its `@@` range header. */
fun forgeHunkText(hunk: ForgeDiffHunk): String {
    val oldStart = if (hunk.oldLines == 0) hunk.oldStart - 1 else hunk.oldStart
    val newStart = if (hunk.newLines == 0) hunk.newStart - 1 else hunk.newStart
    return buildString {
        append("@@ -").append(oldStart).append(',').append(hunk.oldLines)
        append(" +").append(newStart).append(',').append(hunk.newLines).append(" @@")
        for (line in hunk.lines) append('\n').append(line)
    }
}

/** `R12` or `L3–5`, matching the Web's range label. */
fun forgeReviewRange(comment: ForgeReviewComment): String =
    "${comment.side}${comment.startLine}${if (comment.endLine == comment.startLine) "" else "–${comment.endLine}"}"

/**
 * Format the frozen reference an App composer sends as a normal user message.
 *
 * The shape is the Web's `formatReviewComment`: a numbered `## User Comment` section, the file,
 * side and lines, the source (local scope with its base, or the change request and commit), the
 * captured hunk in a fenced block, and the user's comment. The fence grows past any backtick run
 * inside the hunk so code containing Markdown fences stays inside the block.
 */
fun formatForgeReviewComment(selection: ForgeReviewSelection, index: Int): String {
    val (source, comment) = selection
    val range = if (comment.startLine == comment.endLine) {
        comment.startLine.toString()
    } else {
        "${comment.startLine}-${comment.endLine}"
    }
    val longestFence = Regex("`+").findAll(comment.hunk).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestFence + 1))
    val commits = source.commits?.let { range ->
        val value = if (range.from == range.to) range.from else "${range.from}^..${range.to}"
        "\nCommits: $value"
    }.orEmpty()
    val origin = when (source) {
        is ForgeReviewSource.Change ->
            "Source: ${source.url}\nCommit: ${source.headSha}$commits"
        is ForgeReviewSource.Local -> buildString {
            append("Source: Local (").append(source.scope).append(')')
            if (source.baseLabel != null) append("\nBase: ").append(source.baseLabel)
            append(commits)
        }
    }
    return buildString {
        append("## User Comment ").append(index).append('\n')
        append("File: ").append(comment.path).append('\n')
        append("Side: ").append(comment.side).append('\n')
        append("Lines: ").append(range).append('\n')
        append(origin).append('\n')
        append("Diff hunk:\n")
        append(fence).append("diff\n")
        append(comment.hunk).append('\n')
        append(fence).append('\n')
        append("Comment:\n")
        append(comment.body)
    }
}

/** Why a chosen run of commits is accepted or refused. */
sealed interface ForgeCommitRangeCheck {
    data class Accepted(val start: String) : ForgeCommitRangeCheck
    data object Missing : ForgeCommitRangeCheck
    data object NotLinear : ForgeCommitRangeCheck
}

/**
 * Validate a run of commits listed oldest first.
 *
 * Two ends describe a run only when each commit after the first has the commit listed before it as
 * its first parent, so the diff from the first commit's parent to the last is exactly the run.
 */
fun checkForgeCommitRange(commits: List<dev.dsh.mobile.mesh.core.wire.dto.ForgeCommit>, from: String, to: String): ForgeCommitRangeCheck {
    val first = commits.indexOfFirst { it.sha == from }
    val last = commits.indexOfFirst { it.sha == to }
    if (first < 0 || last < 0 || last < first) return ForgeCommitRangeCheck.Missing
    for (index in first + 1..last) {
        if (commits[index].parent != commits[index - 1].sha) return ForgeCommitRangeCheck.NotLinear
    }
    return ForgeCommitRangeCheck.Accepted(commits[first].sha)
}

/**
 * Format a whole review thread as the Web's frozen `## Review Thread` reference.
 *
 * Comment bodies are quoted line by line so their Markdown cannot add headings to the user message.
 */
fun formatForgeThreadQuote(
    thread: dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewThread,
    url: String,
    index: Int,
): String {
    val anchor = thread.current ?: thread.original
    val lines = anchor?.let {
        if (it.startLine == it.endLine) it.startLine.toString() else "${it.startLine}-${it.endLine}"
    }.orEmpty()
    val where = when {
        thread.subject == "file" || anchor == null -> "Lines: whole file"
        thread.side == "R" -> "Side: R\nLines: $lines\nCommit: ${anchor.commit}"
        else -> "Side: L (old side: the lines number the file before the pull request's changes)\n" +
            "Lines: $lines\nDiff head: ${anchor.commit}"
    }
    val state = buildList {
        add(if (thread.resolved) "resolved" else "unresolved")
        if (thread.subject == "line" && thread.current == null) add("outdated")
    }.joinToString(", ")
    val fence = thread.diffHunk?.let { hunk ->
        val longest = Regex("`+").findAll(hunk).maxOfOrNull { it.value.length } ?: 0
        "`".repeat(maxOf(3, longest + 1))
    }
    val hunk = if (thread.diffHunk == null || fence == null) {
        ""
    } else {
        "Diff hunk:\n$fence" + "diff\n" + thread.diffHunk + "\n" + fence + "\n"
    }
    val comments = thread.comments.joinToString("\n") { comment ->
        val author = comment.author?.login?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "Unknown author"
        val body = comment.body.split('\n').joinToString("\n") { line ->
            if (line.trim().isEmpty()) ">" else "> $line"
        }
        "$author (${comment.createdAt}):\n$body"
    }
    val link = thread.comments.firstOrNull()?.url?.takeIf { it.isNotBlank() } ?: url
    return "## Review Thread $index\nFile: ${thread.path}\n$where\nState: $state\nSource: $link\n$hunk" +
        "Comments:\n$comments"
}
