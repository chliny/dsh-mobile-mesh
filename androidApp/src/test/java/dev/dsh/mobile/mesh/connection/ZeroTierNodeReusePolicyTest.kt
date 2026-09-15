package dev.dsh.mobile.mesh.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierNodeReusePolicyTest {
    @Test
    fun `same-network retained node is reused even while service reports offline`() {
        assertTrue(shouldReuseZeroTierNode(hasNode = true, sameNetwork = true))
    }

    @Test
    fun `missing node requires first initialization`() {
        assertFalse(shouldReuseZeroTierNode(hasNode = false, sameNetwork = true))
    }

    @Test
    fun `different network cannot reuse retained identity`() {
        assertFalse(shouldReuseZeroTierNode(hasNode = true, sameNetwork = false))
    }
}
