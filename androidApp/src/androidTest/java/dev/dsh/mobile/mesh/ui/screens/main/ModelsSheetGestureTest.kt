package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalogModel
import dev.dsh.mobile.mesh.core.wire.dto.ModelProviderGroup
import dev.dsh.mobile.mesh.core.wire.dto.ModelSelection
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import dev.dsh.mobile.mesh.ui.SessionStoreEntryPoint
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelsSheetGestureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun downwardDragScrollsListBeforeDismissingSheetAtTop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val store = EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).sessionStore()
        val models = SessionModelsValue(
            current = ModelSelection(provider = "provider", model = "model-0"),
            routable = true,
            groups = listOf(ModelProviderGroup(
                id = "provider",
                name = "Provider",
                models = (0 until 30).map { ModelCatalogModel(id = "model-$it", name = "Model $it") },
            )),
        )
        var dismissed = false
        compose.setContent {
            var open by androidx.compose.runtime.remember { mutableStateOf(true) }
            DshTheme {
                if (open) ModelsSheet(models, store) {
                    dismissed = true
                    open = false
                }
            }
        }
        val list = compose.onNode(
            hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            useUnmergedTree = true,
        )
        fun scrollPosition(): Float = list.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

        compose.onNodeWithText("Model 0").assertIsDisplayed()
        assertEquals(0f, scrollPosition(), 0.01f)
        repeat(3) {
            list.performTouchInput {
                swipe(
                    start = Offset(centerX, height * 0.8f),
                    end = Offset(centerX, height * 0.2f),
                    durationMillis = 600,
                )
            }
        }
        val scrolledPosition = scrollPosition()
        assertTrue("The model list did not scroll away from the top", scrolledPosition > 0f)

        list.performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.4f),
                end = Offset(centerX, height * 0.6f),
                durationMillis = 600,
            )
        }
        // Check immediately, while the list is still away from the top.
        compose.runOnIdle { assertFalse("Dragging a scrolled list dismissed the sheet", dismissed) }
        val afterDownwardDrag = scrollPosition()
        assertTrue("The downward drag did not scroll the list toward the top", afterDownwardDrag < scrolledPosition)
        assertTrue("The list reached the top before the dismissal check", afterDownwardDrag > 0f)

        // Return to the top separately, then exercise a new downward user gesture.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        assertEquals("The list must be at the top before dragging again", 0f, scrollPosition(), 0.01f)
        compose.runOnIdle { assertFalse("Returning to the top dismissed the sheet", dismissed) }
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue("Dragging down at the top did not dismiss the sheet", dismissed) }
    }
}
