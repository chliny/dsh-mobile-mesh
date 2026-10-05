package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModsBandCompatibilityTest {
    private fun error(endpoint: String, code: String) = RpcError(
        code, "unavailable", buildJsonObject { put("endpoint", endpoint) },
    )

    @Test fun `old host missing the exact optional stream does not affect the session`() {
        assertTrue(isUnsupportedModsBandFailure(error("claudeCodeMods/watchBand", "gateway/invocation-unavailable"), false))
    }

    @Test fun `carrier failure and unrelated route failure cannot hide a broken connection`() {
        assertFalse(isUnsupportedModsBandFailure(error("claudeCodeMods/watchBand", "gateway/invocation-unavailable"), true))
        assertFalse(isUnsupportedModsBandFailure(error("session/follow", "gateway/invocation-unavailable"), false))
        assertFalse(isUnsupportedModsBandFailure(error("claudeCodeMods/watchBand", "gateway/internal"), false))
    }
}
