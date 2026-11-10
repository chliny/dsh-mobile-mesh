package dev.dsh.mobile.mesh.ui.screens.main

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalog
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the Host-published model badge as a reader sees it in the model picker: the label appears
 * beside its model, localization uses the device locale with the Host's English fallback, and the
 * model stays selectable. The catalog JSON is the wire shape a real Host returns, so the DTO's
 * string-or-translations decoding is exercised too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ModelBadgeSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `host badges are shown beside their models and the model still selects`() {
        val models = modelsOf(HOST_CATALOG_WITH_BADGES)
        val store = Mockito.mock(SessionStore::class.java)
        setModelsContent(models, store, Locale.SIMPLIFIED_CHINESE)

        compose.onNodeWithText("DeepSeek Chat").assertIsDisplayed()
        // A literal badge is shown as published.
        compose.onNodeWithText("New").assertIsDisplayed()
        // The device locale (zh-CN) wins over the Host's English fallback.
        compose.onNodeWithText("预览").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertDoesNotExist()
        // A translation map without this locale falls back to the Host's required English text.
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        // A tone this build does not know keeps the label visible at the neutral emphasis.
        compose.onNodeWithText("Beta").assertIsDisplayed()
        // A model the Host published without a badge is unaffected.
        compose.onNodeWithText("DeepSeek Legacy").assertIsDisplayed()

        compose.onNodeWithText("DeepSeek Preview").performClick()
        val (provider, model) = awaitSelection(store)
        assertEquals("deepseek", provider)
        assertEquals("deepseek-preview", model)
    }

    @Test
    fun `catalog from an older host without badges renders and selects as before`() {
        val models = modelsOf(OLDER_HOST_CATALOG)
        val store = Mockito.mock(SessionStore::class.java)
        setModelsContent(models, store, Locale.ENGLISH)

        compose.onNodeWithText("DeepSeek Chat").assertIsDisplayed()
        compose.onNodeWithText("DeepSeek Reasoner").assertIsDisplayed()

        compose.onNodeWithText("DeepSeek Chat").performClick()
        val (provider, model) = awaitSelection(store)
        assertEquals("deepseek", provider)
        assertEquals("deepseek-chat", model)
    }

    /**
     * Render the sheet under an explicit configuration locale, the same source production reads.
     * Fixing it in the test keeps badge localization deterministic while the sheet still resolves
     * the language from the Android configuration rather than from a test-only parameter.
     */
    private fun setModelsContent(models: SessionModelsValue, store: SessionStore, locale: Locale) {
        val base = RuntimeEnvironment.getApplication().resources.configuration
        val configuration = Configuration(base).apply { setLocale(locale) }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DshTheme { ModelsSheet(models = models, store = store, onDismiss = {}) }
            }
        }
    }

    /** Wait for the click's launched coroutine to hand the selection to the store. */
    private fun awaitSelection(store: SessionStore): Pair<Any?, Any?> {
        compose.waitUntil(2_000) {
            Mockito.mockingDetails(store).invocations.any { it.method.name == "selectModel" }
        }
        val invocation = Mockito.mockingDetails(store).invocations.single { it.method.name == "selectModel" }
        return invocation.arguments[0] to invocation.arguments[1]
    }

    /** The sheet's join over the Host catalog, mirroring the store's catalog/selection split. */
    private fun modelsOf(json: String): SessionModelsValue {
        val catalog = WireJson.decodeFromString(ModelCatalog.serializer(), json)
        return SessionModelsValue(
            current = catalog.default,
            routable = catalog.default.provider in catalog.routableProviders,
            groups = catalog.groups,
            failures = catalog.failures,
        )
    }

    private companion object {
        /** Real `session/modelCatalog` shape: literal badge, translations, English-only map, unknown tone. */
        const val HOST_CATALOG_WITH_BADGES = """
            {
              "default": {"provider": "deepseek", "model": "deepseek-chat"},
              "routableProviders": ["deepseek"],
              "groups": [{
                "id": "deepseek",
                "name": "DeepSeek",
                "models": [
                  {"id": "deepseek-chat", "name": "DeepSeek Chat",
                   "badge": {"text": "New", "tone": "success"}},
                  {"id": "deepseek-preview", "name": "DeepSeek Preview",
                   "description": "Preview build",
                   "badge": {"text": {"en": "Preview", "zh": "预览"}, "tone": "info"}},
                  {"id": "deepseek-alpha", "name": "DeepSeek Alpha",
                   "badge": {"text": {"en": "Alpha", "ja": "アルファ"}, "tone": "warning"}},
                  {"id": "deepseek-beta", "name": "DeepSeek Beta",
                   "badge": {"text": "Beta", "tone": "sparkle"}},
                  {"id": "deepseek-legacy", "name": "DeepSeek Legacy"}
                ]
              }],
              "failures": []
            }
        """

        /** Real 0.2.1-era `session/modelCatalog` shape: no badge at all. */
        const val OLDER_HOST_CATALOG = """
            {
              "default": {"provider": "deepseek", "model": "deepseek-reasoner"},
              "routableProviders": ["deepseek"],
              "groups": [{
                "id": "deepseek",
                "name": "DeepSeek",
                "models": [
                  {"id": "deepseek-chat", "name": "DeepSeek Chat", "description": "Fast general model",
                   "reasoning": {"efforts": [{"id": "low", "name": "Low"}, {"id": "high", "name": "High"}],
                                 "defaultEffort": "high"}},
                  {"id": "deepseek-reasoner", "name": "DeepSeek Reasoner"}
                ]
              }],
              "failures": []
            }
        """

    }
}
