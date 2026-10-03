import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const page = readFileSync(new URL('../../main/assets/terminal/index.html', import.meta.url), 'utf8');

test('terminal leaves Backspace and IME composition reconciliation to xterm', () => {
    assert.doesNotMatch(page, /attachCustomKeyEventHandler/);
    // xterm's native input handler is the single authority that translates Backspace to DEL;
    // composition helper can emit its deferred textarea diff without a competing app DEL.
    assert.match(page, /term\.onData\(data => AndroidTerminal\.input\(data\)\)/);
});
