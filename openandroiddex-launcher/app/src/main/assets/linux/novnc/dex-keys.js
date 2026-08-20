/*
 * The keyboard — the one half of input noVNC gives us a real API for.
 *
 * There are two paths and never both at once. On the desktop display a
 * physical keyboard types into the canvas and noVNC's own Keyboard handles it,
 * scancodes and all; that path is better than anything written here and is left
 * completely alone. On a phone the sink below holds focus, the canvas is deaf,
 * and every key is synthesised through `rfb.sendKey`.
 *
 * Why a hidden textarea and a text diff rather than keydown: with a composing
 * IME, Chromium reports keydown as keyCode 229 / key "Unidentified" for
 * essentially every character key, and with an EMPTY field it often emits no
 * beforeinput for Backspace at all, because there is nothing to delete. So the
 * field is kept padded and typing is read as a prefix diff — the same shape
 * noVNC's own vnc.html uses, with a working reference sitting on the same box.
 */

const PAD_LEN = 40;
/* Underscores, not spaces: noVNC's own vnc.html pads with them, and a run of
   spaces is the one thing a phone keyboard's autocorrect and trailing-space
   handling rewrites underneath a diff. Written as an escape so the pad can
   never be turned into something else by an editor. */
const PAD = '_'.repeat(PAD_LEN);
const REPEAT_DELAY = 400;
const REPEAT_MS = 60;

/* The named keys, by X keysym. Everything else is derived from its code point. */
const XK = {
  BackSpace: 0xff08, Tab: 0xff09, Return: 0xff0d, Escape: 0xff1b,
  Home: 0xff50, Left: 0xff51, Up: 0xff52, Right: 0xff53, Down: 0xff54,
  Page_Up: 0xff55, Page_Down: 0xff56, End: 0xff57, Insert: 0xff63,
  Delete: 0xffff,
  Shift_L: 0xffe1, Control_L: 0xffe3, Meta_L: 0xffe7, Alt_L: 0xffe9, Super_L: 0xffeb,
};

/* The four that latch, in the order they appear in the row. */
const MODS = ['Control_L', 'Alt_L', 'Super_L', 'Shift_L'];

const ROW = [
  { kind: 'key', name: 'Escape', label: 'Esc', code: 'Escape' },
  { kind: 'key', name: 'Tab', label: 'Tab', code: 'Tab' },
  { kind: 'mod', name: 'Control_L', label: 'Ctrl' },
  { kind: 'mod', name: 'Alt_L', label: 'Alt' },
  { kind: 'mod', name: 'Super_L', label: 'Super' },
  { kind: 'mod', name: 'Shift_L', label: 'Shift' },
  { kind: 'rep', name: 'Left', label: '←', code: 'ArrowLeft' },
  { kind: 'rep', name: 'Up', label: '↑', code: 'ArrowUp' },
  { kind: 'rep', name: 'Down', label: '↓', code: 'ArrowDown' },
  { kind: 'rep', name: 'Right', label: '→', code: 'ArrowRight' },
  { kind: 'key', name: 'Home', label: 'Home', code: 'Home' },
  { kind: 'key', name: 'End', label: 'End', code: 'End' },
  { kind: 'key', name: 'Page_Up', label: 'PgUp', code: 'PageUp' },
  { kind: 'key', name: 'Page_Down', label: 'PgDn', code: 'PageDown' },
  { kind: 'key', name: 'Delete', label: 'Del', code: 'Delete' },
  { kind: 'cad', label: 'Ctrl+Alt+Del' },
];

