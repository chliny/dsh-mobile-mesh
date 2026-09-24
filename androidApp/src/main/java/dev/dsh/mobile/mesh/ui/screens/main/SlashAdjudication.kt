package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.CommandDescriptor

/** What the composer's send action does with a draft. */
internal sealed interface Submission {
    /** A registered command line; goes through `commands/execute`. */
    data class Command(val line: String) : Submission

    /** A server catalog command offered through a dedicated Android surface. */
    data class NativeCommand(val name: String) : Submission

    /** Ordinary text, including slash-prefixed skill invocation. */
    data class Prompt(val text: String) : Submission

    /** The command refuses attachments; preserve the draft and attachments. */
    data class Refused(val command: String, val reason: RefusalReason) : Submission
}

/** Why an invocation carrying attachments cannot be submitted. */
internal enum class RefusalReason {
    COMMAND_TAKES_NO_ATTACHMENTS,
    HOST_TOO_OLD,
}

/** Resolve one slash line against the server catalog and Android's server-backed native surfaces. */
internal fun adjudicate(
    draft: String,
    catalog: List<CommandDescriptor>,
    attachments: Int,
    hostAcceptsAttachments: Boolean,
    nativeCommands: Set<String> = emptySet(),
): Submission {
    val trimmed = draft.trim()
    if (!trimmed.startsWith("/")) return Submission.Prompt(draft)

    val separator = trimmed.indexOfFirst { it.isWhitespace() }
    val bare = separator == -1
    val name = (if (bare) trimmed else trimmed.substring(0, separator)).substring(1)
    if (name.isEmpty()) return Submission.Prompt(draft)
    if (name in nativeCommands && bare) {
        if (attachments > 0) return Submission.Refused(name, RefusalReason.COMMAND_TAKES_NO_ATTACHMENTS)
        return Submission.NativeCommand(name)
    }
    val descriptor = catalog.firstOrNull { it.name == name } ?: return Submission.Prompt(draft)
    if (descriptor.input == null && !bare) return Submission.Prompt(draft)
    if (attachments > 0) {
        if (!descriptor.acceptsAttachments) return Submission.Refused(name, RefusalReason.COMMAND_TAKES_NO_ATTACHMENTS)
        if (!hostAcceptsAttachments) return Submission.Refused(name, RefusalReason.HOST_TOO_OLD)
    }
    return Submission.Command(trimmed)
}
