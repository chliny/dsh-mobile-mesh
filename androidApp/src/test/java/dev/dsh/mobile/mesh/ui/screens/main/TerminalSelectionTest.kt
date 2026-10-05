package dev.dsh.mobile.mesh.ui.screens.main

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression guard: xterm paints its glyphs onto a canvas inside a `user-select: none` host, so
 * the WebView callout has no text to select and terminal output could never be selected or copied.
 * A long press now starts a row selection in [touch-scroll.js], and the toolbar Copy button hands
 * that selection to the Android clipboard.
 */
class TerminalSelectionTest {
    private val gestures = File("src/main/assets/terminal/touch-scroll.js")

    /** Drives the real gesture handler with a fake DOM, so the selection invariant is exercised. */
    private fun gesturesOnDevice(): kotlinx.serialization.json.JsonObject {
        val script = File.createTempFile("terminal-selection", ".js")
        script.writeText(HARNESS)
        try {
            val process = ProcessBuilder("node", script.absolutePath, gestures.absolutePath)
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

    @Test fun `long press selects the touched row and dragging extends it`() {
        assumeTrue("node is required to drive the terminal gesture script", nodeAvailable())
        val result = gesturesOnDevice()
        val selection = result.getValue("longPress").jsonObject.getValue("selected").jsonArray
        // Scrolled back five rows: xterm selects absolute buffer lines, so viewport row 2 is line 7.
        assertEquals(listOf(7, 7), selection.first().jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(7, 9), selection.last().jsonArray.map { it.jsonPrimitive.int })
        // The drag owns the gesture, so WebView must not scroll the page under the selection.
        assertEquals(1, result.getValue("longPress").jsonObject.getValue("prevented").jsonPrimitive.int)
    }

    @Test fun `a swipe still scrolls scrollback instead of selecting`() {
        assumeTrue("node is required to drive the terminal gesture script", nodeAvailable())
        val result = gesturesOnDevice()
        val swipe = result.getValue("swipe").jsonObject
        // The gesture that scrolled is a regression guard of its own: no line may be selected.
        assertEquals(2, swipe.getValue("scrolled").jsonPrimitive.int)
        assertEquals(0, swipe.getValue("selected").jsonArray.size)
    }

    @Test fun `a tap without a long press drops a stale selection`() {
        assumeTrue("node is required to drive the terminal gesture script", nodeAvailable())
        val result = gesturesOnDevice()
        // Copy reads the live selection, so an old highlight must not survive a plain tap.
        assertEquals(1, result.getValue("staleAfterSelect").jsonPrimitive.int)
        assertEquals(0, result.getValue("staleAfterTap").jsonPrimitive.int)
    }

    @Test fun `the selection bridge reaches the Android clipboard`() {
        val page = File("src/main/assets/terminal/index.html").readText()
        assertTrue("terminalCopy() must read the live xterm selection", page.contains("term.getSelection()"))
        assertTrue("the page must hand the selection to the native bridge", page.contains("AndroidTerminal.copy(text)"))
        val screen = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/TerminalScreen.kt").readText()
        assertTrue("the bridge must copy to the system clipboard", screen.contains("@JavascriptInterface fun copy(text: String)"))
        assertTrue(screen.contains("ClipboardManager"))
        assertTrue("a visible Copy control must invoke it", screen.contains("evaluateJavascript(\"terminalCopy()\", null)"))
    }

    private fun nodeAvailable(): Boolean =
        try {
            ProcessBuilder("node", "--version").redirectErrorStream(true).start()
                .also { it.inputStream.readBytes(); it.waitFor(20, TimeUnit.SECONDS) }
                .exitValue() == 0
        } catch (_: Exception) {
            false
        }

    private companion object {
        /**
         * A minimal stand-in for `.xterm-screen`: the handler only needs `closest`,
         * `getBoundingClientRect` and the touch listeners, so the real script runs unmodified.
         */
        val HARNESS = """
            const fs = require('fs');
            const install = new Function(fs.readFileSync(process.argv[2], 'utf8') + '\nreturn installTerminalTouchScrolling;')();

            // A manual clock, so the long press threshold is driven deterministically.
            let now = 0, nextId = 0;
            const timers = new Map();
            global.setTimeout = (fn, delay) => { const id = ++nextId; timers.set(id, {fn, at: now + delay}); return id; };
            global.clearTimeout = id => timers.delete(id);
            const advance = ms => {
              now += ms;
              for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn(); }
            };

            const screen = {closest: selector => (selector === '.xterm-screen' ? {} : null)};
            function harness(viewportY) {
              const listeners = {};
              const container = {
                getBoundingClientRect: () => ({top: 0, height: 200}),
                addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
              };
              const term = {
                rows: 10,
                buffer: {active: {type: 'normal', baseY: viewportY, viewportY: viewportY, length: 500}},
                selected: [],
                scrolled: 0,
                prevented: 0,
                hasSelection() { return this.selected.length > 0; },
                clearSelection() { this.selected.length = 0; },
                selectLines(start, end) { this.selected.push([start, end]); },
                scrollLines(lines) { this.scrolled += lines; },
              };
              install(container, term);
              const fire = type => (x, y) => listeners[type].forEach(fn =>
                fn({
                  target: screen,
                  cancelable: true,
                  preventDefault() { term.prevented++; },
                  touches: [{clientX: x, clientY: y}],
                }));
              return {term, advance, start: fire('touchstart'), move: fire('touchmove'), end: fire('touchend')};
            }

            // Long press, then drag down two rows.
            const longPress = harness(5);
            longPress.start(40, 45);
            longPress.advance(600);
            longPress.move(40, 95);
            longPress.end();

            // A one-finger swipe must keep scrolling rather than selecting.
            const swipe = harness(10);
            swipe.start(40, 100);
            swipe.move(40, 60);
            swipe.advance(600);
            swipe.end();

            // A selection must survive its own touchend, then a plain tap must drop it.
            const stale = harness(0);
            stale.start(40, 25);
            stale.advance(600);
            stale.end();
            const staleAfterSelect = stale.term.selected.length;
            stale.start(40, 150);
            stale.end();

            console.log(JSON.stringify({
              longPress: {selected: longPress.term.selected, prevented: longPress.term.prevented},
              swipe: {scrolled: swipe.term.scrolled, selected: swipe.term.selected},
              staleAfterSelect: staleAfterSelect,
              staleAfterTap: stale.term.selected.length,
            }));
        """.trimIndent()
    }
}
