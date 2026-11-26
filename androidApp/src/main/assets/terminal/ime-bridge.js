'use strict';

/**
 * Stream IME text from the helper textarea to the PTY like a normal text field.
 *
 * xterm treats that textarea as a keystroke sink: it re-reads committed text
 * from a position captured at the first composition start and never clears it,
 * so an unmatched compositionend (common in Chrome on Android around '.' / '-')
 * resends the whole line, and its keyCode 229 diff leaks preedit characters.
 *
 * This bridge gives the IME the editable buffer it expects instead. The helper
 * textarea keeps the current input line, so the composition, candidate
 * replacement and in-preedit Backspace all behave like in a normal field, and
 * only text beyond what the terminal already received is forwarded. xterm keeps
 * rendering (including the inline preedit) and forwarding keys and paste, but no
 * longer forwards IME text.
 *
 * @param term the xterm.js Terminal instance
 * @param send forwards terminal input to the host PTY
 */
function installTerminalImeBridge(term, send) {
  const textarea = term.textarea;
  let composing = false;
  // Prefix of the helper textarea that the terminal has already received. The
  // textarea is the IME's editing buffer, so this is normally its prefix.
  let forwarded = '';

  function forward(text) {
    if (!text) return;
    send(text.replace(/\r?\n/g, '\r'));
  }

  // Forward the newly committed suffix of the editing buffer. Text the IME
  // rewrote behind that prefix cannot be taken back from a PTY, so it is adopted
  // without being sent a second time.
  function sync() {
    const value = textarea.value;
    if (value === forwarded) return;
    if (value.startsWith(forwarded)) {
      forward(value.slice(forwarded.length));
      forwarded = value;
    } else if (value === '') {
      forwarded = ''; // xterm cleared the helper textarea (Enter, paste, blur)
    } else {
      forwarded = value;
    }
  }

  // xterm finalizes a composition by re-reading the textarea from a stale start
  // position and forwarding the result. The bridge owns IME text instead; keep
  // the composition helper for the inline preview only.
  const helper = term._core && term._core._compositionHelper;
  if (helper && typeof helper._finalizeComposition === 'function') {
    helper._finalizeComposition = function () {
      this._compositionView.classList.remove('active');
      this._isComposing = false;
      this._isSendingComposition = false;
      this._dataAlreadySent = '';
    };
  } else {
    // Unknown xterm build: keep composition events away from xterm's listeners so
    // it cannot forward IME text (the inline preedit is then unavailable).
    for (const type of ['compositionstart', 'compositionupdate', 'compositionend']) {
      window.addEventListener(type, event => {
        if (event.target === textarea) event.stopPropagation();
      }, true);
    }
  }

  window.addEventListener('compositionstart', event => {
    if (event.target === textarea) composing = true;
  }, true);

  window.addEventListener('compositionend', event => {
    if (event.target !== textarea) return;
    composing = false;
    const value = textarea.value;
    const committed = event.data || '';
    // The IME can report the committed text while the textarea still shows the
    // preedit (a tapped candidate); only the commit is forwarded then. An empty
    // textarea means there is nothing to commit: a stray compositionend must not
    // resend text, so that commit is left to the input event that applies it.
    if (committed && value.startsWith(forwarded) && value.length > forwarded.length) {
      const tail = value.slice(forwarded.length);
      if (tail === committed) {
        sync();
      } else {
        forward(committed);
        forwarded += committed;
      }
    } else {
      sync();
    }
  }, true);

  window.addEventListener('input', event => {
    if (event.target !== textarea) return;
    if (composing) return;
    const type = event.inputType || '';
    if (type === 'insertFromPaste' || type === 'insertFromDrop') {
      // xterm's paste handler already forwarded the clipboard text; keep the
      // textarea in step with it instead of sending the text a second time.
      forwarded = textarea.value;
      return;
    }
    sync();
  }, true);

  // xterm clears the helper textarea on Enter, Ctrl-C, paste and blur; start the
  // next line from an empty baseline. That clear happens in xterm's own listener,
  // which runs after this one, so check it on a task and not in a microtask --
  // otherwise the first commit of the new line looks already forwarded.
  const resetWhenCleared = () => setTimeout(() => {
    if (textarea.value === '') forwarded = '';
  }, 0);
  window.addEventListener('blur', event => {
    if (event.target !== textarea) return;
    composing = false;
    resetWhenCleared();
  }, true);
  window.addEventListener('paste', event => {
    if (event.target === textarea) resetWhenCleared();
  }, true);

  window.addEventListener('keydown', event => {
    if (event.target !== textarea) return;
    if (event.keyCode === 229) {
      // Chrome on Android reports every soft-key commit behind a keyCode 229
      // keydown; xterm's fallback diff would forward the preedit characters.
      event.stopPropagation();
      return;
    }
    if (event.keyCode === 13) {
      // Enter commits a pending preedit, then xterm runs the line and clears the
      // helper textarea.
      sync();
      resetWhenCleared();
    } else if (event.keyCode === 3) {
      resetWhenCleared();
    } else if (composing && (event.keyCode === 8 || event.keyCode === 46 || event.keyCode === 67)) {
      // Backspace and Delete edit the preedit, not the command line: let the
      // browser apply them to the textarea and keep the key away from xterm.
      event.stopPropagation();
    }
  }, true);
}
