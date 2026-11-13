#!/usr/bin/env node
// The phone's half of its screen as a game controller for its apps' windows on
// the computer (doc/game-mode.md, "The phone as a controller"), checked on a
// phone or an emulator over adb, with nothing of the app's running:
//
//   node test/controller-test.mjs [serial] [app A] [app B]
//
// Needs this build's launcher on the phone (a desktop session installs it, or
// the app does when a window's More asks for the controller) and the window
// daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex: its build script, or
// `npm run apk`). An app is `package` or `package/activity`: A defaults to
// Files (DocumentsUI), which B closes, B to the first calculator the phone has,
// or else its clock. Runs the app server as the app does, opens A and B in two
// windows, and speaks the computer's side of the wire:
//
// - offered: the launcher let draw over other apps, a connection from anyone
//   but the launcher refused, and the notification up only while game mode is
//   on (where the launcher may post one);
// - up: a window over the phone's screen, landscape, connected, playing the
//   window last in front — and the window that comes in front after;
// - real taps on the phone's screen: its B is a gamepad's B in the window in
//   front, a pad of the phone's own (slot 4) made for it, which closes Files
//   there — on Android 12 and older only once that window's display has been
//   pressed (the window told to say so first); a slide on its left stick is the
//   pad's stick, back in the middle as the finger lifts;
// - its ✕ closing it, its pad going with it; withdrawn, the window gone and the
//   launcher's drawing over other apps as it was; the server stopping
//   withdrawing it too.
//
// The windows' tasks are taken out of the phone's recent apps at the end; the
// dex, the forwards and the server go; the launcher's drawing over other apps
// is put back as it was. About a minute.

import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const exe = process.platform === "win32" ? "adb.exe" : "adb";
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), process.platform === "win32" ? "AppData/Local/Android/Sdk" : "Library/Android/sdk");
const ADB = process.env.ADB || path.join(sdk, "platform-tools", exe);
const DEX = path.join(here, "..", "openandroiddex-wmd", "openandroiddex-wmd.dex");
const PHONE_DEX = "/data/local/tmp/oadx-controller-test.dex";
const LAUNCHER = "com.ccrstech.openandroiddex.launcher";
const PAD_NAME = "Microsoft X-Box 360 pad";
/** The phone's own controller's slot (AppGamepads.PHONE): past the computer's four. */
const PHONE_SLOT = 4;
const [serialArg, appA, appB] = process.argv.slice(2);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
// adb on Windows hands `shell`'s output back with CRLF: taken back to LF.
const adb = (...a) => (spawnSync(ADB, adbArgs(...a), { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).stdout || "").replace(/\r\n/g, "\n");
const sh = (cmd) => adb("shell", cmd);

if (!serialArg) {
  const listed = (spawnSync(ADB, ["devices"], { encoding: "utf8" }).stdout || "").split(/\r?\n/).filter((l) => /\tdevice\s*$/.test(l));
  if (listed.length !== 1) {
    console.error(listed.length ? `${listed.length} devices are connected: say which, node test/controller-test.mjs <serial>.` : "No device is connected.");
    process.exit(2);
  }
}

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
}
const note = (s) => console.log(`     ${s}`);

/** The phone's app server, as host.rs starts it, on a socket of its own. */
class Server {
  constructor() {
    this.suffix = randomBytes(4).toString("hex");
    this.name = "oadx-controller-test-" + this.suffix;
    this.lines = [];
    this.child = spawn(
      ADB,
      adbArgs("shell", `trap '' HUP; CLASSPATH=${PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer ${this.name} --no-warm-up`),
      { stdio: ["pipe", "pipe", "inherit"] },
    );
    this.child.stdin.on("error", () => {});
    let rest = "";
    this.child.stdout.on("data", (b) => {
      const parts = (rest + b.toString()).split("\n");
      rest = parts.pop();
      for (const l of parts) this.lines.push(l.trimEnd());
    });
    this.exited = new Promise((r) => this.child.on("exit", r));
  }
  async ready() {
    if (!(await this.waitFor(/apps ready/, 15000))) throw new Error("the server did not start: " + this.lines.join(" | "));
    this.port = Number(adb("forward", "tcp:0", `localabstract:${this.name}`).trim());
  }
  said(re, from = 0) {
    return this.lines.slice(from).filter((l) => re.test(l));
  }
  async waitFor(re, ms, from = 0) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const got = this.said(re, from);
      if (got.length) return got[got.length - 1];
      await sleep(100);
    }
    return null;
  }
  connect() {
    return new Wire(this.port);
  }
  unforward() {
    if (this.port) spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
    this.port = 0;
  }
}

