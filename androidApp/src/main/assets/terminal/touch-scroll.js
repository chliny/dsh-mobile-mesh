// Terminal gestures for a canvas-painted xterm: one-finger vertical swipes scroll the
// scrollback (WebView does not scroll the fixed, overflow-hidden viewport by itself) and a
// long press starts a row selection, because xterm paints with `user-select: none` and the
// WebView callout can never select terminal glyphs on its own.
function installTerminalTouchScrolling(container, term) {
  let lastY = null;
  let originX = 0;
  let originY = 0;
  let vertical = false;
  let selectionTimer = null;
  let selectionStart = null;
  let selecting = false;
  const rowAt = y => {
    const rect = container.getBoundingClientRect();
    const rowHeight = rect.height / Math.max(1, term.rows);
    const buffer = term.buffer.active;
    if (!(rowHeight > 0)) return buffer.viewportY;
    // xterm selects by absolute buffer line, so a viewport row shifts by the scroll offset.
    const row = Math.floor((y - rect.top) / rowHeight);
    const line = buffer.viewportY + Math.max(0, Math.min(term.rows - 1, row));
    return Math.max(0, Math.min(buffer.length - 1, line));
  };
  const cancelSelectionTimer = () => { clearTimeout(selectionTimer); selectionTimer = null; };
  container.addEventListener('touchstart', event => {
    const target = event.target;
    lastY = event.touches.length === 1 ? event.touches[0].clientY : null;
    if (lastY === null || !target.closest || !target.closest('.xterm-screen') ||
        target.closest('.xterm-helper-textarea')) {
      lastY = null;
      return;
    }
    originY = lastY;
    originX = event.touches[0].clientX;
    vertical = false;
    selecting = false;
    cancelSelectionTimer();
    selectionTimer = setTimeout(() => {
      selectionStart = rowAt(originY);
      selecting = true;
      term.selectLines(selectionStart, selectionStart);
    }, 500);
  }, {passive: true});
  container.addEventListener('touchmove', event => {
    if (lastY === null || event.touches.length !== 1) { lastY = null; return; }
    const x = event.touches[0].clientX;
    const y = event.touches[0].clientY;
    if (selecting) {
      if (event.cancelable) event.preventDefault();
      term.selectLines(Math.min(selectionStart, rowAt(y)), Math.max(selectionStart, rowAt(y)));
      return;
    }
    if (Math.abs(y - originY) >= 8 || Math.abs(x - originX) >= 8) cancelSelectionTimer();
    if (!vertical) {
      if (Math.abs(y - originY) < 8 || Math.abs(y - originY) <= Math.abs(x - originX)) return;
      vertical = true;
    }
    // A terminal alternate screen (vim, less, etc.) has no scrollback to inspect.
    // Do not mutate its input stream or turn a swipe into cursor keys.
    if (event.cancelable) event.preventDefault();
    if (term.buffer.active.type !== 'normal' || term.buffer.active.baseY === 0) { lastY = y; return; }
    const rowHeight = container.getBoundingClientRect().height / Math.max(1, term.rows);
    if (!(rowHeight > 0)) { lastY = y; return; }
    // Follow requested direction: finger up shows newer output; finger down shows older output.
    const lines = Math.trunc((lastY - y) / rowHeight);
    if (lines !== 0) {
      term.scrollLines(lines);
      lastY -= lines * rowHeight; // preserve partial rows across small movements
    }
  }, {passive: false});
  const end = () => {
    cancelSelectionTimer();
    // A tap that never became a selection drops a stale one, so Copy acts on what is on screen.
    if (!selecting && term.hasSelection()) term.clearSelection();
    lastY = null;
    vertical = false;
    selecting = false;
  };
  container.addEventListener('touchend', end, {passive: true});
  container.addEventListener('touchcancel', end, {passive: true});
}
