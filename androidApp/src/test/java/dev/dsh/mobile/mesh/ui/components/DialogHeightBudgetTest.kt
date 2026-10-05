package dev.dsh.mobile.mesh.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A dialog plate used to be capped at a flat 680.dp — taller than the usable height of the phones
 * this app runs on, so the cap bounded nothing. A body that grew with its content (the queued-turn
 * editor is the real case) ran past the bottom of the screen and carried its OK/Cancel row with it.
 */
class DialogHeightBudgetTest {
    @Test
    fun `the body plus the plate chrome always fits inside the plate height`() {
        // Heights a phone actually offers the dialog, from a tall tablet to a phone with the
        // keyboard up: every one of them must leave a body that still fits.
        listOf(240.dp, 280.dp, 360.dp, 420.dp, 495.dp, 600.dp, 680.dp, 900.dp).forEach { plate ->
            val body = dialogBodyMaxHeight(plate)
            assertTrue(
                "body $body does not fit inside plate $plate",
                body + DIALOG_CHROME_TEST_ALLOWANCE <= plate || body == MIN_BODY_TEST_FLOOR,
            )
        }
    }

    @Test
    fun `a body is never squeezed below a usable editor height`() {
        // A very short plate must not collapse the body to nothing; the caller scrolls it instead.
        assertTrue(dialogBodyMaxHeight(1.dp) >= 96.dp)
        assertTrue(dialogBodyMaxHeight(240.dp) >= 96.dp)
    }

    @Test
    fun `the body grows with the plate instead of always taking the ceiling`() {
        assertTrue(dialogBodyMaxHeight(680.dp) > dialogBodyMaxHeight(280.dp))
        assertTrue(dialogBodyMaxHeight(280.dp) > dialogBodyMaxHeight(240.dp))
    }

    @Test
    fun `the plate cap follows the viewport instead of a constant taller than the screen`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/Overlays.kt").readText()
        val dialog = source.substringAfter("fun DsDialog(").substringBefore("private fun dialogMaxHeight()")
        assertTrue("DsDialog must cap on the measured height", dialog.contains("heightIn(max = maxHeight)"))
        assertTrue("the 680.dp cap is taller than a phone screen", !dialog.contains("heightIn(max = 680.dp)"))

        val measure = source.substringAfter("private fun dialogMaxHeight()").substringBefore("@Composable\nfun DsDialogBodyMaxHeight")
        assertTrue("the cap must account for the keyboard", measure.contains("WindowInsets.ime"))
        assertTrue("the cap must account for the status bar", measure.contains("WindowInsets.systemBars"))
        assertTrue("the cap must be measured against the screen", measure.contains("screenHeightDp"))
    }

    private companion object {
        /** Mirrors `DIALOG_CHROME_HEIGHT`; the real constant is private to the component file. */
        val DIALOG_CHROME_TEST_ALLOWANCE = 136.dp
        val MIN_BODY_TEST_FLOOR = 96.dp
    }
}