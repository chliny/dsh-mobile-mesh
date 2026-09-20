package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.PromptContentPart
import dev.dsh.mobile.mesh.core.wire.dto.SessionAddress
import dev.dsh.mobile.mesh.core.wire.dto.SubagentPromptRequest

internal fun canContinue(address: SessionAddress?): Boolean =
    (address as? SessionAddress.Subagent)?.mode == "continuable"

internal fun subagentPromptRequest(
    address: SessionAddress.Subagent,
    requestId: String,
    text: String,
    delivery: String,
    clientTimeZone: String,
): SubagentPromptRequest = SubagentPromptRequest(
    requestId = requestId,
    parentSessionId = address.parentSessionId,
    childSessionId = address.childSessionId,
    mode = "continuable",
    delivery = delivery,
    content = listOf(PromptContentPart.Text(text)),
    clientTimeZone = clientTimeZone,
)
