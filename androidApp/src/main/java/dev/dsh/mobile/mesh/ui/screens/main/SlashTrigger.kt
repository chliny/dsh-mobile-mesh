package dev.dsh.mobile.mesh.ui.screens.main

/** The slash token under the caret, regardless of text on either side of it. */
internal data class SlashTrigger(val start: Int, val end: Int, val query: String)

internal fun slashTrigger(draft: String, caret: Int): SlashTrigger? {
    val position = caret.coerceIn(0, draft.length)
    val start = draft.lastIndexOf('/', position - 1)
    if (start < 0 || draft.substring(start + 1, position).any(Char::isWhitespace)) return null
    var end = position
    while (end < draft.length && !draft[end].isWhitespace() && draft[end] != '/') end++
    return SlashTrigger(start, end, draft.substring(start + 1, position))
}

/** Replace only the active token; all text around it remains untouched. */
internal fun replaceSlashTrigger(draft: String, trigger: SlashTrigger, prefix: String): String =
    draft.replaceRange(trigger.start, trigger.end, prefix)

/** Keep a cancelled token dismissed while typing, but reopen for the next slash. */
internal data class SlashPickerState(val dismissedStart: Int? = null) {
    fun visible(trigger: SlashTrigger?): Boolean = trigger != null && trigger.start != dismissedStart
    fun dismiss(trigger: SlashTrigger): SlashPickerState = SlashPickerState(trigger.start)
    fun onEdit(previous: String, next: String): SlashPickerState =
        if (next.count { it == '/' } > previous.count { it == '/' }) SlashPickerState() else this
}
