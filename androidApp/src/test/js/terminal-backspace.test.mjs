import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

// Evaluate the actual handler embedded in the WebView page, with a native textarea edit
// simulated ONLY when the xterm custom handler leaves the keydown unprevented.
const page = readFileSync(new URL('../../main/assets/terminal/index.html', import.meta.url), 'utf8');
const handlerSource = page.match(/term\.attachCustomKeyEventHandler\(event => \{([\s\S]*?)\n\}\);/)?.[1];
assert.ok(handlerSource, 'WebView must register a custom keydown handler');

function fixture() {
    const sent = [];
    const handler = new Function('term', `return event => {${handlerSource}}`)({ input: data => sent.push(data) });
    return { handler, sent };
}

test('Backspace sends one DEL and allows the IME to delete its pending character', () => {
    const { handler, sent } = fixture();
    let pending = 'hello';
    const handled = handler({ type: 'keydown', keyCode: 8, isComposing: false,
        ctrlKey: false, altKey: false, metaKey: false });
    if (handled === false) pending = pending.slice(0, -1); // browser native default action
    assert.equal(pending, 'hell');
    assert.deepEqual(sent, ['\x7f']);
});

test('229/composition and modified Backspace remain with xterm without a duplicate DEL', () => {
    const { handler, sent } = fixture();
    for (const event of [
        { type: 'keydown', keyCode: 229 },
        { type: 'keydown', keyCode: 8, isComposing: true },
        { type: 'keydown', keyCode: 8, ctrlKey: true },
        { type: 'keydown', keyCode: 8, altKey: true },
        { type: 'keydown', keyCode: 8, metaKey: true },
        { type: 'keyup', keyCode: 8 },
    ]) {
        assert.equal(handler({ isComposing: false, ctrlKey: false, altKey: false, metaKey: false, ...event }), true);
    }
    assert.deepEqual(sent, []);
});
