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
     * The current compatibility target is DeepSeek Harness tag `dsh-v0.2.0-rc.2`. The tag is the
     * release tested for the current DTOs and call shapes; older hosts remain supported through
     * the connection-time protocol detection and optional API handling described in
     * `docs/COMPATIBILITY.md`.
     */
    const val PROTOCOL_BASELINE = "0.2.0-rc.2"
}