/** One connection to the server, framed as Wire.java frames: type, u32 length, bytes. JSON kept until asked for. */
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
        if (this.buf[0] === 0x01) {
          try {
            this.queue.push(JSON.parse(this.buf.subarray(5, 5 + n).toString()));
          } catch {}
        }
        this.buf = this.buf.subarray(5 + n);
      }
      this.wake();
    });
    const end = () => {
      this.ended = true;
      this.wake();
    };
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
  json(v) {
    const body = Buffer.from(JSON.stringify(v));
    const head = Buffer.alloc(5);
    head[0] = 0x01;
    head.writeUInt32BE(body.length, 1);
    this.socket.write(Buffer.concat([head, body]));
  }
  /** The next message `want` takes, skipping (and keeping) others; null if none in `ms`, { ended } if the connection went. */
  async next(want, ms = 10000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const i = this.queue.findIndex(want);
      if (i >= 0) return this.queue.splice(i, 1)[0];
      if (this.ended) return { ended: true };
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(Math.max(0, until - Date.now()))]);
    }
    return null;
  }
  close() {
    this.socket.destroy();
  }
}

// ── what the phone says ──

const overlay = () => (sh(`cmd appops get ${LAUNCHER} SYSTEM_ALERT_WINDOW`).match(/SYSTEM_ALERT_WINDOW: (\w+)/) || [])[1] ?? "default";

/** The controller's window on the phone's screen: its frame, or null. */
function padFrame() {
  const dump = sh("dumpsys window windows");
  const at = dump.indexOf("Game controller}");
  if (at < 0) return null;
  const m = dump.slice(at, at + 4000).match(/\b[mM]?[Ff]rame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
  return m ? { left: +m[1], top: +m[2], right: +m[3], bottom: +m[4] } : null;
}

async function padGone(ms = 2000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (!padFrame()) return true;
    await sleep(150);
  }
  return !padFrame();
}

/** The phone's own screen's rotation, as the input system has it: 0…3. */
function rotation() {
  const m = sh("dumpsys input").match(/Viewport INTERNAL: displayId=0,[^\n]*?orientation=(\d)/);
  return m ? Number(m[1]) : NaN;
}

/** The controller's last layout, as it logged it: its size (landscape) and each control's point in its window. */
function layout() {
  const lines = sh("logcat -d -s OpenDeX").split("\n").filter((l) => l.includes("[gamepad] laid out"));
  const l = lines[lines.length - 1];
  if (!l) return null;
  const size = l.match(/laid out (\d+)x(\d+)/) || [];
  const at = { w: Number(size[1]), h: Number(size[2]) };
  for (const m of l.matchAll(/\b(A|B|X|Y|pad|L|R|back|guide|start|lb|rb|lt|rt|close) (\d+),(\d+)/g)) at[m[1]] = { x: +m[2], y: +m[3] };
  at.stick = Number((l.match(/ stick (\d+)/) || [])[1]);
  at.turned = / turned:/.test(l);
  return at;
}

/** Whether a layout is the one for the controller's window as it is now: the screen may turn after it opens. */
function settled(frame, at) {
  const fw = frame.right - frame.left;
  const fh = frame.bottom - frame.top;
  return at.turned ? at.w === fh && at.h === fw : at.w === fw && at.h === fh;
}

/**
 * Null while the controller's window is on the screen; else why not — Android keeping every app's
 * overlay off it for a window that asks (Settings; Files on Android 16), named where the dump says.
 */
