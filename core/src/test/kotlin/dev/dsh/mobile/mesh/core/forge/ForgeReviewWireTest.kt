package dev.dsh.mobile.mesh.core.forge

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeReviewWireTest {

    @Test
    fun `pending comment names the captured line range`() {
        val write = forgeReviewAddPendingWrite(
            headSha = "head",
            comment = ForgeLineComment("src/App.kt", "R", 3, 5, "please fix"),
        )
        assertEquals("add-pending", write["kind"]!!.jsonPrimitive.content)
        val comment = write["comment"]!!.jsonObject
        assertEquals("src/App.kt", comment["path"]!!.jsonPrimitive.content)
        assertEquals("R", comment["side"]!!.jsonPrimitive.content)
        assertEquals(3, comment["startLine"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, comment["endLine"]!!.jsonPrimitive.content.toInt())
        assertEquals("please fix", comment["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `completed review carries every comment and decision`() {
        val request = forgeSubmitReviewRequest(
            headSha = "head",
            event = "request-changes",
            body = "Needs work",
            comments = listOf(
                ForgeLineComment("a.kt", "L", 1, 1, "old"),
                ForgeLineComment("b.kt", "R", 2, 3, "new"),
            ),
        )
        assertEquals("review", request["kind"]!!.jsonPrimitive.content)
        assertEquals("request-changes", request["event"]!!.jsonPrimitive.content)
        assertEquals(2, request["comments"]!!.jsonArray.size)
        assertEquals("b.kt", request["comments"]!!.jsonArray[1].jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reviewer array keeps account kind`() {
        val array = forgeReviewersJson(
            listOf(
                dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerRef("user", "alice"),
                dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewerRef("team", "org/reviewers"),
            ),
        )
        assertEquals(2, array.size)
        assertEquals("team", array[1].jsonObject["kind"]!!.jsonPrimitive.content)
        assertTrue(array[1].jsonObject["login"]!!.jsonPrimitive.content.contains("/"))
    }
}
