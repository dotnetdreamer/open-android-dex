/**
 * A game's controls, as the editor holds them: the file's own form
 * (apps/game/layout.rs, doc/game-mode.md "Layouts"), the anchor arithmetic
 * mirrored from there, and the words for keys.
 *
 * A control's place is one of nine anchors of the picture and an offset in
 * units of the picture's shorter side; the editor works in the picture's
 * pixels and turns a dropped control back into the nearest anchor.
 */

export type Anchor =
  | "top-left"
  | "top"
  | "top-right"
  | "left"
  | "center"
  | "right"
  | "bottom-left"
  | "bottom"
  | "bottom-right";

export const ANCHORS: Anchor[] = ["top-left", "top", "top-right", "left", "center", "right", "bottom-left", "bottom", "bottom-right"];

export interface At {
  from: Anchor;
  dx: number;
  dy: number;
}

export type Stick = { type: "stick"; at: At; size: number; up: string | null; left: string | null; down: string | null; right: string | null };
export type Button = { type: "button"; at: At; key: string | null; press: "hold" | "tap" };
export type Look = { type: "look"; at: At; w: number; h: number; by: "hold" | "always"; key: string | null; speed: number };
export type Control = Stick | Button | Look;

/** What game_editor_load answers. */
export interface Loaded {
  id: number;
  app: { pkg: string; name: string };
  shape: "wide" | "tall";
  picture: { w: number; h: number };
  dpi: number;
  controls: Control[] | null;
  other: boolean;
  starter: Control[];
  warned: boolean;
  mac: boolean;
  /** This keyboard has a key under Esc: an Apple JIS keyboard has none, and a look area asks for one instead. */
  underEsc?: boolean;
}

/** As layout.rs clamps them. */
export const STICK_SIZE = { min: 0.06, max: 0.3 };
export const LOOK_SIZE = { min: 0.05, max: 8 };
export const SPEED = { min: 0.25, max: 4 };
const MAX_OFFSET = 8;
export const MAX_CONTROLS = 32;

