package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierCallbackPolicyTest {
    @Test
    fun `native ZeroTier listener and callback implementations are kept`() {
        val rulesFile = sequenceOf(
            java.io.File("proguard-rules.pro"),
            java.io.File("androidApp/proguard-rules.pro"),
        ).firstOrNull { it.isFile } ?: error("proguard-rules.pro not found")
        val rules = rulesFile.readText()
        assertTrue(rules.contains("-keep interface com.zerotier.sockets.ZeroTierEventListener"))
        assertTrue(rules.contains("-keep class * implements com.zerotier.sockets.ZeroTierEventListener"))
    }
}
