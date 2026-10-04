package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalogModel
import dev.dsh.mobile.mesh.core.wire.dto.ModelProviderGroup
import dev.dsh.mobile.mesh.core.wire.dto.ModelReasoning
import dev.dsh.mobile.mesh.core.wire.dto.ModelReasoningEffort
import dev.dsh.mobile.mesh.core.wire.dto.ModelSelection
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerModelLabelsTest {
    private fun models(effort: String?, reasoning: ModelReasoning? = ModelReasoning(
        efforts = listOf(ModelReasoningEffort("high", "High")),
    )) = SessionModelsValue(
        current = ModelSelection("provider", "model", effort),
        routable = true,
        groups = listOf(ModelProviderGroup("provider", "Provider", listOf(
            ModelCatalogModel("model", "Display Model", reasoning = reasoning),
        ))),
    )

    @Test fun `selected supported reasoning effort appears beside model`() {
        assertEquals("Display Model" to "High", composerModelLabels(models("high")))
    }

    @Test fun `unsupported or unspecified effort does not invent a label`() {
        assertNull(composerModelLabels(models(null)).second)
        assertNull(composerModelLabels(models("unknown")).second)
        assertNull(composerModelLabels(models("high", null)).second)
    }

    @Test fun `composer chip retains its width limit`() {
        val composer = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Composer.kt").readText()
        val chip = composer.substringAfter("private fun ModelChip(").substringBefore("private fun PermissionChip(")
        assertTrue(chip.contains(".widthIn(max = 112.dp)"))
        assertTrue(chip.contains("effortLabel?.let"))
        assertTrue(chip.contains("modifier = Modifier.weight(1f, fill = false)"))
    }
}
