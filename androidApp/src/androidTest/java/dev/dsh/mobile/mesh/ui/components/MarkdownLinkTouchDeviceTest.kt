package dev.dsh.mobile.mesh.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real Chromium link gesture must route without being reclassified as a message tap. */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkTouchDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappingRenderedLinkOpensUriInsteadOfMessageActions() {
        val destination = "https://example.test/docs"
        val openedUri = AtomicReference<String?>(null)
        val messageTapCount = AtomicInteger()

        compose.setContent {
            DshTheme {
                Box(Modifier.height(160.dp)) {
                    MarkdownText(
                        text = "# [Open documentation]($destination)",
                        onTap = { messageTapCount.incrementAndGet() },
                        onOpenLink = { openedUri.set(it) },
                    )
                }
            }
        }

        val webViewRef = AtomicReference<WebView?>(null)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.runOnIdle {
                webViewRef.set(findWebView(compose.activity.window.decorView))
            }
            webViewRef.get() != null
        }
        val webView = requireNotNull(webViewRef.get())
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnIdle { webView.width > 1 && webView.height > 1 && webView.progress == 100 }
        }
        data class LinkPoint(
            val x: Float,
            val y: Float,
            val viewportWidth: Float,
            val viewportHeight: Float,
            val insideAnchor: Boolean,
        )
        val linkPoint = AtomicReference<LinkPoint?>(null)
        try {
            compose.runOnIdle {
                // JavaScript is disabled in production. Enable it only long enough to query the real
                // rendered anchor bounds; the finally block restores the secure setting on every path.
                webView.settings.javaScriptEnabled = true
                webView.evaluateJavascript(
                    "(() => { const a = document.querySelector('a'); if (!a) return null; " +
                        "const r = a.getBoundingClientRect(); const e = document.elementFromPoint(r.left+r.width/2,r.top+r.height/2); " +
                        "return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,viewportWidth:innerWidth," +
                        "viewportHeight:innerHeight,insideAnchor:!!e&&!!e.closest('a')}); })()",
                ) { encoded ->
                    val decoded = runCatching {
                        Json.parseToJsonElement(encoded ?: "null").jsonPrimitive.content
                    }.getOrNull()
                    val point = decoded?.let { value ->
                        runCatching {
                            val json = Json.parseToJsonElement(value).jsonObject
                            LinkPoint(
                                x = json.getValue("x").jsonPrimitive.float,
                                y = json.getValue("y").jsonPrimitive.float,
                                viewportWidth = json.getValue("viewportWidth").jsonPrimitive.float,
                                viewportHeight = json.getValue("viewportHeight").jsonPrimitive.float,
                                insideAnchor = json.getValue("insideAnchor").jsonPrimitive.content.toBoolean(),
                            )
                        }.getOrNull()
                    }
                    if (point != null) linkPoint.set(point)
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) { linkPoint.get() != null }
        } finally {
            compose.runOnIdle { webView.settings.javaScriptEnabled = false }
        }
        val point = requireNotNull(linkPoint.get())
        assertEquals("The selected DOM point must be inside the rendered anchor", true, point.insideAnchor)
        val viewSize = AtomicReference<Pair<Int, Int>?>(null)
        val screenLocation = AtomicReference<Pair<Int, Int>?>(null)
        compose.runOnIdle {
            viewSize.set(webView.width to webView.height)
            val location = IntArray(2)
            webView.getLocationOnScreen(location)
            screenLocation.set(location[0] to location[1])
        }
        val (viewWidth, viewHeight) = requireNotNull(viewSize.get())
        val x = point.x * viewWidth / point.viewportWidth
        val y = point.y * viewHeight / point.viewportHeight
        val (screenX, screenY) = requireNotNull(screenLocation.get())
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            screenX + x,
            screenY + y,
            0,
        )
        val up = MotionEvent.obtain(
            downTime,
            downTime + 40L,
            MotionEvent.ACTION_UP,
            screenX + x,
            screenY + y,
            0,
        )
        InstrumentationRegistry.getInstrumentation().sendPointerSync(down)
        InstrumentationRegistry.getInstrumentation().sendPointerSync(up)
        down.recycle()
        up.recycle()

        compose.waitUntil(timeoutMillis = 10_000) { openedUri.get() != null || messageTapCount.get() > 0 }
        assertEquals(
            "The real WebView link click must reach the URI handler (tap=($x,$y), messageTaps=${messageTapCount.get()})",
            destination,
            openedUri.get(),
        )
        assertEquals("A link click must not reveal message actions; uri=${openedUri.get()}", 0, messageTapCount.get())
    }

    private fun findWebView(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWebView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
