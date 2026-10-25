package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry

/** The @ reference at the caret, with its replacement range and server search query. */
internal data class MentionTrigger(val start: Int, val end: Int, val query: String, val quoted: Boolean)

internal fun mentionTrigger(draft: String, caret: Int): MentionTrigger? {
    val position = caret.coerceIn(0, draft.length)
    val start = draft.lastIndexOf('@', position - 1)
    if (start < 0) return null
    val quoted = draft.getOrNull(start + 1) == '"'
    val queryStart = start + if (quoted) 2 else 1
    if (position < queryStart) return null
    val query = draft.substring(queryStart, position)
    if (query.contains('@') || query.contains('"') || (!quoted && query.any(Char::isWhitespace))) return null
    var end = position
    if (quoted) {
        val closing = draft.indexOf('"', position).takeIf { it >= 0 && '@' !in draft.substring(position, it) }
        if (closing != null) end = closing + 1
        else while (end < draft.length && !draft[end].isWhitespace() && draft[end] != '@') end++
    } else {
        while (end < draft.length && !draft[end].isWhitespace() && draft[end] != '@') end++
    }
    return MentionTrigger(start, end, query, quoted)
}

/** Preserve text on both sides; only a completed file/session mention receives a separator. */
internal fun replaceMentionTrigger(draft: String, trigger: MentionTrigger, mention: String, complete: Boolean): String {
    val suffix = draft.substring(trigger.end)
    val separator = if (complete && suffix.firstOrNull()?.isWhitespace() != true) " " else ""
    return draft.substring(0, trigger.start) + mention + separator + suffix
}

/** Follow the server's path and directory type; directories stay open for the next server search. */
internal fun fileMention(entry: WorkspaceDirectoryEntry, preserveQuote: Boolean): String? {
    val path = entry.name + if (entry.type == "directory" && !entry.name.endsWith('/')) "/" else ""
    if (path.any { it == '"' || it.code < 32 || it.code in 127..159 }) return null
    val quoted = preserveQuote || path.any(Char::isWhitespace)
    return when {
        !quoted -> "@$path"
        entry.type == "directory" -> "@\"$path"
        else -> "@\"$path\""
    }
}

/** A cancelled @ stays dismissed while editing its token, until another @ is entered. */
internal data class MentionPickerState(val dismissedStart: Int? = null) {
    fun visible(trigger: MentionTrigger?): Boolean = trigger != null && trigger.start != dismissedStart
    fun dismiss(trigger: MentionTrigger): MentionPickerState = MentionPickerState(trigger.start)
    fun onEdit(previous: String, next: String): MentionPickerState =
        if (next.count { it == '@' } > previous.count { it == '@' }) MentionPickerState() else this
}
