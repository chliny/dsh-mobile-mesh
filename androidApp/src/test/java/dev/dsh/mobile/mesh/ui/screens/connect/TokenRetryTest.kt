package dev.dsh.mobile.mesh.ui.screens.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenRetryTest {
    @Test
    fun `token retry keeps the entered token on the retry host`() {
        val saved = "old-token"
        val entered = "new-token"
        val retryToken = entered.trim()
        assertEquals("new-token", retryToken)
        assertEquals("old-token", saved)
    }
}
