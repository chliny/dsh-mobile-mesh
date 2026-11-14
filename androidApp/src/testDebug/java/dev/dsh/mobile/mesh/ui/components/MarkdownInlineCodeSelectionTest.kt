package dev.dsh.mobile.mesh.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * User-visible regression: selecting text that contains inline code must show the selection
 * highlight over the code chip, exactly like it does over the prose around it.
 *
 * The renderer used to paint the chip as a `SpanStyle` background, which Compose draws together
 * with the glyphs, i.e. above the selection highlight. The chip is now drawn behind the text, so
 * the highlight lands on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownInlineCodeSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionHighlightIsPaintedOverInlineCodeChip() {
        var chipColor: Color? = null
        compose.setContent {
            DshTheme {
                chipColor = DsTheme.colors.inlineCode
                SelectionContainer {
                    MarkdownText("run `codeChip` now")
                }
            }
        }
        compose.waitForIdle()

        val chip = chipColor ?: error("DshTheme did not expose the inline code color")
        val node = compose.onNodeWithText("codeChip", substring = true, useUnmergedTree = true)

        val before = node.captureNode().asImageBitmap().toPixelMap()
        val chipPixels = before.chipPixels(chip)
        assertTrue("The inline code chip must be painted before any selection", chipPixels.isNotEmpty())
        val chipPixel = chipPixels.closestToCenter()

        // API 27 keeps touch-mode selection off the platform Magnifier, which Robolectric cannot
        // back with a real surface. The text drawing path under test does not depend on the level.
        node.performTouchInput { longClick(chipPixel) }
        compose.waitForIdle()

        val after = node.captureNode().asImageBitmap().toPixelMap()
        assertNotEquals(
            "The selection highlight must be painted over the inline code chip",
            chip.toArgb(),
            after[chipPixel.x.toInt(), chipPixel.y.toInt()].toArgb(),
        )
    }

    /** Draws the node's window through a software canvas; Robolectric cannot PixelCopy a window. */
    private fun SemanticsNodeInteraction.captureNode(): Bitmap {
        val node = fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val bounds = node.boundsInWindow
        val bitmap = Bitmap.createBitmap(
            bounds.width.roundToInt(),
            bounds.height.roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.translate(-bounds.left, -bounds.top)
        view.draw(canvas)
        return bitmap
    }

    private fun PixelMap.chipPixels(chip: Color): List<Pair<Int, Int>> {
        val chipArgb = chip.toArgb()
        return buildList {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (this@chipPixels[x, y].toArgb() == chipArgb) add(x to y)
                }
            }
        }
    }

    /** A chip pixel in the middle of the chip, i.e. inside the code text rather than its edge. */
    private fun List<Pair<Int, Int>>.closestToCenter(): Offset {
        val centerX = (minOf { it.first } + maxOf { it.first }) / 2f
        val centerY = (minOf { it.second } + maxOf { it.second }) / 2f
        val closest = minBy { (x, y) -> abs(x - centerX) + abs(y - centerY) }
        return Offset(closest.first.toFloat(), closest.second.toFloat())
    }
}