export function initKeys(api) {
  const sink = document.getElementById('sink');
  const row = document.getElementById('keys-row');

  /*
   * Imported, not hand-rolled — but with the rule the table implements anyway
   * as a floor, so an export-shape surprise degrades to "no legacy-keysym
   * special cases" instead of to a dead page.
   */
  let KeyTable = null;
  let lookup = (cp) => (cp < 0x100 ? cp : 0x01000000 + cp);
  (async () => {
    try {
      const m = await import('./core/input/keysym.js');
      if (m && m.default) KeyTable = m.default;
    } catch (e) { /* the XK table above covers every named key we send */ }
    try {
      const m = await import('./core/input/keysymdef.js');
      if (m && m.default && typeof m.default.lookup === 'function') lookup = m.default.lookup;
    } catch (e) { /* the code-point rule is the fallback */ }
  })();

  const keysym = (name) => (KeyTable && KeyTable['XK_' + name]) || XK[name];

  /* ── latches ──────────────────────────────────────────────────────────── */

  /* 0 off, 1 armed (one tap — spent by the next key), 2 locked (two taps). */
  const latch = { Control_L: 0, Alt_L: 0, Super_L: 0, Shift_L: 0 };

  function clearLatches() {
    for (const m of MODS) latch[m] = 0;
    paintLatches();
  }

  function paintLatches() {
    for (const m of MODS) {
      const el = row.querySelector('[data-mod="' + m + '"]');
      if (!el) continue;
      el.classList.toggle('latched', latch[m] > 0);
      el.classList.toggle('locked', latch[m] === 2);
    }
  }

  const MOD_CODE = {
    Control_L: 'ControlLeft', Alt_L: 'AltLeft',
    Super_L: 'MetaLeft', Shift_L: 'ShiftLeft',
  };

  /* The same four as KeyboardEvent reports them, for the physical path. */
  const MOD_EVENT = {
    Control_L: 'ctrlKey', Alt_L: 'altKey',
    Super_L: 'metaKey', Shift_L: 'shiftKey',
  };

  const activeMods = () => MODS.filter((m) => latch[m] > 0);

  function pressMods(list) {
    const rfb = api.rfb;
    if (!rfb) return;
    for (const m of list) rfb.sendKey(keysym(m), MOD_CODE[m], true);
  }

  function releaseMods(list) {
    const rfb = api.rfb;
    if (!rfb) return;
    for (const m of list.slice().reverse()) rfb.sendKey(keysym(m), MOD_CODE[m], false);
  }

  /* An armed latch is spent by the key it modified; a locked one stays. */
  function spendArmed(list) {
    let changed = false;
    for (const m of list) if (latch[m] === 1) { latch[m] = 0; changed = true; }
    if (changed) paintLatches();
  }

  /*
   * Pressed around the key and released again, rather than left physically
   * held: a locked modifier that stayed down would turn every later click into
   * a Ctrl-click on the guest, and nothing in the page would show it. The latch
   * is the state; the key press is momentary.
   *
   * This is consulted here, in the TEXT path, and not only in keydown. Ctrl+C
   * from a soft keyboard arrives as an inserted "c" and nothing else.
   *
   * A key that is HELD (the auto-repeating arrows) cannot use this: the
   * modifier has to stay down for as long as the key does, or Shift+Arrow
   * selects nothing. Those drive pressMods/releaseMods themselves.
   */
  function withLatches(fn, extra) {
    if (!api.rfb) return;
    const latched = activeMods();
    /* Filtered through MODS so the press order stays the one releaseMods
       assumes, and so a modifier that is both latched and physically held is
       pressed once rather than twice. */
    const active = (extra && extra.length)
      ? MODS.filter((m) => latched.indexOf(m) >= 0 || extra.indexOf(m) >= 0)
      : latched;
    pressMods(active);
    try { fn(); } finally {
      releaseMods(active);
      /* Only a latch can be spent. A key someone is physically holding is not
         ours to clear. */
      spendArmed(latched);
    }
  }

  function sendNamed(name, code, extra) {
    const rfb = api.rfb;
    if (!rfb) return;
    withLatches(() => rfb.sendKey(keysym(name), code || null), extra);
  }

  /* for…of, never charCodeAt in a loop: an emoji is two code units and
     halving it produces two keysyms for characters nobody typed. */
  function sendText(str) {
    const rfb = api.rfb;
    if (!rfb) return;
    /* CRLF and a lone CR are one Return, not two and not a dropped line. */
    for (const ch of str.replace(/\r\n?/g, '\n')) {
      /* X has no keysym below 0x20, and the Unicode form (0x0100000a) is a
         control character every toolkit discards — so a pasted newline or tab
         would silently vanish and a multi-line paste would arrive as one line.
         These two are real keys, and passing their real code is not a
         fabrication. */
      if (ch === '\n') { sendNamed('Return', 'Enter'); continue; }
      if (ch === '\t') { sendNamed('Tab', 'Tab'); continue; }
      if (ch < ' ') continue;
      /* code is null for anything else synthesised from text — noVNC uses it
         only to look up an XT scancode, and a fabricated one sends a scancode
         for a key that was never pressed. */
      withLatches(() => rfb.sendKey(lookup(ch.codePointAt(0)), null));
    }
  }

  /* ── the sink ─────────────────────────────────────────────────────────── */

  let last = PAD;
  let composing = false;

  /* The pad sits BEFORE the caret and the caret is pinned at the end. That is
     what makes a prefix diff exact for typing and for backspacing alike. */
  function resetSink() {
    sink.value = PAD;
    try { sink.setSelectionRange(PAD_LEN, PAD_LEN); } catch (e) { /* not focused yet */ }
    last = PAD;
  }

  function keyInput() {
    if (composing) return;
    const v = sink.value;
    let p = 0;
    while (p < v.length && p < last.length && v[p] === last[p]) p++;
    /* p is a CODE UNIT index; back it off a split surrogate pair so it lands on
       a code-point boundary. sendText walks code points, so the BackSpaces have
       to be counted the same way — otherwise one emoji leaves as two of them
       and the second eats a real character of the guest's document. */
    if (p > 0 && p < last.length && (last.charCodeAt(p) & 0xfc00) === 0xdc00) p--;
    for (let i = [...last.slice(p)].length; i > 0; i--) sendNamed('BackSpace', 'Backspace');
    if (v.length > p) sendText(v.slice(p));
    last = v;
    if (v.length < PAD_LEN / 2 || v.length > PAD_LEN * 2) resetSink();
  }

  sink.addEventListener('input', keyInput);
  sink.addEventListener('compositionstart', () => { composing = true; });
  sink.addEventListener('compositionend', () => { composing = false; keyInput(); });

  /* Only the two cases that must never enter the diff. */
  sink.addEventListener('beforeinput', (e) => {
    if (e.inputType === 'insertLineBreak' || e.inputType === 'insertParagraph') {
      e.preventDefault();
      sendNamed('Return', 'Enter');
      return;
    }
    if (e.inputType === 'insertFromPaste') {
      const text = e.data || (e.dataTransfer && e.dataTransfer.getData('text'));
      if (text) {
        e.preventDefault();
        if (api.rfb) api.rfb.clipboardPasteFrom(text);
        sendText(text);
      }
    }
  });

  /*
   * A physical keyboard typing into a focused sink — the DeX case where key and
   * code are real. Everything a composing IME produces is filtered out here,
   * because for those the diff above is the only honest reading.
   */
  sink.addEventListener('keydown', (e) => {
    if (e.keyCode === 229 || e.key === 'Unidentified') return;
    const map = {
      Backspace: 'BackSpace', Enter: 'Return', Tab: 'Tab', Escape: 'Escape',
      Delete: 'Delete', Home: 'Home', End: 'End', PageUp: 'Page_Up', PageDown: 'Page_Down',
      ArrowLeft: 'Left', ArrowRight: 'Right', ArrowUp: 'Up', ArrowDown: 'Down',
    };
    const name = map[e.key];
    if (!name) return;
    e.preventDefault();
    /* This path exists for a REAL keyboard, so the chord it is actually part of
       has to travel with it. Without this every physical Ctrl+Left, Shift+Home
       and Alt+Tab reaches the guest stripped of its modifiers. */
    sendNamed(name, e.code, MODS.filter((m) => e[MOD_EVENT[m]]));
    if (name === 'BackSpace' || name === 'Return') resetSink();
  });

  sink.addEventListener('blur', () => api.onSinkBlur());

  /* ── the keys row ─────────────────────────────────────────────────────── */

  function chip(spec) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'kk' + (spec.kind === 'cad' ? ' wide' : '');
    b.tabIndex = -1;
    b.textContent = spec.label;
    if (spec.kind === 'mod') b.dataset.mod = spec.name;
    if (spec.name) b.setAttribute('aria-label', spec.name.replace(/_L$/, ''));

    /* Never take focus: the sink would lose it and the IME would close on the
       first modifier the user pressed. */
    b.addEventListener('pointerdown', (e) => e.preventDefault());

    if (spec.kind === 'mod') {
      b.addEventListener('click', () => {
        latch[spec.name] = latch[spec.name] === 0 ? 1 : latch[spec.name] === 1 ? 2 : 0;
        paintLatches();
        api.refocus();
      });
      return b;
    }

    if (spec.kind === 'cad') {
      b.addEventListener('click', () => {
        if (api.rfb) api.rfb.sendCtrlAltDel();
        clearLatches();
        api.toast(api.TEXT.toastCad);
        api.refocus();
      });
      return b;
    }

    if (spec.kind === 'rep') {
      /* X11 auto-repeat is down-down-…-up, not a burst of full presses. The
         modifiers stay down for the whole hold — see withLatches. */
      let delay = 0, tick = 0, down = false, mods = [];
      const start = (e) => {
        e.preventDefault();
        if (down || !api.rfb) return;
        down = true;
        mods = activeMods();
        pressMods(mods);
        api.rfb.sendKey(keysym(spec.name), spec.code, true);
        delay = setTimeout(() => {
          tick = setInterval(() => {
            if (api.rfb) api.rfb.sendKey(keysym(spec.name), spec.code, true);
          }, REPEAT_MS);
        }, REPEAT_DELAY);
      };
      const stop = () => {
        if (!down) return;
        down = false;
        clearTimeout(delay); clearInterval(tick);
        if (api.rfb) api.rfb.sendKey(keysym(spec.name), spec.code, false);
        releaseMods(mods);
        spendArmed(mods);
        mods = [];
        api.refocus();
      };
      b.addEventListener('pointerdown', start);
      b.addEventListener('pointerup', stop);
      b.addEventListener('pointercancel', stop);
      b.addEventListener('pointerleave', stop);
      return b;
    }

    b.addEventListener('click', () => { sendNamed(spec.name, spec.code); api.refocus(); });
    return b;
  }

  for (const spec of ROW) row.appendChild(chip(spec));

  /* ── what dex.js drives ───────────────────────────────────────────────── */

  return {
    clearLatches,
    focusSink() { try { sink.focus(); } catch (e) { /* nothing to do */ } },
    /* Focus inside the user's own gesture is the only thing Chromium raises
       the IME for, so this must stay synchronous with the click. */
    showKeyboard() { resetSink(); try { sink.focus(); } catch (e) { /* ignore */ } },
    hideKeyboard() { clearLatches(); try { sink.blur(); } catch (e) { /* ignore */ } },
  };
}
