package dev.dsh.mobile.mesh.data

import java.nio.charset.Charset
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
}
