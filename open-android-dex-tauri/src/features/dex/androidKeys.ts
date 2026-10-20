/**
 * This computer's keyboard and mouse as the phone's Android desktop takes them
 * (DexWindow.tsx → openandroiddex-wmd, DesktopInput).
 *
 * Two kinds of key go to the phone. **Text**: the character this keyboard just
 * made, in whatever layout it has — AltGr, a Mac's Option, dead keys all done
 * here — which the phone types with the key presses its own virtual keyboard
 * would use, or hands over whole where it has none (an é, 日本). **Keys**: the
 * ones that do things — arrows, Enter, Backspace, function keys — and any key
 * pressed with a shortcut modifier, as Android key codes with Android's meta
 * bits, so Ctrl+A selects all and Shift+arrow extends a selection.
 *
 * A Mac is spoken to as Mac users type. Cmd is the shortcut key, so Cmd+key
 * goes as Ctrl+key (Android's shortcut key) — except Cmd+C, X and V, which go as
 * Android's own Copy, Cut and Paste keys: a Linux window on the desktop turns
 * those into Ctrl or Ctrl+Shift depending on the app (a terminal's is
 * Ctrl+Shift+C), where a bare Ctrl+C would interrupt whatever it was running.
 * Cmd and Option with the arrows and Backspace move and delete as they do on a
 * Mac. Cmd+Q, H, M, Tab, ` and Space stay the Mac's.
 *
 * Pure, so test/dex-video-test.mjs runs this very file.
 */

// android.view.KeyEvent's meta bits
export const META_SHIFT = 0x1 | 0x40; // META_SHIFT_ON | META_SHIFT_LEFT_ON
export const META_ALT = 0x2 | 0x10; // META_ALT_ON | META_ALT_LEFT_ON
export const META_CTRL = 0x1000 | 0x2000; // META_CTRL_ON | META_CTRL_LEFT_ON
export const META_META = 0x10000 | 0x20000; // META_META_ON | META_META_LEFT_ON
export const META_CAPS_LOCK = 0x100000;

// android.view.KeyEvent's codes the mapping itself needs
export const KEYCODE_BACK = 4;
export const KEYCODE_DEL = 67; // Backspace
export const KEYCODE_FORWARD_DEL = 112;
export const KEYCODE_DPAD_UP = 19;
export const KEYCODE_DPAD_DOWN = 20;
export const KEYCODE_DPAD_LEFT = 21;
export const KEYCODE_DPAD_RIGHT = 22;
export const KEYCODE_MOVE_HOME = 122;
export const KEYCODE_MOVE_END = 123;
export const KEYCODE_FORWARD = 125;
export const KEYCODE_CUT = 277;
export const KEYCODE_COPY = 278;
export const KEYCODE_PASTE = 279;

