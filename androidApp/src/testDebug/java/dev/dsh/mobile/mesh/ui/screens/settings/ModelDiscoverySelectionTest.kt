package dev.dsh.mobile.mesh.ui.screens.settings

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.dsh.mobile.mesh.core.wire.dto.CredentialInfo
import dev.dsh.mobile.mesh.core.wire.dto.LlmConfigurableProvider
import dev.dsh.mobile.mesh.core.wire.dto.LlmDiscoveredModel
import dev.dsh.mobile.mesh.core.wire.dto.SettingsDescribeValue
import dev.dsh.mobile.mesh.core.wire.dto.SettingsNamespaceView
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class ModelDiscoverySelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `discovery without catalog matches starts with no models selected`() {
        discoverModels()

        val useSelected = compose.onNodeWithText("Use selected models")
        useSelected.assertIsNotEnabled()
        compose.onNodeWithText("Select all models").assertIsOff().performScrollTo().performClick()
        useSelected.assertIsEnabled()
        compose.onNodeWithText("Deselect all models").assertIsOn().performClick()
        useSelected.assertIsNotEnabled()
    }

    @Test fun `applying discovered models without changing selection keeps only existing matches`() {
        discoverModels(catalog = """[{"id":"first","name":"Existing model"}]""")

        compose.onNodeWithText("Use selected models").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("Existing model").assertExists()
        compose.onNodeWithText("second").assertDoesNotExist()
    }

    @Test fun `applying discovery follows the returned order for old and new models`() {
        val (viewModel, state) = openProvider(catalog = """
            [{"id":"second","name":"Priority model"},{"id":"first","name":"Fallback model"},{"id":"retired"}]
        """.trimIndent())
        discoverModels(state, LlmDiscoveredModel("new"), LlmDiscoveredModel("first"), LlmDiscoveredModel("second"))
        compose.onNodeWithText("Select all models").performScrollTo().performClick()
        compose.onNodeWithText("Use selected models").performClick()
        compose.onNodeWithText("Save").performClick()

        val row = state.value.providers.single()
        Mockito.verify(viewModel).saveProvider(requireNotNull(row.namespace), row, "example", mapOf(
            "models" to Json.parseToJsonElement("""
                [{"id":"new"},{"id":"first","name":"Fallback model"},{"id":"second","name":"Priority model"}]
            """.trimIndent()),
        ), null)
    }

    @Test fun `a live provider without an address can save a new one`() {
        val (viewModel, state) = openProvider(endpoint = null)
        compose.onNodeWithContentDescription("Edit: API address").performClick()
        compose.onNodeWithText("API address").performTextInput("https://models.example/v1")
        compose.onNodeWithText("Save").performClick()

        val row = state.value.providers.single()
        Mockito.verify(viewModel).saveProvider(requireNotNull(row.namespace), row, "example", mapOf(
            "baseURL" to JsonPrimitive("https://models.example/v1"),
            "models" to null,
        ), null)
    }

    @Test fun `long press and drag changes the order saved to the provider`() {
        val (viewModel, state) = openProvider(catalog = """
            [{"id":"first","name":"First model"},{"id":"second","name":"Second model"}]
        """.trimIndent())
        val first = compose.onNodeWithText("First model")
        val second = compose.onNodeWithText("Second model").performScrollTo()
        val distance = first.fetchSemanticsNode().positionInRoot.y - second.fetchSemanticsNode().positionInRoot.y
        second.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        second.performTouchInput {
            advanceEventTime(800)
            repeat(8) { moveBy(Offset(0f, (distance - 8f) / 8f)) }
            up()
        }
        compose.onNodeWithText("Save").performClick()

        val row = state.value.providers.single()
        Mockito.verify(viewModel).saveProvider(requireNotNull(row.namespace), row, "example", mapOf(
            "models" to Json.parseToJsonElement("""
                [{"id":"second","name":"Second model"},{"id":"first","name":"First model"}]
            """.trimIndent()),
        ), null)
    }

    private fun discoverModels(catalog: String = "[]") {
        val (_, state) = openProvider(catalog)
        discoverModels(state, LlmDiscoveredModel("first"), LlmDiscoveredModel("second"))
    }

    private fun discoverModels(state: MutableStateFlow<HostModelsState>, vararg results: LlmDiscoveredModel) {
        compose.onNodeWithText("Discover models").performClick()
        compose.runOnIdle { state.value = state.value.copy(discovered = results.toList()) }
    }

    private fun openProvider(catalog: String = "[]", endpoint: String? = "https://models.example/v1"):
        Pair<HostModelsViewModel, MutableStateFlow<HostModelsState>> {
        val provider = LlmConfigurableProvider("example", "Example", PI_AI, listOf("providers", "example"))
        val baseUrl = endpoint?.let { "\"baseURL\":${JsonPrimitive(it)}," }.orEmpty()
        val parsed = Json.parseToJsonElement("""{"providers":{"example":{${baseUrl}"models":$catalog}}}""")
        val section = SettingsNamespaceView(PI_AI, Json.parseToJsonElement("{}"), parsed, applies = "live", revision = 1)
        val settings = SettingsDescribeValue(writable = true, hasDocument = true, namespaces = listOf(section))
        val state = MutableStateFlow(HostModelsState(settings = settings,
            providers = listOf(HostModelProvider(provider, section, live = true, credential = CredentialInfo(writable = true)))))
        val viewModel = Mockito.mock(HostModelsViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(state)
        compose.setContent { DshTheme { HostModelsSettings(onClose = {}, viewModel = viewModel) } }
        compose.onNodeWithText("Edit").performClick()
        return viewModel to state
    }
}