function hiddenBy() {
  // Locked, or dark: a tap there is the lock screen's, whose corner is its emergency call (an S25 run
  // opened the emergency dialer this way; nothing was dialled).
  const locked = locks();
  if (locked) return locked;
  const dump = sh("dumpsys window windows");
  const at = dump.indexOf("Game controller}");
  if (at < 0) return "no controller window";
  if (!dump.slice(at, at + 4000).includes("mForceHideNonSystemOverlayWindow=true")) return null;
  const hider = dump.split(/\n(?=\s*Window #\d+)/).find((b) => /HIDE_NON_SYSTEM_OVERLAY_WINDOWS/.test(b));
  return `kept off the screen for ${hider ? hider.split("\n")[0].trim() : "a window that hides overlays"}`;
}

/** Null while the phone is awake and unlocked; else which it is not. */
function locks() {
  if (!/mWakefulness=Awake/.test(sh("dumpsys power | grep mWakefulness="))) return "the phone's screen is off";
  if (/isKeyguardShowing=true/.test(sh("dumpsys window | grep isKeyguardShowing"))) return "the phone is locked";
  return null;
}

/** Each block of `dump`'s lines whose first line is a device entry naming `name`. */
function blocks(dump, name) {
  const lines = dump.split("\n");
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes(name) || !/^\s*(Device\s+)?-?\d+: /.test(lines[i])) continue;
    const indent = lines[i].match(/^\s*/)[0].length;
    const block = [lines[i]];
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].trim() && lines[j].match(/^\s*/)[0].length <= indent) break;
      block.push(lines[j]);
    }
    out.push(block.join("\n"));
  }
  return out;
}

/** The server's pads on the phone (their ports), its ties, the displays' unique ids, the focused display. */
function inputState(suffix) {
  const dump = sh("dumpsys input");
  const pads = blocks(dump, PAD_NAME)
    .map((b) => (b.match(/Location: (\S+)/) || [])[1])
    .filter((p) => p && p.startsWith(`oadx-pad:${suffix}:`));
  const ties = {};
  const at = dump.indexOf("Unique Id Associations:");
  if (at >= 0) {
    for (const line of dump.slice(at).split("\n").slice(1)) {
      const m = line.match(/^\s*port: (\S+)\s+uniqueId: (.*)$/);
      if (!m) break;
      ties[m[1]] = m[2].trim();
    }
  }
  const unique = {};
  for (const m of dump.matchAll(/Viewport VIRTUAL: displayId=(\d+), uniqueId=(.*?), port=/g)) unique[m[1]] = m[2];
  const focused = Number((dump.match(/FocusedDisplayId:\s*(-?\d+)/) || [])[1] ?? NaN);
  return { pads, ties, unique, focused };
}

async function waitInput(suffix, want, ms = 3000) {
  const until = Date.now() + ms;
  let s;
  do {
    s = inputState(suffix);
    if (want(s)) return s;
    await sleep(150);
  } while (Date.now() < until);
  return s;
}

/** An axis of the pad at `port`, as the kernel has it now (getevent -il): dumpsys prints none below Android 15. */
function axis(port, name) {
  for (const dev of sh("getevent -il 2>/dev/null").replace(/\r\n/g, "\n").split(/^add device /m)) {
    if (!dev.includes(`location: "${port}"`)) continue;
    const m = dev.match(new RegExp(`${name}\\s*:\\s*value (-?\\d+)`));
    return m ? Number(m[1]) : NaN;
  }
  return NaN;
}

const CALCULATORS = ["com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"];
const CLOCKS = ["com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"];
const installed = new Set(sh("pm list packages").split("\n").map((l) => l.replace("package:", "").trim()).filter(Boolean));
const app = (s) => {
  const [pkg, activity = ""] = s.split("/");
  return { pkg, activity };
};
const A = appA ? app(appA) : { pkg: "com.google.android.documentsui", activity: "com.android.documentsui.files.FilesActivity" };
const B = appB ? app(appB) : { pkg: CALCULATORS.find((p) => installed.has(p)) || CLOCKS.find((p) => installed.has(p)) || "", activity: "" };

const tasks = new Set();

