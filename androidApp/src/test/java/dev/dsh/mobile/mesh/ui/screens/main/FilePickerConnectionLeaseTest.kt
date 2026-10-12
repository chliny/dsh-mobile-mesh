package dev.dsh.mobile.mesh.ui.screens.main

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerConnectionLeaseTest {
    @Test
    fun `document picker and upload hold transport lifecycle lease until callback or completion`() {
        val chat = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatScreen.kt").readText()
        val store = File("src/main/java/dev/dsh/mobile/mesh/data/SessionStore.kt").readText()
        val upload = store.substringAfter("suspend fun uploadFile(").substringBefore("private suspend fun promptContent(")

        assertTrue(chat.contains("documentPickerLease = store.acquireFileTransferLease()"))
        assertTrue(chat.contains("documentPickerLease?.close()"))
        assertTrue(upload.contains("fileTransferLease ?: connectionManager.acquireFileTransferLease()"))
        assertTrue(upload.contains("transferLease.close()"))
    }
}
