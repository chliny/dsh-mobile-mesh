package dev.dsh.mobile.mesh.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.mozilla.universalchardet.UniversalDetector

/**
 * Decode a workspace file the Host could not serve as UTF-8 text.
 *
 * The workspace text route rejects anything that is not strict UTF-8, so a legacy-encoded
 * file arrives here as raw bytes. A BOM is authoritative; otherwise a statistical detector
 * decides for real legacy text, and a small plausibility score breaks ties for very short
 * files where the detector has too little data. The score also keeps ASCII-heavy HTML with a
 * few Chinese characters from falling back to a Western single-byte encoding.
 */
internal fun decodeWorkspaceText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    decodeBom(bytes)?.let { return it }
    // UTF-16 ASCII is also valid UTF-8 because NUL is a legal code point; decide on the NUL
    // pattern before the UTF-8 check or every other character would arrive as a control byte.
    decodeUtf16WithoutBom(bytes)?.let { return it }
    if (isValidUtf8(bytes)) return bytes.toString(StandardCharsets.UTF_8).stripBom()

    val sample = detectionSample(bytes)
    val detected = detectCharset(sample)
    // A statistical verdict is only trusted once it has enough bytes to be meaningful, and
    // only for East Asian legacy sets, the families this route exists to recover.
    if (detected != null && sample.size >= TRUSTED_DETECTION_BYTES && isEastAsianCharset(detected)) {
        charset(detected)?.let { candidate ->
            decodeStrict(bytes, candidate)?.stripBom()?.let { return it }
        }
    }

    val best = bestScoringCharset(sample, detected) ?: return bytes.toString(StandardCharsets.UTF_8).stripBom()
    return decodeLenient(bytes, charset(best) ?: return bytes.toString(StandardCharsets.UTF_8).stripBom()).stripBom()
}

internal fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
}.isSuccess

/** UTF-8, UTF-16 and UTF-32 byte-order marks, decoded before any heuristic can see the bytes. */
private fun decodeBom(bytes: ByteArray): String? {
    val offset = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> 3
        bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> 4
        bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> 4
        bytes.startsWith(0xFF, 0xFE) -> 2
        bytes.startsWith(0xFE, 0xFF) -> 2
        else -> return null
    }
    return when {
        offset == 3 -> decodeStrict(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8)
        bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> decodeUtf32(bytes, offset, littleEndian = true)
        bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> decodeUtf32(bytes, offset, littleEndian = false)
        bytes.startsWith(0xFF, 0xFE) -> decodeStrict(bytes, offset, bytes.size - offset, StandardCharsets.UTF_16LE)
        else -> decodeStrict(bytes, offset, bytes.size - offset, StandardCharsets.UTF_16BE)
    }?.stripBom()
}

