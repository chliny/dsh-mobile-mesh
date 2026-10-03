import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';

const code = readFileSync(new URL('../../main/assets/terminal/touch-scroll.js', import.meta.url), 'utf8');
const page = readFileSync(new URL('../../main/assets/terminal/index.html', import.meta.url), 'utf8');
const install = runInNewContext(`${code}\ninstallTerminalTouchScrolling`);

test('terminal page loads scrolling support and attaches it to the rendered screen', () => {
    assert.match(page, /<script src="touch-scroll\.js"><\/script>/);
    assert.match(page, /installTerminalTouchScrolling\(term\.element\.querySelector\('\.xterm-screen'\), term\)/);
});

function fixture({ baseY = 100, type = 'normal' } = {}) {
    const listeners = new Map();
    const screen = {
        getBoundingClientRect: () => ({ height: 500 }),
        addEventListener: (name, fn, options) => listeners.set(name, { fn, options }),
    };
    const buffer = { type, baseY, viewportY: baseY };
    const sent = [];
    const term = {
        rows: 25,
        buffer: { active: buffer },
        scrollLines: lines => {
            assert.equal(Number.isInteger(lines), true);
            buffer.viewportY = Math.max(0, Math.min(baseY, buffer.viewportY + lines));
            sent.push(lines);
        },
    };
    install(screen, term);
    const touch = (name, y, x = 100, count = 1, target = '.xterm-screen') => {
        let prevented = false;
        listeners.get(name).fn({
            touches: Array.from({ length: count }, () => ({ clientY: y, clientX: x })),
            target: { closest: selector => selector === target ? {} : null },
            cancelable: true,
            preventDefault: () => { prevented = true; },
        });
        return prevented;
    };
    return { touch, sent, buffer, listeners };
}

test('vertical finger swipes move scrollback by rows in both directions and clamp at ends', () => {
    const { touch, sent, buffer, listeners } = fixture();
    assert.equal(listeners.get('touchmove').options.passive, false);
    touch('touchstart', 300);
    assert.equal(touch('touchmove', 230), true); // finger up: older lines
    assert.equal(buffer.viewportY, 97); // finger up moves toward older output
    touch('touchmove', 220); // fractional movement retained (80px / 20px = 4 lines)
    assert.equal(buffer.viewportY, 96);
    touch('touchmove', 400); // finger down: newer lines, clamped to bottom
    assert.equal(buffer.viewportY, 100);
    touch('touchend', 400);
    assert.deepEqual(sent, [-3, -1, 9]);
});

test('tap, horizontal gesture, multitouch, and scrollbar do not scroll or cancel input', () => {
    const { touch, sent } = fixture();
    touch('touchstart', 300);
    assert.equal(touch('touchmove', 297), false);
    assert.equal(touch('touchmove', 280, 200), false);
    touch('touchend', 280);
    touch('touchstart', 300);
    assert.equal(touch('touchmove', 260, 100, 2), false);
    touch('touchstart', 300, 100, 1, '.xterm-viewport');
    assert.equal(touch('touchmove', 250), false);
    assert.deepEqual(sent, []);
});

test('empty and alternate buffers do not emit terminal input or scroll history', () => {
    for (const settings of [{ baseY: 0 }, { type: 'alternate' }]) {
        const { touch, sent, buffer } = fixture(settings);
        touch('touchstart', 300);
        touch('touchmove', 200);
        assert.deepEqual(sent, []);
        assert.equal(buffer.viewportY, buffer.baseY);
    }
});
