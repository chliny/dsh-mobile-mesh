package dev.dsh.mobile.mesh.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUploadRetryTest {
    @Test
    fun `transient transport failure retries with a fresh stream before reporting failure`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val upload = source.substringAfter("suspend fun uploadFile(").substringBefore("private suspend fun promptContent(")

        assertTrue(upload.contains("streamed.error.code == \"internal\""))
        assertTrue(upload.contains("delay(FILE_UPLOAD_RETRY_DELAY_MS)"))
        assertTrue(upload.contains("open()?.use { retryStream"))
        assertTrue(upload.contains("onProgress(0)"))
    }

    @Test
    fun `host capability and business errors are not retried`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val upload = source.substringAfter("suspend fun uploadFile(").substringBefore("private suspend fun promptContent(")

        assertTrue(upload.contains("streamed.error.code == \"internal\""))
        assertTrue(upload.contains("delivered.error.code != \"capability-unavailable\""))
    }
}
