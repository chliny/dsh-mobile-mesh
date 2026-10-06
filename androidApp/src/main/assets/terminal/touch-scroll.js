// Map one-finger vertical gestures to xterm's scrollback, which WebView does not
// scroll by itself (the terminal fills a fixed, overflow-hidden viewport).
function installTerminalTouchScrolling(container, term) {
  let lastY = null;
  let originX = 0;
  let originY = 0;
  let vertical = false;
  let nativeTextGesture = false;
  container.addEventListener('touchstart', event => {
    const target = event.target.nodeType === 3 ? event.target.parentElement : event.target;
    lastY = event.touches.length === 1 ? event.touches[0].clientY : null;
    if (lastY === null || !target.closest ||
        !target.closest('.xterm-screen, .xterm-accessibility-tree') ||
        target.closest('.xterm-helper-textarea')) {
      lastY = null;
      return;
    }
    originY = lastY;
    originX = event.touches[0].clientX;
    vertical = false;
    nativeTextGesture = !!target.closest('.xterm-accessibility-tree');
  }, {passive: true});
  container.addEventListener('touchmove', event => {
    if (lastY === null || event.touches.length !== 1) { lastY = null; return; }
    const x = event.touches[0].clientX;
    const y = event.touches[0].clientY;
    const target = event.target.nodeType === 3 ? event.target.parentElement : event.target;
    // Once WebView creates a text range, don't cancel its native selection-handle drag.
    if (nativeTextGesture && target.closest('.xterm-accessibility-tree') &&
        typeof window !== 'undefined' && !window.getSelection().isCollapsed) {
      lastY = y;
      return;
    }
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
    lastY = null;
    vertical = false;
    nativeTextGesture = false;
  };
  container.addEventListener('touchend', end, {passive: true});
  container.addEventListener('touchcancel', end, {passive: true});
}
