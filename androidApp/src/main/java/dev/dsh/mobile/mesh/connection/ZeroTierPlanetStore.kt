package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ZeroTierPlanetStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) throw IOException("ZeroTier planet exceeds $MAX_BYTES bytes")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Unable to open selected ZeroTier planet")
        require(bytes.isNotEmpty()) { "ZeroTier planet is empty" }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(directory(id).apply { mkdirs() }, "planet").writeBytes(bytes)
        bytes.fill(0)
        id
    }

    fun resolve(id: String): File? {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return null
        return File(directory(id), "planet").takeIf(File::isFile)
    }

    private fun directory(id: String) = File(context.noBackupFilesDir, "zerotier/planets/$id")

    private companion object { const val MAX_BYTES = 4096 }
}
