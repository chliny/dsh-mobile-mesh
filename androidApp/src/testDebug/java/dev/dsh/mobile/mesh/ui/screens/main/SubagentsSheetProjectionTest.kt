package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.SubagentCatalogProjectionEntry
import dev.dsh.mobile.mesh.core.wire.dto.SubagentListEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers the catalog as a user sees it when a Host supplies a projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubagentsSheetProjectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `host projection displays names without changing child navigation`() {
        val projection = WireJson.decodeFromString<List<SubagentCatalogProjectionEntry>>(
            """
            [
              {"id":"child-real-42","createdAt":1,"mode":"continuable","label":"Research task","name":"research-agent"},
              {"id":"child-old-17","createdAt":2,"mode":"one-shot","label":"Legacy task"},
              {"id":"child-name-only-9","createdAt":3,"mode":"continuable","name":"named-agent"},
              {"id":"child-id-only-8","createdAt":4,"mode":"one-shot"},
              {"id":"external-5","createdAt":5,"mode":"external","label":"External task"}
            ]
            """.trimIndent(),
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            SubagentsSheet(
                entries = projection.map { SubagentListEntry.Projected(it) },
                sessions = emptyList(),
                onOpenSubagent = { opened.add(it) },
                onDismiss = {},
            )
        }

        compose.onNodeWithText("Research task").assertIsDisplayed()
        compose.onNodeWithText("research-agent").assertIsDisplayed()
        val labelBounds = compose.onNodeWithText("Research task", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nameBounds = compose.onNodeWithText("research-agent", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The name should appear below the task label", nameBounds.top > labelBounds.top)
        compose.onNodeWithText("Legacy task").assertIsDisplayed()
        compose.onNodeWithText("named-agent").assertIsDisplayed()
        compose.onNodeWithText("child-id-only-8").assertIsDisplayed()
        compose.onNodeWithText("External task").assertIsDisplayed().assertIsNotEnabled()

        compose.onNodeWithText("Research task").performClick()
        compose.onNodeWithText("Legacy task").performClick()
        compose.runOnIdle { assertEquals(listOf("child-real-42", "child-old-17"), opened) }
    }
}
