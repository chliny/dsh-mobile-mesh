package dev.dsh.mobile.mesh.connection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionServiceTimeoutPolicyTest {
    private val source by lazy {
        File("src/main/java/dev/dsh/mobile/mesh/connection/ConnectionService.kt").readText()
    }

    @Test
    fun `data sync timeout overloads stop the matching service start safely`() {
        assertTrue(source.contains("override fun onTimeout(startId: Int)"))
        assertTrue(source.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(source.contains("stopSelf(startId)"))
    }

    @Test
    fun `pinning service is not sticky`() {
        assertTrue(source.contains("return START_NOT_STICKY"))
        assertFalse(source.contains("return START_STICKY"))
    }
}