/** Android's key code for each key a computer's keyboard has, by KeyboardEvent.code. */
export const KEYCODES: Record<string, number> = {
  Escape: 111,
  Backquote: 68,
  Minus: 69,
  Equal: 70,
  Backspace: KEYCODE_DEL,
  Tab: 61,
  BracketLeft: 71,
  BracketRight: 72,
  Backslash: 73,
  IntlBackslash: 73,
  CapsLock: 115,
  Semicolon: 74,
  Quote: 75,
  Enter: 66,
  Comma: 55,
  Period: 56,
  Slash: 76,
  IntlRo: 217,
  IntlYen: 216,
  Space: 62,
  ShiftLeft: 59,
  ShiftRight: 60,
  ControlLeft: 113,
  ControlRight: 114,
  AltLeft: 57,
  AltRight: 58,
  MetaLeft: 117,
  MetaRight: 118,
  ContextMenu: 82,
  PrintScreen: 120,
  ScrollLock: 116,
  Pause: 121,
  Insert: 124,
  Delete: KEYCODE_FORWARD_DEL,
  Home: KEYCODE_MOVE_HOME,
  End: KEYCODE_MOVE_END,
  PageUp: 92,
  PageDown: 93,
  ArrowUp: KEYCODE_DPAD_UP,
  ArrowDown: KEYCODE_DPAD_DOWN,
  ArrowLeft: KEYCODE_DPAD_LEFT,
  ArrowRight: KEYCODE_DPAD_RIGHT,
  NumLock: 143,
  NumpadDivide: 154,
  NumpadMultiply: 155,
  NumpadSubtract: 156,
  NumpadAdd: 157,
  NumpadEnter: 160,
  NumpadDecimal: 158,
  NumpadComma: 159,
  NumpadEqual: 161,
  Numpad0: 144,
  AudioVolumeMute: 164,
  AudioVolumeDown: 25,
  AudioVolumeUp: 24,
  MediaPlayPause: 85,
  MediaStop: 86,
  MediaTrackNext: 87,
  MediaTrackPrevious: 88,
  BrowserBack: KEYCODE_BACK,
  BrowserForward: KEYCODE_FORWARD,
};
for (let i = 0; i < 26; i++) KEYCODES[`Key${String.fromCharCode(65 + i)}`] = 29 + i;
for (let i = 0; i < 10; i++) KEYCODES[`Digit${i}`] = 7 + i;
for (let i = 1; i < 10; i++) KEYCODES[`Numpad${i}`] = 144 + i;
for (let i = 1; i <= 12; i++) KEYCODES[`F${i}`] = 130 + i;

/** The modifiers any input event says are held: a key's, the mouse's, the wheel's. */
export interface ModifiersLike {
  shiftKey: boolean;
  ctrlKey: boolean;
  altKey: boolean;
  metaKey: boolean;
  getModifierState?: (key: string) => boolean;
}

/** What a KeyboardEvent has that this reads; a plain object in the tests. */
export interface KeyLike extends ModifiersLike {
  key: string;
  code: string;
  isComposing?: boolean;
  keyCode?: number;
}

export type KeyAction =
  /** Nothing to send: a dead key waiting for its letter, a composition. */
  | { kind: "none" }
  /** The computer's own: let it through (Cmd+Q, Cmd+Tab…). */
  | { kind: "system" }
  /** Characters, typed. */
  | { kind: "text"; text: string }
  /** A key, held until its keyup — Android's code and meta. */
  | { kind: "key"; code: number; meta: number };

/** The Mac's own shortcuts, by key, with Cmd: quit, hide, minimise, the app and window switchers, Spotlight. */
const MAC_SYSTEM = new Set(["KeyQ", "KeyH", "KeyM", "Tab", "Backquote", "Space"]);

/** One printable character, not a control: what a key typed. */
function printable(key: string) {
  const cps = [...key];
  return cps.length === 1 && key.charCodeAt(0) >= 0x20 && key !== "\u007f";
}

/** The meta bits for the modifiers held, a Mac's Cmd as Ctrl. */
export function metaOf(e: ModifiersLike, mac: boolean): number {
  let m = 0;
  if (e.shiftKey) m |= META_SHIFT;
  if (e.altKey) m |= META_ALT;
  if (e.ctrlKey || (mac && e.metaKey)) m |= META_CTRL;
  if (!mac && e.metaKey) m |= META_META;
  if (e.getModifierState?.("CapsLock")) m |= META_CAPS_LOCK;
  return m;
}

/** A letter key, by the letter it makes in this layout when it makes one: Ctrl+A is select-all on AZERTY too. */
function codeFor(e: KeyLike): number | undefined {
  if (/^[a-zA-Z]$/.test(e.key) && e.code.startsWith("Key")) return 29 + (e.key.toLowerCase().charCodeAt(0) - 97);
  return KEYCODES[e.code];
}