/** A UTF-16 file without a BOM leaves a NUL in every other byte for its ASCII run. */
private fun decodeUtf16WithoutBom(bytes: ByteArray): String? {
    if (bytes.size < 8) return null
    val sampleSize = minOf(bytes.size, 4096).let { if (it % 2 == 0) it else it - 1 }
    if (sampleSize < 8) return null
    val pairs = sampleSize / 2
    var evenNuls = 0
    var oddNuls = 0
    for (index in 0 until sampleSize step 2) {
        if (bytes[index].toInt() == 0) evenNuls++
        if (bytes[index + 1].toInt() == 0) oddNuls++
    }
    // Only an ASCII-heavy sample gives a strong NUL signal; a Chinese-only UTF-16 file has
    // high bytes that look like ordinary legacy double-byte text, so it is left to the detector.
    val charsetName = when {
        oddNuls >= pairs / 4 && evenNuls <= pairs / 16 -> "UTF-16LE"
        evenNuls >= pairs / 4 && oddNuls <= pairs / 16 -> "UTF-16BE"
        else -> return null
    }
    val candidate = charset(charsetName) ?: return null
    val text = decodeStrict(bytes, candidate) ?: return null
    if (text.isEmpty() || text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\r\n\t") }) return null
    return text.stripBom()
}

private fun detectionSample(bytes: ByteArray): ByteArray =
    if (bytes.size <= DETECTION_BYTES) bytes else bytes.copyOf(DETECTION_BYTES)

private fun detectCharset(sample: ByteArray): String? = runCatching {
    val detector = UniversalDetector()
    detector.handleData(sample, 0, sample.size)
    detector.dataEnd()
    detector.detectedCharset
}.getOrNull()?.takeIf { charset(it) != null }

private fun bestScoringCharset(sample: ByteArray, detected: String?): String? {
    val candidates = LinkedHashSet<String>()
    detected?.let { candidates += it }
    candidates += FALLBACK_CHARSETS
    var best: String? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (name in candidates) {
        val candidate = charset(name) ?: continue
        val text = decodeSample(sample, candidate) ?: continue
        val score = scoreText(text)
        if (score > bestScore) {
            best = name
            bestScore = score
        }
    }
    return best
}

/**
 * Decode a sample for scoring, tolerating a cut multi-byte character at the sample boundary.
 * A truncated tail must not disqualify an otherwise correct encoding.
 */
private fun decodeSample(sample: ByteArray, charset: Charset): String? {
    for (dropped in 0..3) {
        val end = sample.size - dropped
        if (end <= 0) break
        decodeStrict(sample, 0, end, charset)?.let { return it }
    }
    return null
}

/** A small plausibility score: real CJK wins over byte-per-character mojibake. */
private fun scoreText(text: String): Double {
    var score = 0.0
    for (char in text) {
        val type = Character.getType(char)
        score += when {
            char == '\uFFFD' -> -80.0
            char == '\u0000' -> -160.0
            Character.isISOControl(char) && char != '\n' && char != '\r' && char != '\t' -> -40.0
            type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() -> -25.0
            Character.isSurrogate(char) -> -40.0
            char in '\uFF61'..'\uFF9F' -> 2.0
            isCjk(char) -> 10.0
            isKanaOrHangul(char) -> 8.0
            char in ' '..'~' -> 1.0
            type == Character.LOWERCASE_LETTER.toInt() || type == Character.UPPERCASE_LETTER.toInt() -> 2.0
            else -> 0.5
        }
    }
    return score
}

private fun isCjk(char: Char): Boolean = when (Character.UnicodeBlock.of(char)) {
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
    -> true
    else -> false
}

private fun isKanaOrHangul(char: Char): Boolean = when (Character.UnicodeBlock.of(char)) {
    Character.UnicodeBlock.HIRAGANA,
    Character.UnicodeBlock.KATAKANA,
    Character.UnicodeBlock.HANGUL_SYLLABLES,
    Character.UnicodeBlock.HANGUL_JAMO,
    -> true
    else -> false
}

private fun isEastAsianCharset(name: String): Boolean {
    val upper = name.uppercase()
    return upper in EAST_ASIAN_CHARSETS || upper.startsWith("UTF-16") || upper.startsWith("UTF-32")
}

private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
    decodeStrict(bytes, 0, bytes.size, charset)

private fun decodeStrict(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, length))
        .toString()
} catch (_: CharacterCodingException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun decodeLenient(bytes: ByteArray, charset: Charset): String = String(bytes, charset)

/** Some legacy tools write a UTF-16 BOM as a decoded character instead of stripping it. */
private fun String.stripBom(): String = if (startsWith('\uFEFF')) substring(1) else this

private fun charset(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index].toInt() and 0xFF != prefix[index]) return false
    }
    return true
}

/** Manually decode UTF-32: Android does not ship every UTF-32 charset name. */
private fun decodeUtf32(bytes: ByteArray, offset: Int, littleEndian: Boolean): String? {
    val remaining = bytes.size - offset
    if (remaining % 4 != 0) return null
    val builder = StringBuilder(remaining / 4)
    var index = offset
    while (index < bytes.size) {
        val codePoint = if (littleEndian) {
            (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                ((bytes[index + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[index].toInt() and 0xFF) shl 24) or
                ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                (bytes[index + 3].toInt() and 0xFF)
        }
        if (codePoint < 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        builder.appendCodePoint(codePoint)
        index += 4
    }
    return builder.toString()
}

private const val DETECTION_BYTES = 128 * 1024
private const val TRUSTED_DETECTION_BYTES = 16
private val FALLBACK_CHARSETS = listOf("GB18030", "Big5", "Shift_JIS", "EUC-KR", "windows-1252")
private val EAST_ASIAN_CHARSETS = setOf("GB18030", "GBK", "GB2312", "BIG5", "SHIFT_JIS", "EUC-JP", "EUC-KR")
