#!/usr/bin/env node
// Shortcut keys in an app's window (doc/apps-on-pc.md, "Input"), checked on a
// phone or emulator over adb, with nothing of the computer's app running:
//
//   node test/apps-keys-test.mjs [serial]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex)
// to a path of its own, runs the server as the app does, opens Settings'
// search in a window and sends it what the computer sends: typed text as
// text, a shortcut as Android's key code with its modifiers in the meta.
//
// Ctrl, Alt and Shift must reach the app as keys of their own as well, around
// the key they came with, as a keyboard's do: an app that counts what is held
// from those keys (a remote desktop, TeamViewer) saw an A alone. That order is
// read from what the phone's input dispatcher last dispatched, which only a
// debuggable build lists in full (an emulator's google_apis image, not a
// Play Store one). Anywhere, the shortcuts must do what they do: Ctrl+A and
// Ctrl+C put the field's text on the computer's clipboard, and End, Shift+Left
// over the last word and Ctrl+C that word — which needs Shift's own key, as a
// text field reads Shift from it, not from the arrow's meta (before this, it
// selected nothing). About twenty seconds. Needs Node 18+ and the Android SDK's adb.

import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), process.platform === "win32" ? "AppData/Local/Android/Sdk" : "Library/Android/sdk");
const ADB = path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
const DEX = path.join(here, "..", "openandroiddex-wmd", "openandroiddex-wmd.dex");
const PHONE_DEX = "/data/local/tmp/oadx-apps-keys-test.dex";
const serialArg = process.argv[2];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
}

// Android's key codes and meta bits, the ones this sends.
const KEY = { A: 29, C: 31, DEL: 67, LEFT: 21, MOVE_END: 123, SHIFT_LEFT: 59, ALT_LEFT: 57, CTRL_LEFT: 113 };
const META = { SHIFT: 0x41, ALT: 0x12, CTRL: 0x3000 };
const MODIFIER_KEYS = new Set([KEY.SHIFT_LEFT, 60, KEY.ALT_LEFT, 58, KEY.CTRL_LEFT, 114]);

/** The phone's app server, as host.rs starts it, on a socket of its own. */
class Server {
  constructor() {
    this.name = "oadx-test-" + randomBytes(4).toString("hex");
    this.lines = [];
    this.child = spawn(
      ADB,
      adbArgs("shell", `trap '' HUP; CLASSPATH=${PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer ${this.name} --no-warm-up`),
      { stdio: ["pipe", "pipe", "inherit"] },
    );
    let rest = "";
    this.child.stdout.on("data", (b) => {
      const parts = (rest + b.toString()).split("\n");
      rest = parts.pop();
      for (const l of parts) this.lines.push(l.trimEnd());
    });
  }
  async ready() {
    const until = Date.now() + 15000;
    while (Date.now() < until && !this.lines.some((l) => l.includes("apps ready"))) await sleep(100);
    if (!this.lines.some((l) => l.includes("apps ready"))) throw new Error("the server did not start: " + this.lines.join(" | "));
    this.port = Number(adb("forward", "tcp:0", `localabstract:${this.name}`).trim());
  }
  connect() {
    return new Wire(this.port);
  }
  done() {
    this.child.stdin.write("stop\n");
    spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
  }
}

/** One connection to the server, framed as Wire.java frames. */
class Wire {
  constructor(port) {
    this.socket = net.connect(port, "127.0.0.1");
    this.buf = Buffer.alloc(0);
    this.queue = [];
    this.waiting = null;
    this.ended = false;
    this.socket.on("data", (d) => {
      this.buf = Buffer.concat([this.buf, d]);
      while (this.buf.length >= 5) {
        const n = this.buf.readUInt32BE(1);
        if (this.buf.length < 5 + n) break;
        this.queue.push({ kind: this.buf[0], data: this.buf.subarray(5, 5 + n) });
        this.buf = this.buf.subarray(5 + n);
      }
      this.wake();
    });
    const end = () => {
      this.ended = true;
      this.wake();
    };
    this.socket.on("end", end);
    this.socket.on("close", end);
    this.socket.on("error", end);
  }
  wake() {
    if (this.waiting) {
      const w = this.waiting;
      this.waiting = null;
      w();
    }
  }
  send(kind, body) {
    const head = Buffer.alloc(5);
    head[0] = kind;
    head.writeUInt32BE(body.length, 1);
    this.socket.write(Buffer.concat([head, body]));
  }
  json(v) {
    this.send(0x01, Buffer.from(JSON.stringify(v)));
  }
  /** A key as the computer sends it: down or up, Android's code, Android's meta. */
  key(down, code, meta = 0) {
    const b = Buffer.alloc(8);
    b[0] = down ? 1 : 0;
    b[1] = 0;
    b.writeUInt16BE(code, 2);
    b.writeUInt32BE(meta, 4);
    this.send(0x22, b);
  }
  /** Characters typed. */
  text(s) {
    this.send(0x23, Buffer.from(s));
  }
  async read(ms = 15000) {
    const until = Date.now() + ms;
    while (!this.queue.length && !this.ended && Date.now() < until) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(until - Date.now())]);
    }
    return this.queue.shift() ?? null;
  }
  /** The next JSON message of type `t`, skipping the picture and anything else. */
  async until(t, ms = 15000, also = () => true) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return null;
      if (m.kind !== 0x01) continue;
      const v = JSON.parse(m.data.toString());
      if (v.t === t && also(v)) return v;
    }
    return null;
  }
  /** The window's first picture: a video message that is not the encoder's set-up alone. */
  async frame(ms = 15000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return false;
      if (m.kind === 0x02 && m.data.length > 9 && (m.data[0] & 2) === 0) return true;
    }
    return false;
  }
  /** Reads and drops whatever came, so a window's pictures never back up. */
  drain() {
    this.queue.length = 0;
  }
  close() {
    this.socket.destroy();
  }
}

