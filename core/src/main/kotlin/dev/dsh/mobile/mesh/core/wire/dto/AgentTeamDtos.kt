package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The `agentTeam` projection: the Agent Team task board the Host folds from one Session's durable
 * Team events.
 *
 * Ported from upstream `master` `packages/experimental/agent-team/src/types.ts`; the published
 * 0.2.2 release ships an earlier projection shape (a roster plus tasks) whose roster this client
 * does not render. Unknown fields are ignored, so such a Host still yields a correct task board.
 * Every rendered field is
 * Host-owned state: [AgentTeamTaskView.ready] and [AgentTeamTaskView.writeScopeWarnings] are
 * computed against the whole board server-side, [AgentTeamTaskView.ownerName] is the immutable
 * agent path captured at assignment, and `deleted` tasks never reach the wire at all. Clients render
 * the board in the Host's order with the Host's values; they do not re-derive readiness, filter
 * tasks, sort them, or synthesize members the Host did not publish.
 *
 * The projection is registered per Session, so a Host that composes the Agent Team service
 * publishes this key for every Session it summarizes. [agentTeamView] answers `null` when the
 * current Session carries no readable board (an older Host, or a payload this build cannot trust),
 * which callers render as "no team surface" rather than an empty or invented one.
 */

/** One task in the Host's non-deleted Agent Team board. */
@Serializable
data class AgentTeamTaskView(
    /** Team-local durable task identity, e.g. `task-3`. */
    @SerialName("id") val id: String,
    /** Monotonic durable revision; every task mutation increments it. */
    @SerialName("revision") val revision: Long = 0,
    /** The task's name. */
    @SerialName("subject") val subject: String,
    /** The task's full description; the board card leaves it out to keep rows scannable. */
    @SerialName("description") val description: String = "",
    /** One of `pending`, `in_progress`, `completed`; deleted tasks are omitted by the Host. */
    @SerialName("status") val status: String,
    /** Ids of the tasks that must complete before this one can start. */
    @SerialName("blockedBy") val blockedBy: List<String> = emptyList(),
    /** File or directory prefixes the owning agent expects to modify. */
    @SerialName("writeScopes") val writeScopes: List<String> = emptyList(),
    /** Owning Session identity; absent on historical records without an owner. */
    @SerialName("ownerId") val ownerId: String? = null,
    /** Immutable agent path captured at assignment; absent when the Host has no saved name. */
    @SerialName("ownerName") val ownerName: String? = null,
    /** Host-computed: pending with every blocker completed. Render as given; never recompute. */
    @SerialName("ready") val ready: Boolean = false,
    /** Host advisory warnings, e.g. overlapping write scopes with another in-progress task. */
    @SerialName("writeScopeWarnings") val writeScopeWarnings: List<String> = emptyList(),
)

/**
 * The `agentTeam` projection value for one Session.
 *
 * [failure] is the Host's first rejected persisted Team record: the board then keeps rendering the
 * last valid state, so a non-null failure must be shown prominently instead of presenting the
 * tasks as current. Its absence means the Host has no recorded rejection.
 */
@Serializable
data class AgentTeamProjectionView(
    /** Saved Team mode for this root Session; the board below is retained either way. */
    @SerialName("enabled") val enabled: Boolean = false,
    /** Non-deleted tasks in the Host's board order. */
    @SerialName("tasks") val tasks: List<AgentTeamTaskView> = emptyList(),
    /** Host-reported projection failure; the tasks are then only the last valid state. */
    @SerialName("failure") val failure: String? = null,
)

/**
 * Decode the Host's `agentTeam` projection from the open Session's projection map.
 *
 * @param element the raw `conversation.projections["agentTeam"]` value, or null when the Host
 *   published no such key (an older Host composes no Agent Team service).
 * @return the Host's board, or null when the key is absent or the payload cannot be read as this
 *   contract. A malformed payload hides the surface instead of rendering half-trusted state.
 */
fun agentTeamView(element: JsonElement?): AgentTeamProjectionView? {
    if (element == null) return null
    return runCatching {
        decodeFromJsonElement(AgentTeamProjectionView.serializer(), element)
    }.getOrNull()
}
