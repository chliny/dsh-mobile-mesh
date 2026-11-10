package dev.dsh.mobile.mesh.core

/** Core module placeholder for the baseline build; wire protocol code lands here. */
object DshCore {
    /**
     * The harness release this client's DTOs and call shapes were ported from and verified
     * against.
     *
     * Display-only, and it has no version to compare itself against: 0.1.2 removed
     * `host.describe`, so the harness does not tell a client what it is. Where a shape used to
     * differ between releases this client read the difference off the wire rather than off a
     * version string, and there is no version-shaped branch in the client at all — see the
     * "Compatibility and protocol baseline" section of the root `README.md`. Two occurrences
     * confirm the rule: the terminal protocol is selected by the read-only
     * `terminal/environment.maxBufferedBytes` field, and session capabilities by the
     * `session/list` / `session/projections` probes.
     *
     * The current compatibility target is DeepSeek Harness tag `dsh-v0.2.2-rc.1` (commit
     * `66491b05e3`). The wire types were also checked against upstream `master` at `fcc0ad4621`
     * (811 commits past that release, itself untagged): where that branch already changed a
     * shape this client calls — the `subagents/interruptByUser` rename and the trimmed
     * `subagents/prompt` request — the client sends the released form and negotiates the newer
     * route from structured missing-route evidence. Older hosts remain supported through the
     * connection-time protocol detection and optional API handling described in the README.
     */
    const val PROTOCOL_BASELINE = "0.2.2-rc.1"
}