/** The key events the phone's input dispatcher last dispatched to `display`, oldest first (debuggable builds only). */
function recentKeys(display) {
  const dump = adb("shell", "dumpsys input");
  const at = dump.indexOf("RecentQueue:");
  if (at < 0) return [];
  const block = dump.slice(at, dump.indexOf("PendingEvent", at));
  const keys = [];
  for (const line of block.split("\n")) {
    const m = line.match(/KeyEvent\(.*?displayId=(\d+).*?action=(\w+).*?keyCode=\S*?\((\d+)\).*?metaState=0x([0-9a-f]+)/i);
    if (m && Number(m[1]) === display) keys.push({ action: m[2], code: Number(m[3]), meta: parseInt(m[4], 16) });
  }
  return keys;
}

const say = (keys) => keys.map((k) => `${k.action === "DOWN" ? "↓" : "↑"}${k.code}/0x${k.meta.toString(16)}`).join(" ");

/** `keys` ends with `want`, each a [action, code, meta-must-have, meta-must-not-have]. */
function endsWith(keys, want) {
  const tail = keys.slice(-want.length);
  return (
    tail.length === want.length &&
    want.every(([action, code, has = 0, hasNot = 0], i) => tail[i].action === action && tail[i].code === code && (tail[i].meta & has) === has && (tail[i].meta & hasNot) === 0)
  );
}

if (!fs.existsSync(DEX)) {
  console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.cmd or build.sh, or npm run apk).`);
  process.exit(2);
}
const debuggable = adb("shell", "getprop ro.debuggable").trim() === "1";
const resolved = adb("shell", "cmd package resolve-activity --brief -a com.android.settings.action.SETTINGS_SEARCH").trim().split(/\r?\n/).pop() ?? "";
const [pkg, cls] = resolved.trim().split("/");
if (!pkg || !cls) {
  console.error("No Settings search on this phone: " + resolved);
  process.exit(2);
}
const activity = cls.startsWith(".") ? pkg + cls : cls;

adb("push", DEX, PHONE_DEX);
const server = new Server();
await server.ready();
console.log(`server ${server.name} on port ${server.port}; ${pkg}/${activity}; ${debuggable ? "debuggable: the key order is read" : "not debuggable: the key order cannot be read, only what the keys did"}`);

const control = server.connect();
control.json({ t: "control" });

const w = server.connect();
w.json({ t: "open", pkg, activity, w: 720, h: 1280, dpi: 320, fps: 30, onClose: "close", direct: false, key: randomBytes(12).toString("hex") });
const opened = await w.until("display");
check("Settings' search opens in a window", typeof opened?.id === "number", JSON.stringify(opened));
const display = opened?.id ?? -1;
check("…and shows: its picture comes", await w.frame(15000));
w.json({ t: "focus" });
await sleep(2500); // the search field takes the focus as it opens

/** A shortcut as the computer sends it: the key down and up, its modifiers in both metas. */
async function shortcut(code, meta) {
  w.key(true, code, meta);
  await sleep(40);
  w.key(false, code, meta);
  await sleep(350);
}

/** What the phone reports copied within `ms`: a copy can be reported twice (One UI, Android 16). */
async function copies(ms = 2500) {
  const got = [];
  const until = Date.now() + ms;
  for (;;) {
    const c = await control.until("clip", until - Date.now());
    if (!c) return got;
    got.push(c.text);
  }
}

// Search may open with an earlier query still in its field: that goes first.
await shortcut(KEY.A, META.CTRL);
await shortcut(KEY.DEL, 0);
const word = "world" + randomBytes(2).toString("hex");
w.text(`hello ${word}`);
await sleep(800);

await shortcut(KEY.A, META.CTRL);
if (debuggable) {
  const keys = recentKeys(display);
  check(
    "Ctrl+A reaches the app as Ctrl down, A down, A up, Ctrl up",
    endsWith(keys, [["DOWN", KEY.CTRL_LEFT, 0x1000], ["DOWN", KEY.A, 0x1000], ["UP", KEY.A, 0x1000], ["UP", KEY.CTRL_LEFT, 0, 0x1000]]),
    say(keys.slice(-6)),
  );
}
await shortcut(KEY.C, META.CTRL);
const all = await copies();
check("…and Ctrl+A, Ctrl+C still copy the field's text", all.length > 0 && all.every((t) => t === `hello ${word}`), JSON.stringify(all));

// The cursor to the end, then Shift+Left five times: the last five letters selected.
await shortcut(KEY.MOVE_END, 0);
for (let i = 0; i < word.length; i++) {
  w.key(true, KEY.LEFT, META.SHIFT);
  await sleep(30);
  w.key(false, KEY.LEFT, META.SHIFT);
  await sleep(60);
}
await sleep(300);
if (debuggable) {
  const keys = recentKeys(display);
  check(
    "Shift+Left reaches the app as Shift down, Left down, Left up, Shift up",
    endsWith(keys, [["DOWN", KEY.SHIFT_LEFT, 0x1], ["DOWN", KEY.LEFT, 0x1], ["UP", KEY.LEFT, 0x1], ["UP", KEY.SHIFT_LEFT, 0, 0x1]]),
    say(keys.slice(-6)),
  );
}
await shortcut(KEY.C, META.CTRL);
const last = await copies();
// A text field selects with Shift+Left only once Shift's own key has gone down
// (ArrowKeyMovementMethod reads the field's Shift state, not the arrow's meta).
check("Shift+Left selects: Ctrl+C copies the last word", last.length > 0 && last.every((t) => t === word), JSON.stringify(last));

if (debuggable) {
  // Ctrl let go before A: the computer's A up comes without it, and Ctrl goes up first.
  w.key(true, KEY.A, META.CTRL);
  await sleep(40);
  w.key(false, KEY.A, 0);
  await sleep(350);
  let keys = recentKeys(display);
  check(
    "Ctrl let go before A: Ctrl up, then A up",
    endsWith(keys, [["DOWN", KEY.CTRL_LEFT, 0x1000], ["DOWN", KEY.A, 0x1000], ["UP", KEY.CTRL_LEFT, 0, 0x1000], ["UP", KEY.A, 0, 0x1000]]),
    say(keys.slice(-6)),
  );

  // Two modifiers: pressed in order, let go the other way round.
  w.key(true, KEY.A, META.CTRL | META.ALT);
  await sleep(40);
  w.key(false, KEY.A, META.CTRL | META.ALT);
  await sleep(350);
  keys = recentKeys(display);
  check(
    "Ctrl+Alt+A: Ctrl down, Alt down, A, Alt up, Ctrl up",
    endsWith(keys, [
      ["DOWN", KEY.CTRL_LEFT, 0x1000, 0x2],
      ["DOWN", KEY.ALT_LEFT, 0x1002],
      ["DOWN", KEY.A, 0x1002],
      ["UP", KEY.A, 0x1002],
      ["UP", KEY.ALT_LEFT, 0x1000, 0x2],
      ["UP", KEY.CTRL_LEFT, 0, 0x1002],
    ]),
    say(keys.slice(-7)),
  );

  // Typing goes on with nothing held: H brings its own Shift, and i none.
  w.text("Hi");
  await sleep(500);
  keys = recentKeys(display);
  check(
    "typed text after the shortcuts: only the characters' own Shift",
    endsWith(keys, [["DOWN", KEY.SHIFT_LEFT, 0x1], ["DOWN", 36, 0x1], ["UP", 36, 0x1], ["UP", KEY.SHIFT_LEFT], ["DOWN", 37, 0, 0x3013], ["UP", 37, 0, 0x3013]]) &&
      !keys.slice(-6).some((k) => MODIFIER_KEYS.has(k.code) && k.code !== KEY.SHIFT_LEFT),
    say(keys.slice(-7)),
  );

  // The window loses the keyboard with Ctrl+Shift+Left held: everything is let go, once.
  w.key(true, KEY.LEFT, META.CTRL | META.SHIFT);
  await sleep(100);
  w.json({ t: "blur" });
  await sleep(400);
  keys = recentKeys(display);
  const after = keys.slice(keys.findLastIndex((k) => k.action === "DOWN" && k.code === KEY.LEFT) + 1);
  const ups = after.filter((k) => k.action === "UP").map((k) => k.code).sort((a, b) => a - b);
  check(
    "blur with Ctrl+Shift+Left held lets go of all three, once each",
    JSON.stringify(ups) === JSON.stringify([KEY.LEFT, KEY.SHIFT_LEFT, KEY.CTRL_LEFT].sort((a, b) => a - b)) && after.every((k) => k.action === "UP"),
    say(after),
  );
}

const refused = server.lines.filter((l) => l.includes("input refused")).length;
check("no input was refused", refused === 0, `${refused} refusals`);

w.json({ t: "close", app: "close" }); // the app ended, not handed back to the phone's own screen
w.close();
control.close();
await sleep(1500);
server.done();
await sleep(1000);
adb("shell", `rm -f ${PHONE_DEX}`);
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
