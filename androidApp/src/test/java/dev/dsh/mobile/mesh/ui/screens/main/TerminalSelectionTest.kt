package dev.dsh.mobile.mesh.ui.screens.main

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Regression guards for Android's native selection menu over xterm's accessibility text tree. */
class TerminalSelectionTest {
    private val assets = File("src/main/assets/terminal")

    private fun runGestureHarness(): kotlinx.serialization.json.JsonObject {
        val script = File.createTempFile("terminal-native-selection", ".js")
        script.writeText(HARNESS)
        try {
            val process = ProcessBuilder("node", script.absolutePath, assets.resolve("touch-scroll.js").absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("node harness timed out:\n$output", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals("terminal gesture harness failed:\n$output", 0, process.exitValue())
            return Json.parseToJsonElement(output.trim()).jsonObject
        } finally {
            script.delete()
        }
    }

    @Test fun `native long press uses selectable xterm DOM and no legacy copy controls remain`() {
        val page = assets.resolve("index.html").readText()
        val gestures = assets.resolve("touch-scroll.js").readText()
        val screen = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/TerminalScreen.kt").readText()
        assertTrue(page.contains("screenReaderMode:true"))
        assertTrue(page.contains(".xterm-accessibility:not(.debug){pointer-events:auto}"))
        assertTrue(page.contains("-webkit-user-select:text;user-select:text"))
        assertTrue(page.contains("addEventListener('contextmenu', event => event.stopPropagation())"))
        assertFalse("do not expose the old JS copy endpoint", page.contains("terminalCopy"))
        assertFalse(page.contains("AndroidTerminal.copy"))
        assertFalse("the old synthetic line selection must be gone", gestures.contains("term.selectLines("))
        assertFalse(gestures.contains("term.clearSelection()"))
        assertFalse("remove the old native clipboard bridge", screen.contains("@JavascriptInterface fun copy("))
        assertFalse("the toolbar must not show a Copy button", screen.contains("evaluateJavascript(\"terminalCopy()\""))
    }

    @Test fun `a native text range drag is left to WebView`() {
        assumeTrue("node is required to drive the terminal gesture script", nodeAvailable())
        val result = runGestureHarness()
        val selection = result.getValue("nativeSelection")
        assertEquals(0, selection.jsonObject.getValue("scrolled").jsonPrimitive.int)
        assertEquals(0, selection.jsonObject.getValue("prevented").jsonPrimitive.int)
    }

    @Test fun `one finger swipe still scrolls terminal scrollback`() {
        assumeTrue("node is required to drive the terminal gesture script", nodeAvailable())
        val result = runGestureHarness()
        val swipe = result.getValue("swipe")
        assertEquals(2, swipe.jsonObject.getValue("scrolled").jsonPrimitive.int)
        assertEquals(1, swipe.jsonObject.getValue("prevented").jsonPrimitive.int)
    }

    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start()
            .also { it.inputStream.readBytes(); it.waitFor(20, TimeUnit.SECONDS) }
            .exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private companion object {
        val HARNESS = """
            const fs = require('fs');
            const install = new Function(fs.readFileSync(process.argv[2], 'utf8') + '\nreturn installTerminalTouchScrolling;')();
            let selected = false;
            global.window = {getSelection: () => ({isCollapsed: !selected})};
            const makeTarget = cls => ({closest: selector => selector.split(', ').includes(cls) ? {} : null});
            function harness(target) {
              const listeners = {};
              const container = {
                getBoundingClientRect: () => ({top: 0, height: 200}),
                addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
              };
              const term = {
                rows: 10,
                buffer: {active: {type: 'normal', baseY: 10, viewportY: 10, length: 100}},
                scrolled: 0,
                prevented: 0,
                scrollLines(lines) { this.scrolled += lines; },
              };
              install(container, term);
              const fire = type => (x, y) => listeners[type].forEach(fn => fn({
                target, cancelable: true, preventDefault() { term.prevented++; },
                touches: [{clientX: x, clientY: y}],
              }));
              return {term, start: fire('touchstart'), move: fire('touchmove'), end: fire('touchend')};
            }
            const swipe = harness(makeTarget('.xterm-screen'));
            swipe.start(40, 100); swipe.move(40, 60); swipe.end();
            selected = true;
            const nativeSelection = harness(makeTarget('.xterm-accessibility-tree'));
            nativeSelection.start(40, 100); nativeSelection.move(40, 60); nativeSelection.end();
            console.log(JSON.stringify({
              swipe: {scrolled: swipe.term.scrolled, prevented: swipe.term.prevented},
              nativeSelection: {scrolled: nativeSelection.term.scrolled, prevented: nativeSelection.term.prevented},
            }));
        """.trimIndent()
    }
}
