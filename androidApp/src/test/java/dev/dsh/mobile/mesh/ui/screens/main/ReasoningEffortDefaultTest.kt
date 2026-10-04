package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortDefaultTest {
    @Test fun `Default maps to unset adapter effort`() {
        assertNull(reasoningEffortSelection(DEFAULT_REASONING_EFFORT_KEY))
        assertEquals("high", reasoningEffortSelection("high"))
    }

    @Test fun `Default is selected for unset effort and remains scrollable on narrow screens`() {
        val sheet = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/SheetModels.kt").readText()
        assertTrue(sheet.contains("DsSegment(DEFAULT_REASONING_EFFORT_KEY, stringResource(R.string.presets_default))"))
        assertTrue(sheet.contains("selectedKey = selectedEffort ?: DEFAULT_REASONING_EFFORT_KEY"))
        assertTrue(sheet.contains("BoxWithConstraints(Modifier.fillMaxWidth())"))
        assertTrue(sheet.contains("Modifier.horizontalScroll(rememberScrollState())"))
        assertTrue(sheet.contains("val trackWidth = maxOf(maxWidth, 84.dp * segments.size)"))
        assertTrue(sheet.contains("modifier = Modifier.width(trackWidth)"))
        assertTrue(sheet.contains("stretch = true"))
    }
}