async function openWindow(server, tag, which) {
  const w = server.connect();
  const key = randomBytes(12).toString("hex");
  w.json({ t: "open", pkg: which.pkg, activity: which.activity, w: 720, h: 1280, dpi: 320, fps: 30, onClose: "close", direct: false, key });
  const opened = await w.next((v) => v.t === "display" || v.t === "state", 20000);
  if (!opened || opened.t !== "display") throw new Error(`${tag}: no display: ${JSON.stringify(opened)}`);
  const task = await w.next((v) => v.t === "task" || v.t === "state", 8000);
  if (task && task.t === "task") tasks.add(task.task);
  w.json({ t: "focus" });
  await sleep(1500);
  return { w, display: opened.id, pkg: which.pkg, tag };
}

/** The window's own end, said by the phone within `ms`: "closed", or null if it stayed. */
async function closes(win, ms) {
  const v = await win.w.next((m) => m.t === "state", ms);
  return v ? (v.ended ? "ended" : v.state) : null;
}

/** A press on a window's display, cancelled: what gives that display Android's focus (game-test.mjs). */
async function focusByTap(win, x = 20, y = 1250) {
  sh(`input -d ${win.display} motionevent DOWN ${x} ${y}; input -d ${win.display} motionevent CANCEL ${x} ${y}`);
  await sleep(900);
}