/** What a keydown is, for the phone. */
export function keyDown(e: KeyLike, mac: boolean): KeyAction {
  if (e.isComposing || e.keyCode === 229 || e.key === "Dead") return { kind: "none" };
  if (mac && e.metaKey) {
    if (MAC_SYSTEM.has(e.code) || (e.ctrlKey && e.code === "KeyF")) return { kind: "system" };
    const letter = e.key.toLowerCase();
    if (!e.shiftKey && !e.altKey && !e.ctrlKey) {
      if (letter === "c" || e.code === "KeyC") return { kind: "key", code: KEYCODE_COPY, meta: 0 };
      if (letter === "x" || e.code === "KeyX") return { kind: "key", code: KEYCODE_CUT, meta: 0 };
      if (letter === "v" || e.code === "KeyV") return { kind: "key", code: KEYCODE_PASTE, meta: 0 };
    }
    // Moving and deleting as a Mac does: to the line's ends, the document's, a word at a time.
    const shift = e.shiftKey ? META_SHIFT : 0;
    switch (e.code) {
      case "ArrowLeft":
        return { kind: "key", code: KEYCODE_MOVE_HOME, meta: shift };
      case "ArrowRight":
        return { kind: "key", code: KEYCODE_MOVE_END, meta: shift };
      case "ArrowUp":
        return { kind: "key", code: KEYCODE_MOVE_HOME, meta: shift | META_CTRL };
      case "ArrowDown":
        return { kind: "key", code: KEYCODE_MOVE_END, meta: shift | META_CTRL };
    }
  }
  if (mac && e.altKey && !e.metaKey && !e.ctrlKey) {
    const shift = e.shiftKey ? META_SHIFT : 0;
    switch (e.code) {
      case "ArrowLeft":
        return { kind: "key", code: KEYCODE_DPAD_LEFT, meta: shift | META_CTRL };
      case "ArrowRight":
        return { kind: "key", code: KEYCODE_DPAD_RIGHT, meta: shift | META_CTRL };
      case "Backspace":
        return { kind: "key", code: KEYCODE_DEL, meta: META_CTRL };
      case "Delete":
        return { kind: "key", code: KEYCODE_FORWARD_DEL, meta: META_CTRL };
    }
  }
  // A character, unless a shortcut modifier is held: Ctrl alone (AltGr is
  // Ctrl+Alt on Windows, and types), Cmd, or — off a Mac, where Option types —
  // Alt alone.
  const shortcut = e.metaKey || (e.ctrlKey && !e.altKey) || (!mac && e.altKey && !e.ctrlKey);
  if (printable(e.key) && !shortcut) return { kind: "text", text: e.key };
  // A Mac's Cmd, on its own, is a Ctrl the shortcuts above already carry.
  if (mac && (e.code === "MetaLeft" || e.code === "MetaRight")) return { kind: "none" };
  const code = codeFor(e);
  if (code === undefined) return { kind: "none" };
  return { kind: "key", code, meta: metaOf(e, mac) };
}

// ── the mouse ──

/** MotionEvent.BUTTON_*: primary, secondary, tertiary — the browser's `buttons` bits, as it happens. */
export const BUTTONS_FORWARDED = 1 | 2 | 4;

/**
 * The browser's buttons as the phone takes them: left, right and middle as
 * Android's mouse buttons; back and forward (a mouse's side buttons) as the
 * keys a real mouse's become on Android; Shift with the right button as Back,
 * the desktop's old binding for it.
 */
export function mouseButtons(buttons: number, shift: boolean): { held: number; back: boolean; forward: boolean } {
  let held = buttons & BUTTONS_FORWARDED;
  let back = (buttons & 8) !== 0;
  if (shift && held & 2) {
    held &= ~2;
    back = true;
  }
  return { held, back, forward: (buttons & 16) !== 0 };
}

/**
 * A wheel event as Android's scroll axes [horizontal, vertical], in notches:
 * one notch is 64 dp, which on this desktop is 64 of the computer's points, so
 * a page scrolls as far on the phone as it would have here. Android's vertical
 * axis is positive upwards, the reverse of the browser's; its horizontal one is
 * positive to the right, as the browser's is.
 */
export function wheelNotches(deltaX: number, deltaY: number, deltaMode: number): [number, number] {
  const px = deltaMode === 1 ? 40 : deltaMode === 2 ? 800 : 1;
  return [(deltaX * px) / 64, -(deltaY * px) / 64];
}
