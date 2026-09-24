package dev.dsh.mobile.mesh.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SshCredentials(
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyPassphrase: String? = null,
)

internal fun SshCredentials.hasCredentialFor(authentication: SshAuthentication): Boolean = when (authentication) {
    SshAuthentication.PASSWORD -> !password.isNullOrEmpty()
    SshAuthentication.PRIVATE_KEY -> !privateKey.isNullOrBlank()
}

/** Stores SSH material outside DataStore, encrypted by a non-exportable Android Keystore key. */
@Singleton
class SshSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "ssh-secrets").apply { mkdirs() }
    private val json = Json { encodeDefaults = true }

    fun put(hostId: String, credentials: SshCredentials) {
        val plaintext = json.encodeToString(SshCredentials.serializer(), credentials).toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
        plaintext.fill(0)
        file(hostId).writeText(value)
    }

    fun get(hostId: String): SshCredentials? {
        val raw = file(hostId).takeIf(File::isFile)?.readText() ?: return null
        return runCatching {
            val encrypted = Base64.decode(raw, Base64.NO_WRAP)
            val iv = encrypted.copyOfRange(0, IV_BYTES)
            val payload = encrypted.copyOfRange(IV_BYTES, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            json.decodeFromString(SshCredentials.serializer(), cipher.doFinal(payload).decodeToString())
        }.getOrNull()
    }

    fun remove(hostId: String) {
        file(hostId).delete()
    }

    fun exportCredentials(hostIds: Set<String>): Map<String, SshCredentials> = hostIds.mapNotNull { id ->
        get(id)?.let { id to it }
    }.toMap()

    private fun file(hostId: String): File {
        require(hostId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid SSH secret id" }
        return File(directory, hostId)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "dsh-mobile-mesh-ssh-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
