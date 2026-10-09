package dev.dsh.mobile.mesh.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Automatically handles the common Chinese legacy encodings after strict UTF-8 validation. */
internal fun decodeWorkspaceText(bytes: ByteArray): String {
    if (isValidUtf8(bytes)) return bytes.toString(StandardCharsets.UTF_8)
    val candidates = listOf("GB18030", "Big5", "Shift_JIS", "EUC-KR", "windows-1252")
    return candidates.asSequence().mapNotNull { name ->
        runCatching { String(bytes, charset(name)) }.getOrNull()
    }.firstOrNull { decoded -> decoded.none { it == '\uFFFD' } }
        ?: bytes.toString(StandardCharsets.UTF_8)
}

internal fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
}.isSuccess