async function main() {
  if (!fs.existsSync(DEX)) throw new Error(`no ${DEX}: build the daemon first (openandroiddex-wmd/build.cmd or build.sh, or npm run apk)`);
  const sdkLevel = Number(sh("getprop ro.build.version.sdk").trim());
  console.log(`phone: ${sh("getprop ro.product.model").trim()}, API ${sdkLevel}; A = ${A.pkg}, B = ${B.pkg}`);
  if (sdkLevel < 30) {
    console.log("the phone's game controller needs Android 11: nothing to check");
    return;
  }
  if (!installed.has(LAUNCHER)) throw new Error("no launcher on the phone: open the desktop once, or a window's More → Use phone as game controller");
  if (!installed.has(A.pkg) || !installed.has(B.pkg)) throw new Error(`needs ${A.pkg || "Files"} and ${B.pkg || "a calculator or a clock"} on the phone`);
  // Never on a locked phone: its taps would land on the lock screen. Woken first, and asked to
  // unlock, which a phone with no secure lock does.
  sh("input keyevent KEYCODE_WAKEUP");
  sh("wm dismiss-keyguard");
  await sleep(800);
  const locked = locks();
  if (locked) {
    console.error(`${locked}: unlock it and run again. Nothing was changed on it.`);
    process.exit(2);
  }
  const overlayBefore = overlay();
  sh(`appops set ${LAUNCHER} SYSTEM_ALERT_WINDOW default`);
  spawnSync(ADB, adbArgs("push", DEX, PHONE_DEX));
  sh("input keyevent KEYCODE_WAKEUP");
  sh("wm dismiss-keyguard");
  sh("input keyevent KEYCODE_HOME");
  await sleep(800);
  sh("logcat -c");
  const rotationBefore = rotation();

  const server = new Server();
  let control;
  try {
    await server.ready();
    control = server.connect();
    control.json({ t: "control" });
    check("the server says hello", !!(await control.next((m) => m.t === "hello")));
    const a = await openWindow(server, "A", A);
    const b = await openWindow(server, "B", B);
    note(`A on display ${a.display}, B on display ${b.display}; B was the last in front`);
    const on = (m) => m.t === "controllerOn";
    const said = (k) => (m) => m.t === "controller" && m.k === k;

    // ── offered ──
    control.json({ t: "controllerOn", open: false, notice: false });
    const offer = await control.next(on);
    check("the controller is offered", offer?.ok === true && offer.open === false && offer.launcher === true, JSON.stringify(offer));
    check("…the launcher let draw it over other apps", overlay() === "allow", overlay());
    check("…on a port only the launcher gets through: anyone else is refused", await (async () => {
      const port = Number((server.lines.join("\n").match(/controller: listening on 127\.0\.0\.1:(\d+)/) ?? [])[1]);
      if (!port) return false;
      // Through an adb forward, which reaches it as the shell — not the launcher.
      const local = Number(adb("forward", "tcp:0", `tcp:${port}`).trim());
      const from = server.lines.length;
      const s = net.connect(local, "127.0.0.1");
      s.on("error", () => {});
      s.write('{"t":"gamepad","token":"not this one"}\n{"t":"state","b":2}\n');
      await sleep(1500);
      s.destroy();
      spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${local}`));
      // By its uid; where the kernel will not say whose it is, by the token it does not have.
      const refused = server.said(/controller: (refused a connection from uid 2000|a connection without this run's token, refused)/, from);
      return refused.length > 0 && !server.said(/game controller 4 made/).length;
    })());

    // ── game mode: the notification ──
    // The phone's active notifications, by key (user|package|id|tag|uid): the controller's is 0x7B, 123.
    const notified = () => sh("cmd notification list").split("\n").some((l) => l.includes(`|${LAUNCHER}|123|`));
    control.json({ t: "controllerOn", open: false, notice: true });
    const mode = await control.next(on);
    if (mode?.offered) {
      await sleep(500);
      check("game mode on: the controller's notification is up", mode.ok === true && notified(), JSON.stringify(mode));
      control.json({ t: "controllerOn", open: false, notice: false });
      await control.next(on);
      await sleep(500);
      check("…and down as game mode goes off", !notified());
    } else {
      note(`the launcher may not post notifications here: the notification is not checked (${JSON.stringify(mode)})`);
      control.json({ t: "controllerOn", open: false, notice: false });
      await control.next(on);
    }

    // ── up ──
    const upFrom = server.lines.length;
    control.json({ t: "controllerOn", open: true, notice: false });
    const up = await control.next(on);
    check("the controller goes up on the phone", up?.ok === true && up.open === true, JSON.stringify(up));
    const opened = await control.next(said("open"), 8000);
    check("…connects to the server, which tells the computer", opened?.k === "open", JSON.stringify(opened));
    // The phone's screen turns landscape for it, where the phone lets a window ask, and the controller
    // lays itself out again for the new shape: a moment's work — seconds on a slow emulator.
    let frame = null;
    let at = null;
    // Turned first (where it is not kept off the screen, which no screen turns for), then laid out again.
    for (const until = Date.now() + 6000; Date.now() < until && !(rotation() % 2) && !hiddenBy(); ) await sleep(250);
    for (let i = 0; i < 40; i++) {
      frame = padFrame();
      at = layout();
      if (frame && at && settled(frame, at)) break;
      await sleep(250);
    }
    check("…a window over the phone's screen", !!frame, JSON.stringify(frame));
    if (!frame) throw new Error("no controller to touch");
    check("…laid out for that window, as it says", !!at && !!at.B && !!at.L && settled(frame, at), JSON.stringify({ frame, at }));
    const rot = rotation();
    check("…landscape: the phone's screen turned for it, or the controller drawn turned", rot === 1 || rot === 3 || at?.turned === true, `rotation ${rotationBefore} → ${rot}, turned ${at?.turned}`);
    // Never a tap the controller is not there to take: on a phone in someone's hand it would land on
    // whatever is under it — the home screen, an app.
    const shown = () => {
      const why = hiddenBy();
      if (why) throw new Error(`the controller is not on the phone's screen (${why}): nothing more is tapped`);
    };
    const tapAt = (p) => {
      shown();
      sh(`input tap ${frame.left + p.x} ${frame.top + p.y}`);
    };
    const hidden = hiddenBy();
    check("…on the phone's screen: nothing keeps it off", !hidden, hidden ?? "");
    const plays = await server.waitFor(new RegExp(`controller: plays ${B.pkg.replace(/\./g, "\\.")}`), 4000, upFrom);
    check("…playing the window last in front: B", !!plays, plays ?? server.said(/controller: /, upFrom).join(" | "));

    // ── the window in front ──
    let from = server.lines.length;
    a.w.json({ t: "focus" });
    const playsA = await server.waitFor(new RegExp(`controller: plays ${A.pkg.replace(/\./g, "\\.")}`), 4000, from);
    check("A comes to the front: the controller plays A", !!playsA, playsA ?? "");

    // ── its B, tapped on the phone's screen ──
    // A no longer in front on the computer, which is showing something of its own: the phone's
    // controller is for the phone's games, and still plays A.
    from = server.lines.length;
    a.w.json({ t: "blur" });
    await sleep(600);
    check("A blurred, nothing else of the phone's in front: the controller still plays A", !server.said(/controller: (plays|no window)/, from).length,
      server.said(/controller: /, from).join(" | "));
    await focusByTap(b);
    const focusedThen = inputState(server.suffix).focused;
    // What the dispatcher says from here on is about this press.
    sh("logcat -c");
    tapAt(at.B);
    const made = await server.waitFor(/game controller 4 made/, 4000, from);
    check("its B makes a pad of the phone's own, in a slot of its own (4)", !!made, made ?? server.said(/game controller|controller: /, from).join(" | "));
    const tied = / tied to it/.test(made ?? "");
    const held = await server.waitFor(/game controller 4: the window on display \d+ does not have the phone's focus/, 600, from);
    if (held) {
      // Android 12 and older: a display the shell may not trust has the focus only once pressed.
      const told = await a.w.next((m) => m.t === "gamepad", 3000);
      const stayed = await closes(a, 800);
      check("B on the phone, while B's display has the focus, is held for A: nothing closes", stayed === null && focusedThen === b.display, `A said ${stayed}, focused ${focusedThen}`);
      check("…and A's window is told the controller waits for a click", told?.slot === PHONE_SLOT && told.focus === false, JSON.stringify(told));
      await focusByTap(a);
      tapAt(at.B);
    }
    const saidA = await closes(a, 5000);
    // One UI says where it delivers each key ("Delivering key to (…) … d=N"); AOSP prints nothing. An app
    // that only goes behind on Back stays on its display, and its window open: there, the line is the proof.
    const delivered = sh("logcat -d").split("\n").filter((l) => /Delivering key/.test(l)).map((l) => l.replace(/^.*?Delivering/, "Delivering").slice(0, 180));
    const toA = delivered.filter((l) => new RegExp(`\\bd=${a.display}\\b`).test(l));
    const elsewhere = delivered.filter((l) => /\bd=\d+/.test(l) && !toA.includes(l));
    check(`…and B reaches A's app${tied ? ", the pad tied to A's display" : held ? ", after a press there" : ", the focus moved there"}: ${saidA === "closed" ? "it closes" : "the dispatcher says so"}`,
      saidA === "closed" || (toA.length > 0 && elsewhere.length === 0),
      `A said ${saidA}${delivered.length ? `; ${delivered.slice(-3).join(" | ")}` : "; no dispatcher lines"}`);
    const saidB = await closes(b, 400);
    check("…and B stays open", saidB === null, `B said ${saidB}`);

    // ── the only window left, or B in front ──
    if (saidA !== "closed") {
      from = server.lines.length;
      b.w.json({ t: "focus" });
    }
    const playsB = await server.waitFor(new RegExp(`controller: plays ${B.pkg.replace(/\./g, "\\.")}`), 4000, from);
    check(saidA === "closed" ? "A gone: the controller plays B, the window left" : "B comes to the front: the controller plays B", !!playsB, server.said(/controller: /, from).join(" | "));
    const port = `oadx-pad:${server.suffix}:${PHONE_SLOT}`;
    // A slide on the left stick, held: the pad's stick to the right while it is.
    if (held) await focusByTap(b);
    const reach = at.stick || 100;
    const x0 = frame.left + at.L.x;
    const y0 = frame.top + at.L.y;
    // Along the picture's x: the window's own, or its y where the controller is drawn turned.
    const [x1, y1] = at.turned ? [x0, y0 + Math.round(reach * 0.8)] : [x0 + Math.round(reach * 0.8), y0];
    shown();
    const slide = spawn(ADB, adbArgs("shell", `input swipe ${x0} ${y0} ${x1} ${y1} 3000`));
    const slid = new Promise((r) => slide.on("exit", r));
    let most = NaN;
    for (let i = 0; i < 12; i++) {
      await sleep(250);
      const v = axis(port, "ABS_X");
      if (!Number.isNaN(v) && (Number.isNaN(most) || v > most)) most = v;
    }
    await slid;
    await sleep(400);
    const rest = axis(port, "ABS_X");
    check("its left stick slid right is the pad's stick to the right, while the finger is down", most > 8000, `ABS_X up to ${most}`);
    check("…and back in the middle as it lifts", rest === 0, `ABS_X ${rest}`);
    if (tied) {
      const st = inputState(server.suffix);
      check("…the pad tied to B's display now", st.ties[port] === st.unique[b.display], `${st.ties[port]} vs ${st.unique[b.display]}`);
    }

    // ── ✕ ──
    from = server.lines.length;
    tapAt(at.close);
    const closed = await control.next(said("closed"), 4000);
    check("its ✕ closes it, and the computer hears", closed?.k === "closed" && (await padGone()), JSON.stringify(closed));
    const gone = await server.waitFor(/game controller 4 gone/, 3000, from);
    const st = await waitInput(server.suffix, (s) => !s.pads.includes(port));
    check("…its pad goes from the phone with it: a controller unplugged", !!gone && !st.pads.includes(port), `${gone ?? "not said"}; pads ${JSON.stringify(st.pads)}`);
    // Nothing asks the phone's screen for landscape once it is gone: the window that asked is no
    // longer the orientation's source (one removed after its exit animation stayed it: the doc says).
    let asking = true;
    for (let i = 0; i < 20 && asking; i++) {
      asking = /LastOrientationSource=.*Game controller/.test(sh("dumpsys window displays"));
      if (asking) await sleep(250);
    }
    check("…and nothing asks the phone's screen for landscape any more", !asking);
    // Turned back by the phone itself: a moment's work again. With auto-rotate on, how the phone is
    // held decides, and an emulator's never moves: then only said.
    let rotAfter = rotation();
    for (let i = 0; i < 25 && rotAfter !== rotationBefore; i++) {
      await sleep(200);
      rotAfter = rotation();
    }
    if (sh("settings get system accelerometer_rotation").trim() === "1") {
      note(`auto-rotate is on: the phone's own sensor turns its screen back (rotation ${rotationBefore} → ${rotAfter} 5 s after)`);
    } else {
      check("…and the phone's screen is as it was", rotAfter === rotationBefore || Number.isNaN(rotationBefore), `rotation ${rotationBefore} → ${rotAfter}`);
    }

    // ── withdrawn ──
    control.json({ t: "controllerOn", open: true, notice: false });
    await control.next(on);
    await control.next(said("open"), 8000);
    control.json({ t: "controllerOff" });
    check("withdrawn: the controller goes from the phone", await padGone(5000));
    control.json({ t: "controllerOn", open: false, notice: false });
    await control.next(on);
    from = server.lines.length;
    control.json({ t: "stop" });
    await Promise.race([server.exited, sleep(8000)]);
    check("the server stopping withdraws it too", server.said(/controller: no longer offered \(the server is stopping\)/, from).length > 0);
    // Taken back once nothing needs it: on Android 17 the windows' drag catchers need it as well while one is open.
    await sleep(600);
    check("…and the launcher no longer draws over other apps: as it was", overlay() !== "allow", overlay());
    b.w.close();
  } finally {
    control?.close();
    server.unforward();
    try {
      server.child.stdin.write("stop\n");
    } catch {}
    for (const t of tasks) sh(`am stack remove ${t} 2>/dev/null`);
    sh(`appops set ${LAUNCHER} SYSTEM_ALERT_WINDOW ${overlayBefore}`);
    sh(`rm -f ${PHONE_DEX} /data/local/tmp/oadx-pads-${server.suffix} /data/local/tmp/oadx-pads-${server.suffix}.new`);
  }
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  console.log(`\n${passed} passed, ${failed + 1} failed`);
  process.exit(1);
});
