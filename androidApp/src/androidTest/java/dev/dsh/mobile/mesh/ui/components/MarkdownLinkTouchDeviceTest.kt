package dev.dsh.mobile.mesh.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real link gesture in rendered Markdown must reach the app's link router. */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkTouchDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingRenderedLinkOpensUri() {
        val destination = "https://example.test/docs"
        val openedUri = AtomicReference<String?>(null)
        compose.setContent {
            DshTheme {
                Box(Modifier.height(160.dp)) {
                    MarkdownText(
                        text = "# [Open documentation]($destination)",
                        onOpenLink = { openedUri.set(it) },
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Open documentation", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Open documentation", substring = true).performTouchInput { click() }
        compose.waitUntil(timeoutMillis = 10_000) { openedUri.get() != null }
        assertEquals(destination, openedUri.get())
    }
}
