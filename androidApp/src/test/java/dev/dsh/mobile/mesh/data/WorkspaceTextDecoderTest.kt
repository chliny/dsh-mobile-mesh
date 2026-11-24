package dev.dsh.mobile.mesh.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceTextDecoderTest {
    @Test
    fun `valid utf8 remains unchanged`() {
        val content = "你好，世界"
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `legacy simplified chinese bytes are detected and decoded`() {
        val content = "这是一个旧编码文件，支持查看。"
        val encoded = content.toByteArray(Charset.forName("GB18030"))
        assertEquals(content, decodeWorkspaceText(encoded))
    }

    @Test
    fun `short simplified chinese is not mistaken for another legacy charset`() {
        val content = "中文测试"
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun `traditional chinese big5 content is detected and decoded`() {
        val content = "這是一個舊編碼檔案，支援檢視。天氣很好。"
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charset.forName("Big5"))))
        val short = "中文測試"
        assertEquals(short, decodeWorkspaceText(short.toByteArray(Charset.forName("Big5"))))
    }

    @Test
    fun `ascii heavy html with a few chinese characters keeps the gbk text`() {
        val content = "<html><meta charset=\"gbk\"><body>中文内容</body></html>"
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun `utf16 files are decoded from their byte order mark`() {
        val content = "这是一个 UTF-16 文件。"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + content.toByteArray(Charset.forName("UTF-16LE"))
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + content.toByteArray(Charset.forName("UTF-16BE"))
        assertEquals(content, decodeWorkspaceText(littleEndian))
        assertEquals(content, decodeWorkspaceText(bigEndian))
    }

    @Test
    fun `utf16 without a byte order mark is still recognised from its NUL pattern`() {
        val content = "A UTF-16 file without a BOM."
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charset.forName("UTF-16LE"))))
    }

    @Test
    fun `utf8 byte order mark is stripped instead of shown as a glyph`() {
        val content = "中文 UTF-8 BOM"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(StandardCharsets.UTF_8)
        assertEquals(content, decodeWorkspaceText(bytes))
    }

    @Test
    fun `japanese and korean legacy text use the detected east asian charset`() {
        val japanese = "これは日本語のファイルです。文字コードを確認します。"
        assertEquals(japanese, decodeWorkspaceText(japanese.toByteArray(Charset.forName("Shift_JIS"))))
        val korean = "이것은 한국어 파일입니다. 인코딩을 확인합니다."
        assertEquals(korean, decodeWorkspaceText(korean.toByteArray(Charset.forName("EUC-KR"))))
    }

    @Test
    fun `western legacy text keeps its accented characters`() {
        val content = "café déjà vu, naïve résumé."
        assertEquals(content, decodeWorkspaceText(content.toByteArray(Charset.forName("windows-1252"))))
    }
}
