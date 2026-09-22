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
     * version string, and there is no version-shaped branch in the client at all — see
     * `docs/COMPATIBILITY.md`.
     *
     * The current compatibility target is DeepSeek Harness tag `dsh-v0.1.6-alpha.2`. The wire
     * remains backward-compatible with the session and workspace-file protocol used by this client;
     * the tag is the release tested for the current DTOs and call shapes.
     */
    const val PROTOCOL_BASELINE = "0.1.6-alpha.2"
}
