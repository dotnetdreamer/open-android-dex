/**
 * This computer's keys as X11 keysyms — what an RFB key event carries — for
 * the phone's Xvnc, through the stream (LinuxStream.java).
 *
 * The rules, which are the ones a person expects at a Linux desktop from a
 * Mac or a PC keyboard:
 *
 * - **Typing types what the keyboard makes here.** The character comes from
 *   `key`, so a French or German layout, AltGr, a dead key's result and Caps
 *   Lock all arrive as the characters they made. Xvnc finds a key for any
 *   keysym and presses Shift for it as needed, so the guest's own layout
 *   does not matter.
 * - **Shortcuts are the key, not the character.** With Ctrl or Cmd held, a
 *   layout's character is the wrong question — Ctrl+C on a Cyrillic layout
 *   is still Ctrl+C — so the letter comes from `code`, the key's place.
 * - **Option on a Mac types.** It is how a Mac makes `@` on half its
 *   layouts; when it changed the character, the guest is sent the character
 *   with Alt let go around it. With a key that has no character — an arrow,
 *   Tab, F4 — it is Alt.
 * - **Caps Lock and Num Lock stay here.** Their effect is already in `key`;
 *   toggling the guest's as well would apply it twice.
 *
 * Pure, so it runs under plain Node in test/usb-desktop-test.mjs.
 */

/** One key event's worth of what a KeyboardEvent says. */
export interface KeyInput {
  key: string;
  code: string;
  ctrlKey: boolean;
  altKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
}

export const XK_SHIFT_L = 0xffe1;
export const XK_SHIFT_R = 0xffe2;
export const XK_CONTROL_L = 0xffe3;
export const XK_CONTROL_R = 0xffe4;
export const XK_ALT_L = 0xffe9;
export const XK_ALT_R = 0xffea;
export const XK_SUPER_L = 0xffeb;
export const XK_SUPER_R = 0xffec;
export const XK_ISO_LEVEL3_SHIFT = 0xfe03;

const MODIFIERS: Record<string, number> = {
  ShiftLeft: XK_SHIFT_L,
  ShiftRight: XK_SHIFT_R,
  ControlLeft: XK_CONTROL_L,
  ControlRight: XK_CONTROL_R,
  AltLeft: XK_ALT_L,
  AltRight: XK_ALT_R,
  MetaLeft: XK_SUPER_L,
  MetaRight: XK_SUPER_R,
  OSLeft: XK_SUPER_L,
  OSRight: XK_SUPER_R,
};

const NAMED: Record<string, number> = {
  Backspace: 0xff08,
  Tab: 0xff09,
  Enter: 0xff0d,
  Escape: 0xff1b,
  Delete: 0xffff,
  Home: 0xff50,
  ArrowLeft: 0xff51,
  ArrowUp: 0xff52,
  ArrowRight: 0xff53,
  ArrowDown: 0xff54,
  PageUp: 0xff55,
  PageDown: 0xff56,
  End: 0xff57,
  Insert: 0xff63,
  Pause: 0xff13,
  ScrollLock: 0xff14,
  PrintScreen: 0xff61,
  ContextMenu: 0xff67,
  Help: 0xff6a,
  AudioVolumeMute: 0x1008ff12,
  AudioVolumeDown: 0x1008ff11,
  AudioVolumeUp: 0x1008ff13,
  MediaPlayPause: 0x1008ff14,
  MediaStop: 0x1008ff15,
  MediaTrackPrevious: 0x1008ff16,
  MediaTrackNext: 0x1008ff17,
  BrowserBack: 0x1008ff26,
  BrowserForward: 0x1008ff27,
};
for (let i = 1; i <= 24; i++) NAMED[`F${i}`] = 0xffbd + i;

/** The character a key's place makes on a US layout — for shortcuts. */
const BASE: Record<string, string> = {
  Minus: "-",
  Equal: "=",
  BracketLeft: "[",
  BracketRight: "]",
  Backslash: "\\",
  Semicolon: ";",
  Quote: "'",
  Backquote: "`",
  Comma: ",",
  Period: ".",
  Slash: "/",
  Space: " ",
  IntlBackslash: "<",
};
for (let c = 0; c < 26; c++) BASE[`Key${String.fromCharCode(65 + c)}`] = String.fromCharCode(97 + c);
for (let d = 0; d < 10; d++) BASE[`Digit${d}`] = String(d);

/** Never sent: their effect is already in the characters. */
const KEPT_HERE = new Set(["CapsLock", "NumLock"]);

/** A character's keysym: Latin-1 as itself, anything else in the Unicode block. */
export function charKeysym(ch: string): number {
  const cp = ch.codePointAt(0) ?? 0;
  if ((cp >= 0x20 && cp <= 0x7e) || (cp >= 0xa0 && cp <= 0xff)) return cp;
  return 0x01000000 | cp;
}

/** One printable character, as `key` gives it — or null for a named key. */
function printable(key: string): string | null {
  const chars = [...key];
  return chars.length === 1 && key !== "Dead" ? chars[0] : null;
}

/** What to do with one keydown. */
export type KeyAction =
  /** Press this keysym now; release it at this key's keyup. */
  | { kind: "press"; keysym: number }
  /**
   * A Mac's Option made a character: type it whole now, with Alt let go
   * around it; this key's keyup sends nothing.
   */
  | { kind: "type"; keysym: number }
  /** Nothing for the guest: a dead key, an IME at work, Caps Lock. */
  | { kind: "none" };

export function keyDown(e: KeyInput, mac: boolean): KeyAction {
  if (KEPT_HERE.has(e.code) || KEPT_HERE.has(e.key)) return { kind: "none" };
  if (e.key === "Dead" || e.key === "Process" || e.key === "Unidentified" && !BASE[e.code]) {
    return { kind: "none" };
  }
  const modifier = MODIFIERS[e.code];
  if (modifier) return { kind: "press", keysym: modifier };
  if (e.key === "AltGraph") return { kind: "press", keysym: XK_ISO_LEVEL3_SHIFT };
  const named = NAMED[e.key];
  if (named) return { kind: "press", keysym: named };
  const base = BASE[e.code];
  // A shortcut: the key's place, not the character. AltGr on Windows is
  // Ctrl+Alt, and makes characters — so not when Alt is down too.
  if ((e.ctrlKey || e.metaKey) && !e.altKey && base) {
    return { kind: "press", keysym: charKeysym(base) };
  }
  const ch = printable(e.key);
  if (ch !== null) {
    if (mac && e.altKey && !e.ctrlKey && !e.metaKey && base && ch.toLowerCase() !== base) {
      return { kind: "type", keysym: charKeysym(ch) };
    }
    return { kind: "press", keysym: charKeysym(ch) };
  }
  if (base) return { kind: "press", keysym: charKeysym(base) };
  return { kind: "none" };
}

/** Alt, as the guest has it pressed — for a Mac's Option to be let go around a character. */
export const ALTS = [XK_ALT_L, XK_ALT_R];

/** The keys that are modifiers, whose release a Mac does report while Cmd is held. */
export function isModifier(keysym: number): boolean {
  return keysym >= XK_SHIFT_L && keysym <= XK_SUPER_R;
}
