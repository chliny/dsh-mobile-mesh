package dev.dsh.mobile.mesh.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUploadDiagnosticsTest {
    @Test
    fun `upload diagnostics capture attempt duration bytes and outcome`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()

        assertTrue(source.contains("file upload attempt=1 started bytes=\$size"))
        assertTrue(source.contains("elapsedMs=\$elapsedMs sentBytes=\$sentBytes"))
        assertTrue(source.contains("errorCode=\${result.error.code}"))
        assertTrue(source.contains("file upload retry scheduled bytes=\$size"))
    }

    @Test
    fun `lifecycle diagnostics correlate activity stop with connection suspension`() {
        val activity = File("src/main/java/dev/dsh/mobile/mesh/MainActivity.kt").readText()
        val connection = File("src/main/java/dev/dsh/mobile/mesh/connection/ConnectionManager.kt").readText()

        assertTrue(activity.contains("onStop: app activity stopped isChangingConfigurations="))
        assertTrue(connection.contains("Background retention disabled; suspending active connection phase="))
        assertTrue(connection.contains("keepConnectedInBackground=\$keepConnectedInBackground"))
    }
}
