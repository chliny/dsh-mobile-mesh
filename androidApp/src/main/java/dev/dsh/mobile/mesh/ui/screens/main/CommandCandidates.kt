package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.CommandDescriptor
import dev.dsh.mobile.mesh.core.wire.dto.SkillEntry

/** One slash candidate backed by the session's command or skill catalog. */
internal data class SlashCandidate(
    val name: String,
    val description: String,
    val prefix: String,
    val kind: Kind,
) {
    enum class Kind { HOST_COMMAND, SKILL, NATIVE }
}

/** Build the current Session's visible command candidates without inventing server catalog entries. */
internal fun slashCandidates(
    commands: List<CommandDescriptor>,
    skills: List<SkillEntry>,
    nativeNames: Set<String>,
): List<SlashCandidate> {
    val host = commands.map { SlashCandidate(it.name, it.description, it.draftPrefix, SlashCandidate.Kind.HOST_COMMAND) }
    val native = nativeNames.map { SlashCandidate(it, "", "/$it", SlashCandidate.Kind.NATIVE) }
    val skillRows = skills.map { SlashCandidate(it.name, it.description, "/${it.name} ", SlashCandidate.Kind.SKILL) }
    return (native + host + skillRows).distinctBy(SlashCandidate::name)
}

/** Web-style prefix-first, case-insensitive subsequence ranking for the visible menu. */
internal fun rankSlashCandidates(candidates: List<SlashCandidate>, query: String): List<SlashCandidate> =
    candidates.mapNotNull { candidate ->
        val rank = subsequenceRank(candidate.name, query) ?: subsequenceRank(candidate.description, query)
            ?: return@mapNotNull null
        candidate to rank
    }.sortedWith(compareBy<Pair<SlashCandidate, Int>> { it.second }.thenBy { it.first.name.lowercase() })
        .map { it.first }

private fun subsequenceRank(value: String, query: String): Int? {
    if (query.isEmpty()) return 0
    val haystack = value.lowercase()
    val needle = query.lowercase()
    val first = haystack.indexOf(needle)
    if (first >= 0) return if (first == 0) 0 else 10 + first
    var cursor = 0
    var gaps = 0
    var previous = -1
    for (character in needle) {
        val found = haystack.indexOf(character, cursor)
        if (found < 0) return null
        if (previous >= 0) gaps += found - previous - 1
        previous = found
        cursor = found + 1
    }
    return 100 + gaps
}