/** Where an anchor is, as fractions of the picture's width and height. */
export function fraction(a: Anchor): [number, number] {
  const i = Math.max(0, ANCHORS.indexOf(a));
  return [(i % 3) * 0.5, Math.floor(i / 3) * 0.5];
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** The point of `at` in a picture `w` × `h`, inside it. */
export function point(at: At, w: number, h: number): [number, number] {
  const s = Math.min(w, h);
  const [ax, ay] = fraction(at.from);
  return [clamp(ax * w + at.dx * s, 0, w), clamp(ay * h + at.dy * s, 0, h)];
}

/** The place of (`x`, `y`) in a picture `w` × `h`, counted from the anchor nearest it. */
export function nearest(x: number, y: number, w: number, h: number): At {
  const s = Math.max(1, Math.min(w, h));
  let best: Anchor = "center";
  let d = Infinity;
  for (const a of ANCHORS) {
    const [ax, ay] = fraction(a);
    const e = (x - ax * w) ** 2 + (y - ay * h) ** 2;
    if (e < d) {
      d = e;
      best = a;
    }
  }
  const [ax, ay] = fraction(best);
  const r = (n: number) => Math.round(clamp(n, -MAX_OFFSET, MAX_OFFSET) * 10000) / 10000;
  return { from: best, dx: r((x - ax * w) / s), dy: r((y - ay * h) / s) };
}

// ── keys ──

/** Open Android DeX's own: Esc, F8, F11, the key under Esc, ⌘ and the Windows key. */
export const KEPT = new Set(["Escape", "F8", "F11", "Backquote", "MetaLeft", "MetaRight"]);

const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
/** Every key a control may have, as layout.rs's table names them (KeyboardEvent.code). */
export const KNOWN = new Set<string>([
  ...[...LETTERS].map((c) => `Key${c}`),
  ...[..."0123456789"].map((d) => `Digit${d}`),
  ...Array.from({ length: 12 }, (_, i) => `F${i + 1}`),
  ...["Space", "Tab", "Enter", "Backspace", "Escape", "Minus", "Equal", "BracketLeft", "BracketRight", "Backslash"],
  ...["Semicolon", "Quote", "Comma", "Period", "Slash", "IntlBackslash", "Backquote"],
  ...["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight"],
  ...["AltLeft", "AltRight", "MetaLeft", "MetaRight", "CapsLock", "Insert", "Delete", "Home", "End", "PageUp", "PageDown"],
  ...[..."0123456789"].map((d) => `Numpad${d}`),
  ...["NumpadDecimal", "NumpadAdd", "NumpadSubtract", "NumpadMultiply", "NumpadDivide", "NumpadEnter", "NumpadEqual"],
  ...["MouseLeft", "MouseRight", "MouseMiddle", "MouseBack", "MouseForward", "WheelUp", "WheelDown"],
]);

const NAMES: Record<string, string> = {
  Space: "Space",
  Tab: "Tab",
  Enter: "Enter",
  Backspace: "Backspace",
  Minus: "-",
  Equal: "=",
  BracketLeft: "[",
  BracketRight: "]",
  Backslash: "\\",
  Semicolon: ";",
  Quote: "'",
  Comma: ",",
  Period: ".",
  Slash: "/",
  IntlBackslash: "ISO key",
  ArrowUp: "↑",
  ArrowDown: "↓",
  ArrowLeft: "←",
  ArrowRight: "→",
  CapsLock: "Caps Lock",
  Insert: "Insert",
  Delete: "Delete",
  Home: "Home",
  End: "End",
  PageUp: "Page Up",
  PageDown: "Page Down",
  NumpadDecimal: "Num .",
  NumpadAdd: "Num +",
  NumpadSubtract: "Num -",
  NumpadMultiply: "Num *",
  NumpadDivide: "Num /",
  NumpadEnter: "Num Enter",
  NumpadEqual: "Num =",
  MouseLeft: "Left click",
  MouseRight: "Right click",
  MouseMiddle: "Middle click",
  MouseBack: "Back button",
  MouseForward: "Forward button",
  WheelUp: "Wheel up",
  WheelDown: "Wheel down",
};

/** What a chip says for a key: a letter in capitals, else a short name. */
export function keyLabel(code: string | null, mac: boolean): string {
  if (!code) return "None";
  if (/^Key[A-Z]$/.test(code)) return code.slice(3);
  if (/^Digit\d$/.test(code)) return code.slice(5);
  if (/^Numpad\d$/.test(code)) return `Num ${code.slice(6)}`;
  if (/^F\d+$/.test(code)) return code;
  const side = code.endsWith("Right") && !code.startsWith("Arrow") ? "Right " : "";
  if (code.startsWith("Shift")) return `${side}Shift`;
  if (code.startsWith("Control")) return `${side}${mac ? "Control" : "Ctrl"}`;
  if (code.startsWith("Alt")) return `${side}${mac ? "Option" : "Alt"}`;
  return NAMES[code] ?? code;
}

/**
 * Whether `code` is one of Open Android DeX's own keys, which no control may
 * have. On a Mac the ISO key beside left Shift is too: macOS swaps it with the
 * key under Esc on some keyboards, so the window takes both as that key.
 */
export function kept(code: string, mac: boolean): boolean {
  return KEPT.has(code) || (mac && code === "IntlBackslash");
}

/**
 * Why `code` cannot be control `c`'s `field`, in a sentence, or null if it can.
 * Each is a key the game would never feel: a Mac sends Caps Lock as a change
 * of state, not a press and a release; a left click with the mouse free is the
 * game's own finger where the pointer is; a notch of the wheel is let go as it
 * comes. (layout.rs unbinds the last two from a file too.)
 */
export function refusal(c: Control, field: string, code: string, mac: boolean): string | null {
  if (mac && code === "CapsLock") return "Caps Lock can't be held down on a Mac. Pick another key.";
  const held = c.type === "stick" || (c.type === "look" && field === "key");
  if (held && code === "MouseLeft") return "Left click already taps the game. Pick another key or button.";
  if (held && code.startsWith("Wheel")) return "The wheel can't be held down. Pick a key or mouse button.";
  return null;
}

/** The input a key event names, if it is one of the table's. */
export function fromKey(e: KeyboardEvent): string | null {
  return KNOWN.has(e.code) ? e.code : null;
}

/** The input a mouse button names. */
export function fromButton(button: number): string | null {
  return ["MouseLeft", "MouseMiddle", "MouseRight", "MouseBack", "MouseForward"][button] ?? null;
}

/** Every input `c` is bound to, and where. */
export function bindings(c: Control): { field: string; code: string }[] {
  const out: { field: string; code: string }[] = [];
  const add = (field: string, code: string | null) => code && out.push({ field, code });
  if (c.type === "stick") {
    add("up", c.up);
    add("left", c.left);
    add("down", c.down);
    add("right", c.right);
  } else {
    add("key", c.key);
  }
  return out;
}

/** `controls` with `code` given to control `index`'s `field`, and taken from wherever else it was. */
export function bind(controls: Control[], index: number, field: string, code: string | null): Control[] {
  return controls.map((c, i) => {
    let n: Control = { ...c } as Control;
    if (code) {
      for (const b of bindings(c)) {
        if (b.code === code && !(i === index && b.field === field)) n = { ...n, [b.field]: null } as Control;
      }
    }
    if (i === index) n = { ...n, [field]: code } as Control;
    return n;
  });
}

/**
 * Where "All the time" look area `index` stands among those that take the
 * mouse with the same key — its own, or the key under Esc when it has none:
 * which it is (from 0) and how many there are. A key several share takes the
 * mouse for the first, moves it to the next at each press, and gives it back
 * after the last, as the engine does (apps/game/engine.rs, mouse_keys) — so
 * every look area is reached, whatever a file or the default left them on.
 */
export function sharing(controls: Control[], index: number): { place: number; count: number } {
  const c = controls[index];
  if (!c || c.type !== "look" || c.by !== "always") return { place: 0, count: 1 };
  let place = 0;
  let count = 0;
  controls.forEach((o, i) => {
    if (o.type === "look" && o.by === "always" && (o.key ?? null) === (c.key ?? null)) {
      if (i < index) place++;
      count++;
    }
  });
  return { place, count };
}

/** A key no control has yet, of `wanted`, else none. */
function free(controls: Control[], wanted: string): string | null {
  return controls.some((c) => bindings(c).some((b) => b.code === wanted)) ? null : wanted;
}

/** A new control of `type` in the middle of a picture `w` × `h`, with keys that are still free. */
export function fresh(type: Control["type"], controls: Control[], w: number, h: number): Control {
  const at = nearest(w / 2, h / 2, w, h);
  if (type === "stick") {
    return { type, at, size: 0.14, up: free(controls, "KeyW"), left: free(controls, "KeyA"), down: free(controls, "KeyS"), right: free(controls, "KeyD") };
  }
  if (type === "look") {
    const s = Math.min(w, h);
    return { type, at, w: Math.min(0.7, (w * 0.45) / s), h: Math.min(0.6, (h * 0.6) / s), by: "hold", key: free(controls, "MouseRight"), speed: 1 };
  }
  return { type, at, key: null, press: "hold" };
}

export const TITLES: Record<Control["type"], string> = { stick: "Stick", button: "Button", look: "Aim" };
