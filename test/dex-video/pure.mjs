// The Android desktop window's pure parts, as test/dex-video-test.mjs runs
// them: the keyboard and mouse as the phone takes them (androidKeys.ts), and
// how old a frame is when it shows (latency.ts). Node 24 imports the .ts files
// as they are.

import path from "node:path";
import { pathToFileURL } from "node:url";

export async function pureChecks({ check, DEX_UI, USB }) {
  const k = await import(pathToFileURL(path.join(DEX_UI, "androidKeys.ts")).href);
  const ev = (key, code, mods = {}) => ({ key, code, shiftKey: false, ctrlKey: false, altKey: false, metaKey: false, ...mods });
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

  console.log("androidKeys.ts");
  check("a letter is typed as text", same(k.keyDown(ev("a", "KeyA"), false), { kind: "text", text: "a" }));
  check("Shift+letter types the capital", same(k.keyDown(ev("A", "KeyA", { shiftKey: true }), false), { kind: "text", text: "A" }));
  check("a layout's own character is typed as it is (AZERTY's é)", same(k.keyDown(ev("é", "Digit2"), false), { kind: "text", text: "é" }));
  check("an emoji is text, whole", same(k.keyDown(ev("🙂", "KeyE"), false), { kind: "text", text: "🙂" }));
  check("a dead key waits for its letter", k.keyDown(ev("Dead", "BracketLeft"), false).kind === "none");
  check("an IME composition is the IME's", k.keyDown({ ...ev("a", "KeyA"), isComposing: true }, false).kind === "none");
  check("Enter, Backspace and arrows are keys", ["Enter", "Backspace", "ArrowLeft"].every((c) => k.keyDown(ev(c, c), false).kind === "key"));
  check("Enter is KEYCODE_ENTER", k.keyDown(ev("Enter", "Enter"), false).code === 66);
  check("F5 is KEYCODE_F5", k.keyDown(ev("F5", "F5"), false).code === 135);
  check("Numpad 7 is KEYCODE_NUMPAD_7", k.keyDown(ev("7", "Numpad7", { ctrlKey: true }), false).code === 151);
  const ctrlA = k.keyDown(ev("a", "KeyA", { ctrlKey: true }), false);
  check("Ctrl+A is the A key with Ctrl", ctrlA.kind === "key" && ctrlA.code === 29 && (ctrlA.meta & k.META_CTRL) === k.META_CTRL, JSON.stringify(ctrlA));
  const azerty = k.keyDown(ev("a", "KeyQ", { ctrlKey: true }), false);
  check("Ctrl with the key that types 'a' on AZERTY is Ctrl+A, not Ctrl+Q", azerty.code === 29, JSON.stringify(azerty));
  check("AltGr (Ctrl+Alt on Windows) types its character", same(k.keyDown(ev("@", "KeyQ", { ctrlKey: true, altKey: true }), false), { kind: "text", text: "@" }));
  check("Alt+letter off a Mac is a shortcut", k.keyDown(ev("f", "KeyF", { altKey: true }), false).kind === "key");
  check("Option+letter on a Mac types its character", same(k.keyDown(ev("ƒ", "KeyF", { altKey: true }), true), { kind: "text", text: "ƒ" }));
  const shiftArrow = k.keyDown(ev("ArrowRight", "ArrowRight", { shiftKey: true }), false);
  check("Shift+arrow carries Shift, to extend a selection", (shiftArrow.meta & k.META_SHIFT) === k.META_SHIFT);
  check("the Windows key is Meta off a Mac", (k.keyDown(ev("d", "KeyD", { metaKey: true }), false).meta & k.META_META) === k.META_META);

  console.log("androidKeys.ts — a Mac");
  check("Cmd+C is Android's Copy key", same(k.keyDown(ev("c", "KeyC", { metaKey: true }), true), { kind: "key", code: k.KEYCODE_COPY, meta: 0 }));
  check("Cmd+X is Cut", k.keyDown(ev("x", "KeyX", { metaKey: true }), true).code === k.KEYCODE_CUT);
  check("Cmd+V is Paste", k.keyDown(ev("v", "KeyV", { metaKey: true }), true).code === k.KEYCODE_PASTE);
  const cmdA = k.keyDown(ev("a", "KeyA", { metaKey: true }), true);
  check("Cmd+A is Ctrl+A", cmdA.code === 29 && (cmdA.meta & k.META_CTRL) === k.META_CTRL && !(cmdA.meta & k.META_META), JSON.stringify(cmdA));
  const cmdShiftZ = k.keyDown(ev("z", "KeyZ", { metaKey: true, shiftKey: true }), true);
  check("Cmd+Shift+Z is Ctrl+Shift+Z (redo)", cmdShiftZ.code === 54 && (cmdShiftZ.meta & (k.META_CTRL | k.META_SHIFT)) === (k.META_CTRL | k.META_SHIFT));
  check("Cmd+Q, Cmd+Tab, Cmd+Space stay the Mac's", ["KeyQ", "Tab", "Space", "KeyH", "KeyM"].every((c) => k.keyDown(ev(c === "Space" ? " " : "q", c, { metaKey: true }), true).kind === "system"));
  check("Cmd alone sends nothing — it is the shortcuts' Ctrl", k.keyDown(ev("Meta", "MetaLeft", { metaKey: true }), true).kind === "none");
  check("Cmd+Left goes to the line's start", same(k.keyDown(ev("ArrowLeft", "ArrowLeft", { metaKey: true }), true), { kind: "key", code: k.KEYCODE_MOVE_HOME, meta: 0 }));
  const cmdDown = k.keyDown(ev("ArrowDown", "ArrowDown", { metaKey: true, shiftKey: true }), true);
  check("Cmd+Shift+Down selects to the end of the document", cmdDown.code === k.KEYCODE_MOVE_END && (cmdDown.meta & (k.META_CTRL | k.META_SHIFT)) === (k.META_CTRL | k.META_SHIFT));
  const optLeft = k.keyDown(ev("ArrowLeft", "ArrowLeft", { altKey: true }), true);
  check("Option+Left is a word left (Ctrl+Left)", optLeft.code === k.KEYCODE_DPAD_LEFT && (optLeft.meta & k.META_CTRL) === k.META_CTRL);
  check("Option+Backspace deletes a word (Ctrl+Backspace)", same(k.keyDown(ev("Backspace", "Backspace", { altKey: true }), true), { kind: "key", code: k.KEYCODE_DEL, meta: k.META_CTRL }));
  check("Ctrl on a Mac is Ctrl too", (k.keyDown(ev("c", "KeyC", { ctrlKey: true }), true).meta & k.META_CTRL) === k.META_CTRL);

  console.log("androidKeys.ts — the mouse");
  check("left, right and middle go as Android's buttons", same(k.mouseButtons(1 | 2 | 4, false), { held: 7, back: false, forward: false }));
  check("the side buttons are Back and Forward", same(k.mouseButtons(8 | 16, false), { held: 0, back: true, forward: true }));
  check("Shift with the right button is Back, as the desktop's old binding", same(k.mouseButtons(2, true), { held: 0, back: true, forward: false }));
  check("Shift with the left button stays a click (Shift-click)", same(k.mouseButtons(1, true), { held: 1, back: false, forward: false }));
  const [h, v] = k.wheelNotches(0, 64, 0);
  check("64 points down the wheel is one notch down (negative, Android's way)", h === 0 && v === -1, `${h} ${v}`);
  check("sideways is positive to the right, as the browser's", k.wheelNotches(32, 0, 0)[0] === 0.5);
  check("a line-mode wheel counts lines", k.wheelNotches(0, -3, 1)[1] === (3 * 40) / 64);

  console.log("latency.ts");
  const l = await import(pathToFileURL(path.join(USB, "latency.ts")).href);
  const c = new l.FrameClock();
  check("no age before the phone's clock is known", c.age(1_000_000, 5) === null && !c.known);
  // The phone's clock runs 10 000 ms ahead of this page's; pings of 4, 20 and 2 ms.
  const phone = (pageMs) => (pageMs + 10_000) * 1000;
  c.pong(100, 104, phone(102));
  c.pong(200, 220, phone(203)); // a slow one, read off-centre: must not win
  c.pong(300, 302, phone(301));
  // A frame the phone stamped at page time 1000, shown at 1012.
  const age = c.age(phone(1000), 1012);
  check("a frame's age is now less its stamp, on the shortest ping's clock", Math.abs(age - 12) < 0.01, String(age));
  c.pong(400, 400.5, "x");
  check("a pong without the phone's clock changes nothing", Math.abs(c.age(phone(1000), 1012) - 12) < 0.01);
  check("the median of a second's samples", l.median([5, 1, 9, 3, 7]) === 5 && l.median([]) === null);
}
