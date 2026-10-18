package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

/** Discard an earlier Host read after a newer settings invalidation starts. */
internal class TranscriptPresentationReadFence {
    private val latest = AtomicLong()
    fun begin(): Long = latest.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = latest.get() == ticket
}

/** Host ui-chat preferences; missing values follow the Web client's non-desktop defaults. */
data class TranscriptPresentation(
    val mode: Mode = Mode.Detailed,
    val collapseTiming: CollapseTiming = CollapseTiming.Completion,
) {
    enum class Mode { Compact, Standard, Detailed, Verbose }
    enum class CollapseTiming { Completion, NextInput }

    val foldCompletedTurns: Boolean get() = mode != Mode.Verbose
    val groupLiveSteps: Boolean get() = mode == Mode.Compact || mode == Mode.Standard
    val settledReasoningPreview: Boolean get() = mode != Mode.Compact
    val liveProcessDetail: Boolean get() = mode == Mode.Standard || mode == Mode.Detailed
}

/** Read only the effective Host namespace value, never a local copy of the setting. */
fun transcriptPresentationOf(describe: SettingsDescribeValue): TranscriptPresentation {
    val section = describe.namespaces.firstOrNull { it.ns == "ui-chat" }?.value as? JsonObject
    val mode = when ((section?.get("transcriptView"))?.jsonPrimitive?.contentOrNull) {
        "compact" -> TranscriptPresentation.Mode.Compact
        "standard" -> TranscriptPresentation.Mode.Standard
        "verbose" -> TranscriptPresentation.Mode.Verbose
        "detailed", "normal", "expanded" -> TranscriptPresentation.Mode.Detailed
        else -> TranscriptPresentation.Mode.Detailed
    }
    val timing = when (section?.get("collapseTiming")?.jsonPrimitive?.contentOrNull) {
        "next-input" -> TranscriptPresentation.CollapseTiming.NextInput
        else -> TranscriptPresentation.CollapseTiming.Completion
    }
    return TranscriptPresentation(mode, timing)
}
