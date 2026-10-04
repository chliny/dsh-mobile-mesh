package dev.dsh.mobile.mesh.ui.components

/** One displayed git-style diff line; the marker is not part of the source code being highlighted. */
internal data class UnifiedDiffLine(val marker: Char, val source: String)

/**
 * Diff replacement text into a single ordered stream of context, removals and additions.
 * The quadratic LCS is used only for small edits; large inputs use shared prefix/suffix and
 * a single replacement group so opening a transcript never allocates a huge comparison matrix.
 */
internal fun unifiedDiffLines(oldText: String?, newText: String?): List<UnifiedDiffLine> {
    val old = oldText?.takeIf(String::isNotEmpty)?.lineSequence()?.toList().orEmpty()
    val new = newText?.takeIf(String::isNotEmpty)?.lineSequence()?.toList().orEmpty()
    val result = ArrayList<UnifiedDiffLine>(old.size + new.size)
    var prefix = 0
    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) {
        result += UnifiedDiffLine(' ', old[prefix])
        prefix++
    }
    var suffix = 0
    while (suffix < minOf(old.size, new.size) - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
    ) suffix++
    val removed = old.subList(prefix, old.size - suffix)
    val added = new.subList(prefix, new.size - suffix)
    if (removed.size > 128 || added.size > 128 || removed.size.toLong() * added.size.toLong() > 16_384L) {
        removed.forEach { result += UnifiedDiffLine('-', it) }
        added.forEach { result += UnifiedDiffLine('+', it) }
    } else {
        val lengths = Array(removed.size + 1) { IntArray(added.size + 1) }
        for (i in removed.indices.reversed()) for (j in added.indices.reversed()) {
            lengths[i][j] = if (removed[i] == added[j]) 1 + lengths[i + 1][j + 1]
            else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < removed.size || j < added.size) {
            when {
                i < removed.size && j < added.size && removed[i] == added[j] -> {
                    result += UnifiedDiffLine(' ', removed[i]); i++; j++
                }
                i < removed.size && (j == added.size || lengths[i + 1][j] >= lengths[i][j + 1]) -> {
                    result += UnifiedDiffLine('-', removed[i++])
                }
                else -> result += UnifiedDiffLine('+', added[j++])
            }
        }
    }
    for (index in suffix downTo 1) result += UnifiedDiffLine(' ', old[old.size - index])
    return result
}
