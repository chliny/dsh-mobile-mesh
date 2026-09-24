package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.CommandDescriptor
import dev.dsh.mobile.mesh.core.wire.dto.SkillEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandCandidatesTest {
    @Test
    fun `candidate rows retain authoritative host command and skill data with host row winning collisions`() {
        val rows = slashCandidates(
            commands = listOf(CommandDescriptor("plan", "server plan", definitionId = "@deepseek-ai/dsh-plan-mode")),
            skills = listOf(SkillEntry(name = "review", description = "Review", modelInvocable = true)),
            nativeNames = setOf("model"),
        )
        assertEquals(listOf("model", "plan", "review"), rows.map { it.name })
        assertEquals("server plan", rows[1].description)
        assertEquals("/review ", rows[2].prefix)
        assertEquals(SlashCandidate.Kind.HOST_COMMAND, rows[1].kind)
    }

    @Test
    fun `empty slash query lists every available catalog and native candidate`() {
        val candidates = slashCandidates(
            commands = listOf(
                CommandDescriptor("compact", "Compact"),
                CommandDescriptor("plan", "Plan"),
            ),
            skills = listOf(SkillEntry("review", "Review", modelInvocable = true)),
            nativeNames = setOf("model", "permission", "file"),
        )
        assertEquals(
            listOf("compact", "file", "model", "permission", "plan", "review"),
            rankSlashCandidates(candidates, "").map { it.name },
        )
    }

    @Test
    fun `candidate rank favors prefixes then subsequence and searches descriptions`() {
        val rows = listOf(
            SlashCandidate("compact", "", "/compact", SlashCandidate.Kind.HOST_COMMAND),
            SlashCandidate("plan", "Enter plan mode", "/plan ", SlashCandidate.Kind.HOST_COMMAND),
            SlashCandidate("feedback", "", "/feedback ", SlashCandidate.Kind.HOST_COMMAND),
        )
        assertEquals(listOf("plan"), rankSlashCandidates(rows, "pl").map { it.name })
        assertEquals(listOf("plan"), rankSlashCandidates(rows, "mode").map { it.name })
        assertEquals(listOf("feedback"), rankSlashCandidates(rows, "fdb").map { it.name })
        assertEquals(listOf("compact", "feedback", "plan"), rankSlashCandidates(rows, "").map { it.name })
    }
}
