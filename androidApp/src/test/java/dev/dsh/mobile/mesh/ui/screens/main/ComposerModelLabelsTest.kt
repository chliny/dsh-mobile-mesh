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

    @Test fun `model uses viewport remainder while preserving permission and send slots`() {
        val composer = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/Composer.kt").readText()
        val row = composer.substringAfter("// The model gets only the space left").substringBefore("// Send and stop occupy")
        assertTrue(row.contains("Box(Modifier.weight(1f))"))
        assertTrue(row.contains("ModelChip(models = models, onClick = onOpenModels)"))
        assertTrue(!row.contains("112.dp"))
        val chip = composer.substringAfter("private fun ModelChip(").substringBefore("private fun PermissionChip(")
        assertTrue(!chip.contains(".widthIn(max = 112.dp)"))
        assertTrue(chip.contains("modifier = Modifier.weight(1f, fill = false)"))
        // A single text layout puts effort after the model, so end-ellipsis consumes effort first.
        assertTrue(chip.contains("buildAnnotatedString"))
        assertTrue(chip.indexOf("append(label)") < chip.indexOf("append(effort)"))
        assertTrue(chip.contains("overflow = TextOverflow.Ellipsis"))
        assertEquals(1, Regex("\\bText\\(").findAll(chip).count())
    }
}
