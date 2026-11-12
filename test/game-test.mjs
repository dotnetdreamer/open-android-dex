#!/usr/bin/env node
// Game mode's phone half (doc/game-mode.md), checked on a phone or emulator
// over adb, with nothing of the computer's app running:
//
//   node test/game-test.mjs [serial] [app A] [app B]
//
// With no serial it runs only where adb sees exactly one device. An app is
// `package` or `package/activity`. A defaults to Files (DocumentsUI), B to the
// first calculator the phone has. Pushes the window daemon's dex
// (openandroiddex-wmd/openandroiddex-wmd.dex, build it first) to a path of its
// own, runs the server as the app does, opens A and B in two windows and sends
// what the computer sends:
//
// - a game controller's state (0x25) on a window: the phone makes a gamepad
//   for it, tied to that window's display, so its B button closes that
//   window's app even while the other window's display has the focus; a
//   report on the other window moves it there; blur lets it go, and a report
//   after the blur is dropped until the next focus; the window's end, the
//   control connection's "gone" and the server's stop take it away; and a
//   server killed with kill -9 leaves a tie that the next one takes back;
// - game controls' fingers (0x24): three fingers held together, two on the
//   calculator's 7 and 8 (8 let go first: "87"), a mouse move among them
//   dropped, twenty fingers with ids past Android's last (16 held at most,
//   all let go, nothing left down), blur lifting them;
// - a second connection taking a window while the first is open: the phone
//   lets go of the first one's finger and hat, closes it, and takes the second's;
//   a mouse press held as a third takes it stays down until its own release.
//
// Never a report to a window it did not open, and never a pad left free: the
// server makes each one tied to a display that does not exist first. The
// tasks the windows made are taken out of the phone's recent apps at the end;
// the dex, the forwards and the servers go; and the phone is checked for any
// pad, tie, display or marker of ours left. About ninety seconds.
//
// Reads One UI's dispatcher lines in logcat ("Delivering touch to … d=N") where
// the phone prints them; elsewhere those checks say they could not be made.
// Needs Node 18+ and the Android SDK's adb.

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
const PHONE_DEX = "/data/local/tmp/oadx-game-test.dex";
const [serialArg, appA, appB] = process.argv.slice(2);
const PAD_NAME = "Microsoft X-Box 360 pad";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
// adb on Windows hands `shell`'s output back with CRLF line ends, with a pty or without (`exec-out` does
// not): taken back to LF, or every parse anchored at a line's end would miss there.
const adb = (...a) => (spawnSync(ADB, adbArgs(...a), { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }).stdout || "").replace(/\r\n/g, "\n");
const sh = (cmd) => adb("shell", cmd);

// Never on whatever device adb picks: with more than one, the serial is asked for.
if (!serialArg) {
  const listed = (spawnSync(ADB, ["devices"], { encoding: "utf8" }).stdout || "").split("\n").filter((l) => /\tdevice\s*$/.test(l));
  if (listed.length !== 1) {
    console.error(
      listed.length
        ? `${listed.length} devices are connected (${listed.map((l) => l.split("\t")[0]).join(", ")}): say which, node test/game-test.mjs <serial>.`
        : "No device is connected.",
    );
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
    this.name = "oadx-game-test-" + this.suffix;
    this.lines = [];
    this.child = spawn(
      ADB,
      adbArgs("shell", `trap '' HUP; CLASSPATH=${PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer ${this.name} --no-warm-up`),
      { stdio: ["pipe", "pipe", "inherit"] },
    );
    this.child.stdin.on("error", () => {}); // a server already gone
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
  said(re) {
    return this.lines.filter((l) => re.test(l));
  }
  pid() {
    const m = sh(`ps -A -o PID,ARGS | grep ${this.name} | grep -v grep`).trim().match(/^(\d+)/);
    return m ? Number(m[1]) : null;
  }
  unforward() {
    if (this.port) spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
    this.port = 0;
  }
  async done() {
    try {
      this.child.stdin.write("stop\n");
    } catch {}
    this.unforward();
    const until = Date.now() + 6000;
    while (Date.now() < until && this.pid()) await sleep(200);
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
        const m = { kind: this.buf[0], data: this.buf.subarray(5, 5 + n) };
        // The picture is not kept: only the window's word is read here.
        if (m.kind === 0x01) this.queue.push(m);
        else if (m.kind === 0x02) this.frames = (this.frames || 0) + 1;
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
  /** A game controller's state: 0x25, slot, the 13-byte report. */
  pad(slot, report) {
    this.send(0x25, Buffer.concat([Buffer.from([slot]), report]));
  }
  /** Exactly these fingers are down: 0x24, a count, then id u8, x u16, y u16 each. */
  touch(fingers) {
    const b = Buffer.alloc(1 + 5 * fingers.length);
    b[0] = fingers.length;
    fingers.forEach(([id, x, y], i) => {
      b[1 + 5 * i] = id;
      b.writeUInt16BE(Math.round(x), 2 + 5 * i);
      b.writeUInt16BE(Math.round(y), 4 + 5 * i);
    });
    this.send(0x24, b);
  }
  /** The mouse, as the computer sends it: 0x20 buttons, x, y, meta. */
  pointer(buttons, x, y) {
    const b = Buffer.alloc(9);
    b[0] = buttons;
    b.writeUInt16BE(x, 1);
    b.writeUInt16BE(y, 3);
    b.writeUInt32BE(0, 5);
    this.send(0x20, b);
  }
  async read(ms) {
    const until = Date.now() + ms;
    while (!this.queue.length && !this.ended && Date.now() < until) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(Math.max(1, until - Date.now()))]);
    }
    return this.queue.shift() ?? null;
  }
  /** The next JSON message for which `want` is true, within `ms`; { ended } if the connection went. */
  async until(want, ms) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return this.ended ? { ended: true } : null;
      const v = JSON.parse(m.data.toString());
      if (want(v)) return v;
    }
    return null;
  }
  close() {
    this.socket.destroy();
  }
}

/** A report: buttons, hat (0 centred, 1 up … 5 down … 7 left), left stick. */
function report({ buttons = 0, hat = 0, lx = 0, ly = 0 } = {}) {
  const b = Buffer.alloc(13);
  b.writeUInt16LE(buttons, 0);
  b[2] = hat;
  b.writeInt16LE(lx, 3);
  b.writeInt16LE(ly, 5);
  return b;
}
const NEUTRAL = report();
const BTN_B = 0x0002;

// ── what the phone says ──

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

/** The phone's input, as far as our pads go: the pads of `suffix`, their ties, the displays' unique ids. */
function inputState(suffix) {
  const dump = sh("dumpsys input");
  const hub = blocks(dump, PAD_NAME).filter((b) => /Location: oadx-pad:/.test(b));
  const pads = hub
    .filter((b) => (suffix ? b.includes(`oadx-pad:${suffix}:`) : true))
    .map((b) => ({
      port: (b.match(/Location: (\S+)/) || [])[1],
      enabled: /Enabled: true/.test(b),
      xbox: /Vendor_045e_Product_028e\.kl/.test(b),
      gamepad: /Classes:.*GAMEPAD/.test(b),
      hatY: Number((b.match(/ABS_HAT0Y=(-?\d+)/) || [])[1] ?? NaN),
    }));
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
  return { pads, ties, unique, focused, ourTies: Object.keys(ties).filter((p) => p.startsWith("oadx-pad:")), dump };
}

/** `want` of `inputState` true within `ms`. */
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

/** The focused view's id in `pkg`'s window (dumpsys activity: the second flag group's F). */
function focusedView(pkg) {
  const dump = sh(`dumpsys activity ${pkg}`);
  const m = dump.match(/\{[0-9a-f]+ \S{9} .F\S* [^}]*?(app:id\/[A-Za-z_0-9]+)/);
  return m ? m[1] : "(none)";
}

/** Where view `idRe` is in `pkg`'s window, in the display's pixels: its centre, from the view hierarchy's nested bounds. */
function viewCentre(pkg, idRe) {
  const dump = sh(`dumpsys activity ${pkg}`);
  const start = dump.indexOf("View Hierarchy:");
  if (start < 0) return null;
  const lines = dump.slice(dump.lastIndexOf("\n", start) + 1).split("\n");
  const top = lines[0].match(/^\s*/)[0].length;
  const stack = [];
  for (const line of lines.slice(1)) {
    if (line.trim() && line.match(/^\s*/)[0].length <= top) break;
    const m = line.match(/^(\s*)\S+\{[0-9a-f]+ \S+ \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(.*)\}/);
    if (!m) continue;
    const depth = m[1].length;
    while (stack.length && stack[stack.length - 1].depth >= depth) stack.pop();
    const parent = stack.length ? stack[stack.length - 1] : { x: 0, y: 0 };
    const x = parent.x + Number(m[2]);
    const y = parent.y + Number(m[3]);
    stack.push({ depth, x, y });
    if (idRe.test(m[6])) return { x: x + (Number(m[4]) - Number(m[2])) / 2, y: y + (Number(m[5]) - Number(m[3])) / 2 };
  }
  return null;
}

/** The dispatcher's touches to `display` since `since`, as One UI logs them: their actions, in order. */
function touchesLogged(since, display) {
  const out = sh(`logcat -d -T '${since}' -s InputDispatcher 2>/dev/null`);
  const actions = [];
  for (const line of out.split("\n")) {
    const m = line.match(/Delivering touch to \(\d+\): action: (0x[0-9a-f]+),.* d=(\d+)/);
    if (m && Number(m[2]) === display) actions.push(m[1]);
  }
  return actions;
}
/** The phone's clock to the millisecond, as `logcat -T` takes it (a whole second would let earlier touches in). */
const phoneTime = () => {
  const t = sh("date +'%m-%d %H:%M:%S.%N'").trim();
  return /\.\d{4,}$/.test(t) ? t.replace(/(\.\d{3})\d+$/, "$1") : sh("date +'%m-%d %H:%M:%S.000'").trim();
};

/** The text of the calculator's formula, read from the focused display's window by uiautomator, or null. */
function formulaText() {
  const file = "/data/local/tmp/oadx-game-test-ui.xml";
  sh(`uiautomator dump ${file} >/dev/null 2>&1`);
  const xml = sh(`cat ${file} 2>/dev/null`);
  sh(`rm -f ${file}`);
  const m = xml.match(/text="([^"]*)"[^>]*resource-id="[^"]*(?:edt_formula|formula)"/) || xml.match(/resource-id="[^"]*(?:edt_formula|formula)"[^>]*text="([^"]*)"/);
  return m ? m[1] : null;
}

// ── the apps ──

const CALCULATORS = ["com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"];
const installed = new Set(sh("pm list packages").split("\n").map((l) => l.replace("package:", "").trim()));
const app = (s) => {
  const [pkg, activity = ""] = s.split("/");
  return { pkg, activity };
};
const A = appA ? app(appA) : { pkg: "com.google.android.documentsui", activity: "com.android.documentsui.files.FilesActivity" };
const B = appB ? app(appB) : { pkg: CALCULATORS.find((p) => installed.has(p)) || "", activity: "" };
if (!installed.has(A.pkg) || !installed.has(B.pkg)) {
  console.error(`Needs ${A.pkg || "Files"} and ${B.pkg || "a calculator"} on the phone (give two others: node test/game-test.mjs <serial> <pkg> <pkg>).`);
  process.exit(2);
}
if (!fs.existsSync(DEX)) {
  console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.cmd or build.sh, or npm run apk).`);
  process.exit(2);
}

const servers = [];
const tasks = new Set();
const sdkLevel = Number(sh("getprop ro.build.version.sdk").trim());

async function openWindow(server, tag, which) {
  const w = server.connect();
  const key = randomBytes(12).toString("hex");
  w.json({ t: "open", pkg: which.pkg, activity: which.activity, w: 720, h: 1280, dpi: 320, fps: 30, onClose: "close", direct: false, key });
  const opened = await w.until((v) => v.t === "display" || v.t === "state", 20000);
  if (!opened || opened.t !== "display") throw new Error(`${tag}: no display: ${JSON.stringify(opened)}`);
  const task = await w.until((v) => v.t === "task" || v.t === "state", 8000);
  if (task && task.t === "task") tasks.add(task.task);
  w.json({ t: "focus" });
  await sleep(1500);
  return { w, display: opened.id, pkg: which.pkg, tag, key };
}

/** The window's own end, said by the phone within `ms`: "closed", or null if it stayed. */
async function closes(win, ms) {
  const v = await win.w.until((m) => m.t === "state", ms);
  return v ? (v.ended ? "ended" : v.state) : null;
}

/** Tap an empty part of a window's display, so its display has Android's focus. */
async function focusByTap(win, x = 20, y = 1250) {
  sh(`input -d ${win.display} tap ${x} ${y}`);
  await sleep(900);
}

async function main() {
  adb("push", DEX, PHONE_DEX);
  const before = inputState(null);
  check("no pad or tie of ours on the phone before", before.pads.length === 0 && before.ourTies.length === 0, `${before.pads.length} pads, ${before.ourTies.length} ties`);
  const markersBefore = sh("ls /data/local/tmp").split("\n").filter((l) => l.startsWith("oadx-pads-"));

  const s1 = new Server();
  servers.push(s1);
  await s1.ready();
  console.log(`server ${s1.name} on port ${s1.port}; Android API ${sdkLevel}; A = ${A.pkg}, B = ${B.pkg}`);
  const control = s1.connect();
  control.json({ t: "control" });
  await control.until((v) => v.t === "hello", 5000);

  const a = await openWindow(s1, "A", A);
  const b = await openWindow(s1, "B", B);
  console.log(`A on display ${a.display}, B on display ${b.display}`);

  // ── M1: the pads ──
  console.log("── game controllers ──");
  await focusByTap(b);
  // The first report on A's window: a pad made, tied to A's display.
  a.w.pad(0, NEUTRAL);
  let st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].enabled);
  const port0 = `oadx-pad:${s1.suffix}:0`;
  for (let i = 0; i < 20 && !s1.said(/game controller 0 made/).length; i++) await sleep(50);
  const madeLine = s1.said(/game controller 0 made/)[0] || "";
  check("a report on A makes a pad on the phone", st.pads.length === 1 && st.pads[0].port === port0, JSON.stringify(st.pads));
  check("…an Xbox 360 pad to Android: its key layout, a gamepad", st.pads[0]?.xbox && st.pads[0]?.gamepad);
  check("…tied to A's display", st.ties[port0] === st.unique[a.display], `${st.ties[port0]} vs display ${a.display}: ${st.unique[a.display]}`);
  note(madeLine.replace(/^INFO: apps: /, ""));
  check("B's display has the focus meanwhile", st.focused === b.display, `focused ${st.focused}`);

  // Hat down on B's window: the same pad, re-pointed to B; the calculator's focus moves.
  const viewBefore = focusedView(b.pkg);
  b.w.pad(0, report({ hat: 5 }));
  await sleep(250);
  b.w.pad(0, NEUTRAL);
  await sleep(500);
  b.w.pad(0, report({ hat: 5 }));
  await sleep(250);
  b.w.pad(0, NEUTRAL);
  await sleep(700);
  const viewAfter = focusedView(b.pkg);
  st = inputState(s1.suffix);
  check("a report on B moves the pad to B's display", st.ties[port0] === st.unique[b.display], st.ties[port0]);
  check("…the same pad, not a second one", st.pads.length === 1 && s1.said(/game controller 0 made/).length === 1);
  check("…and the hat moves the calculator's focus", viewAfter !== viewBefore, `${viewBefore} → ${viewAfter}`);

  // B on A's window, while B's display has the focus: A closes, B stays.
  await focusByTap(b);
  const focusedThen = inputState(s1.suffix).focused;
  a.w.pad(0, report({ buttons: BTN_B }));
  await sleep(120);
  a.w.pad(0, NEUTRAL);
  const saidA = await closes(a, 4000);
  const saidB = await closes(b, 400);
  check("B pressed on A's window closes A, while B's display has the focus", saidA === "closed" && focusedThen === b.display, `A said ${saidA}, focused ${focusedThen}`);
  check("…and B stays open", saidB === null, `B said ${saidB}`);
  st = await waitInput(s1.suffix, (s) => s.pads.length === 0);
  check("A's end takes its pad and its tie away", st.pads.length === 0 && !st.ties[port0], `${st.pads.length} pads, tie ${st.ties[port0] ?? "none"}`);

  // A pad on B; blur lets it go.
  b.w.pad(0, report({ hat: 5 }));
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].hatY === 1);
  check("a new report on B makes a pad tied to B, the hat held", st.pads.length === 1 && st.pads[0].hatY === 1 && st.ties[port0] === st.unique[b.display], `hat ${st.pads[0]?.hatY}`);
  b.w.json({ t: "blur" });
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].hatY === 0);
  check("blur on B lets the pad go (the hat centred), still tied", st.pads[0]?.hatY === 0 && st.ties[port0] === st.unique[b.display], `hat ${st.pads[0]?.hatY}`);
  // A report that crossed the blur: dropped until the window's next focus.
  b.w.pad(0, report({ hat: 5 }));
  await sleep(700);
  st = inputState(s1.suffix);
  check("…and a report after the blur is dropped until the window's focus", st.pads[0]?.hatY === 0, `hat ${st.pads[0]?.hatY}`);
  b.w.json({ t: "focus" });
  b.w.pad(0, NEUTRAL);

  // A second controller, and the control connection's word that it went.
  b.w.pad(1, NEUTRAL);
  st = await waitInput(s1.suffix, (s) => s.pads.length === 2);
  check("a second controller is a second pad", st.pads.length === 2, st.pads.map((p) => p.port).join(", "));
  control.json({ t: "gamepad", slot: 1, on: false });
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1);
  check("…which goes when the computer says it went", st.pads.length === 1 && st.pads[0].port === port0 && !st.ties[`oadx-pad:${s1.suffix}:1`]);

  // ── M2: fingers ──
  console.log("── fingers ──");
  b.w.json({ t: "focus" });
  await focusByTap(b);
  // Three fingers held together on the formula's part of the calculator, which none of them presses.
  let since = phoneTime();
  await sleep(1100);
  const f = (id, x, y) => [id, x, y];
  const steps = [
    [f(1, 200, 150)],
    [f(1, 200, 150), f(2, 400, 150)],
    [f(1, 210, 160), f(2, 410, 160)],
    [f(1, 210, 160), f(2, 410, 160), f(3, 300, 300)],
    [f(1, 220, 170), f(2, 420, 170), f(3, 310, 310)],
    [f(1, 220, 170), f(3, 310, 310)],
    [f(3, 310, 310)],
    [],
  ];
  for (const s of steps) {
    b.w.touch(s);
    await sleep(40);
  }
  await sleep(800);
  let acts = touchesLogged(since, b.display);
  if (acts.length) {
    check("three fingers: DOWN, POINTER_DOWN ×2, POINTER_UP ×2, UP, in order", JSON.stringify(acts) === JSON.stringify(["0x0", "0x105", "0x205", "0x106", "0x6", "0x1"]), acts.join(" "));
  } else {
    note("this phone does not log the dispatcher's touches: their order is not read here");
  }

  // A finger held on 7 while another taps 8: buttons act on release, so "87".
  const seven = viewCentre(b.pkg, /btn_0?7\b|digit_7\b/);
  const eight = viewCentre(b.pkg, /btn_0?8\b|digit_8\b/);
  const clear = viewCentre(b.pkg, /btn_clear\b|\bclr\b/);
  if (seven && eight) {
    if (clear) {
      b.w.touch([f(1, clear.x, clear.y)]);
      await sleep(60);
      b.w.touch([]);
      await sleep(400);
    }
    since = phoneTime();
    await sleep(1100);
    b.w.touch([f(1, seven.x, seven.y)]);
    await sleep(80);
    b.w.touch([f(1, seven.x, seven.y), f(2, eight.x, eight.y)]);
    await sleep(80);
    b.w.touch([f(1, seven.x, seven.y)]);
    await sleep(80);
    b.w.touch([]);
    await sleep(900);
    acts = touchesLogged(since, b.display);
    if (acts.length) check("7 held, 8 tapped: DOWN, POINTER_DOWN, POINTER_UP, UP", JSON.stringify(acts) === JSON.stringify(["0x0", "0x105", "0x106", "0x1"]), acts.join(" "));
    const text = formulaText();
    if (text !== null) check("…and the calculator's formula reads 87", text.replace(/[^0-9]/g, "") === "87", JSON.stringify(text));
    else note("the calculator's formula could not be read here (uiautomator saw no formula)");
  } else {
    note(`no 7 and 8 found in ${b.pkg}'s window: the "87" check is not made`);
  }

  // A mouse move among the fingers: dropped by the phone, the fingers' run whole.
  since = phoneTime();
  await sleep(1100);
  b.w.touch([f(1, 200, 150)]);
  await sleep(40);
  b.w.pointer(0, 60, 60);
  await sleep(40);
  b.w.touch([f(1, 205, 160)]);
  await sleep(40);
  b.w.touch([]);
  await sleep(800);
  acts = touchesLogged(since, b.display);
  if (acts.length) check("a mouse move among the fingers: the fingers' DOWN and UP, nothing else", JSON.stringify(acts) === JSON.stringify(["0x0", "0x1"]), acts.join(" "));
  const refused = s1.said(/input refused/).length;
  check("no input was refused", refused === 0, `${refused} refusals`);

  // More fingers than Android takes in one event (16), with ids past its last
  // pointer id (31): the phone gives each the lowest pointer id free and holds
  // no more than 16; all let go, nothing is left down.
  since = phoneTime();
  await sleep(1100);
  const many = Array.from({ length: 20 }, (_, i) => f(40 + i, 40 + 30 * i, 140 + (i % 2) * 50));
  b.w.touch(many.slice(0, 5));
  await sleep(60);
  b.w.touch(many);
  await sleep(150);
  b.w.touch(many.slice(3));
  await sleep(100);
  b.w.touch([]);
  await sleep(900);
  acts = touchesLogged(since, b.display);
  if (acts.length) {
    // The action's low byte: DOWN 0 and POINTER_DOWN 5 put a finger down, UP 1 and POINTER_UP 6 lift one.
    // (Fingers over two of the app's windows reach each as a stream of its own: each ends in its UP.)
    let held = 0;
    let most = 0;
    let downs = 0;
    for (const a of acts) {
      const act = parseInt(a, 16) & 0xff;
      if (act === 0 || act === 5) {
        held++;
        downs++;
      } else if (act === 1 || act === 6) {
        held--;
      }
      most = Math.max(most, held);
    }
    check("twenty fingers, ids 40 to 59: at most 16 down at once, each one let go, no cancel", most <= 16 && held === 0 && downs === 19 && acts.at(-1) === "0x1" && !acts.includes("0x3"), `${most} at most, ${downs} down, ${held} left: ${acts.join(" ")}`);
  }
  // Nothing left down: the next finger is a gesture's first.
  since = phoneTime();
  await sleep(1100);
  b.w.touch([f(1, 200, 150)]);
  await sleep(60);
  b.w.touch([]);
  await sleep(700);
  acts = touchesLogged(since, b.display);
  if (acts.length) check("…and the next finger is the first of a new gesture: DOWN, UP", JSON.stringify(acts) === JSON.stringify(["0x0", "0x1"]), acts.join(" "));
  check("…none of it was refused by Android", s1.said(/touch refused|input refused/).length === 0, s1.said(/touch refused|input refused/)[0] ?? "");

  // Blur with two fingers down: both lifted.
  since = phoneTime();
  await sleep(1100);
  b.w.touch([f(1, 200, 150), f(2, 400, 150)]);
  await sleep(80);
  b.w.json({ t: "blur" });
  await sleep(800);
  acts = touchesLogged(since, b.display);
  // Lifted in ascending id: the first finger's POINTER_UP (index 0), then the other's UP.
  if (acts.length) check("blur with two fingers down lifts both", JSON.stringify(acts) === JSON.stringify(["0x0", "0x105", "0x6", "0x1"]), acts.join(" "));

  // ── a connection made again ──
  // The computer attaches to B on a second connection while the first is still
  // open (it noticed a stall first): the phone lets go of what the first held —
  // a finger, the controller's hat — before it reads the second.
  console.log("── a new connection ──");
  b.w.json({ t: "focus" });
  await focusByTap(b);
  since = phoneTime();
  await sleep(1100);
  b.w.touch([f(1, 200, 150)]);
  b.w.pad(0, report({ hat: 5 }));
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].hatY === 1);
  check("held on the first connection: a finger and the hat", st.pads[0]?.hatY === 1, `hat ${st.pads[0]?.hatY}`);
  const w2 = s1.connect();
  w2.json({ t: "attach", key: b.key, w: 720, h: 1280, dpi: 320, direct: false });
  const att = await w2.until((v) => v.t === "attached" || v.t === "state", 8000);
  check("a second connection takes the window", att?.t === "attached" && att.display === b.display, JSON.stringify(att));
  const old = b.w;
  b.w = w2;
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].hatY === 0);
  await sleep(600);
  acts = touchesLogged(since, b.display);
  const oldClosed = await old.until(() => false, 1500).then(() => old.ended);
  check("…and the phone lets go of what the first held: the hat centred, the finger up", st.pads[0]?.hatY === 0 && (!acts.length || JSON.stringify(acts) === JSON.stringify(["0x0", "0x1"])), `hat ${st.pads[0]?.hatY}; ${acts.join(" ") || "no dispatcher lines"}`);
  check("…and closes the first", oldClosed);
  old.close();
  // The computer sends its whole state on the new one: no fingers, the hat. The window is as good as new.
  since = phoneTime();
  await sleep(1100);
  b.w.touch([]);
  b.w.pad(0, report({ hat: 5 }));
  st = await waitInput(s1.suffix, (s) => s.pads.length === 1 && s.pads[0].hatY === 1);
  b.w.touch([f(1, 200, 150)]);
  await sleep(60);
  b.w.touch([]);
  await sleep(700);
  acts = touchesLogged(since, b.display);
  check("the new connection's state goes: the hat held again, a tap a tap", st.pads[0]?.hatY === 1 && (!acts.length || JSON.stringify(acts) === JSON.stringify(["0x0", "0x1"])), `hat ${st.pads[0]?.hatY}; ${acts.join(" ")}`);
  b.w.pad(0, NEUTRAL);
  await sleep(200);

  // An ordinary mouse press — a drag — held across a connection made again: only game
  // controls start clean, so the press stays down until the computer lets it go on the new one.
  since = phoneTime();
  await sleep(1100);
  b.w.pointer(1, 200, 150);
  await sleep(300);
  const w3 = s1.connect();
  w3.json({ t: "attach", key: b.key, w: 720, h: 1280, dpi: 320, direct: false });
  const att3 = await w3.until((v) => v.t === "attached" || v.t === "state", 8000);
  b.w.close();
  b.w = w3;
  await sleep(800);
  // Down, up and cancel only: a mouse's button press and release events come with them.
  const pressOnly = (list) => list.filter((a) => ["0x0", "0x1", "0x3"].includes(a));
  const held = pressOnly(touchesLogged(since, b.display));
  b.w.pointer(0, 200, 150);
  await sleep(700);
  acts = pressOnly(touchesLogged(since, b.display));
  if (acts.length) {
    check("a mouse press held across a connection made again is not let go by it, only by its release", att3?.t === "attached" && JSON.stringify(held) === JSON.stringify(["0x0"]) && JSON.stringify(acts) === JSON.stringify(["0x0", "0x1"]), `held: ${held.join(" ")}; after the release: ${acts.join(" ")}`);
  } else {
    note("this phone does not log the dispatcher's mouse presses: the press across a new connection is not read here");
  }

  // ── the pads' end ──
  console.log("── the end of a window and of the server ──");
  b.w.json({ t: "close", app: "close" });
  await closes(b, 4000);
  st = await waitInput(s1.suffix, (s) => s.pads.length === 0);
  check("closing B takes its pad away", st.pads.length === 0 && !st.ties[port0]);
  const marker1 = `oadx-pads-${s1.suffix}`;
  const markerWhileUp = sh(`cat /data/local/tmp/${marker1} 2>/dev/null`).trim();
  check("the server's marker names its pid and its ports", /^\d+\n/.test(markerWhileUp) && markerWhileUp.includes(port0), JSON.stringify(markerWhileUp));
  a.w.close();
  b.w.close();
  control.close();
  await s1.done();
  st = inputState(s1.suffix);
  check("stop leaves no pad, no tie, no marker", st.pads.length === 0 && st.ourTies.length === 0 && !sh("ls /data/local/tmp").includes(marker1), `${st.ourTies.join(", ") || "no ties"}`);

  // A server killed with kill -9: its pad goes with it, its tie stays; the next server takes the tie back.
  const s2 = new Server();
  servers.push(s2);
  await s2.ready();
  const c = await openWindow(s2, "C", B);
  c.w.pad(0, NEUTRAL);
  const port2 = `oadx-pad:${s2.suffix}:0`;
  st = await waitInput(s2.suffix, (s) => s.pads.length === 1 && !!s.ties[port2]);
  const pid2 = s2.pid();
  note(`server 2 is pid ${pid2}: ${sh(`cat /proc/${pid2}/cmdline | tr '\\0' ' '`).trim()}`);
  sh(`kill -9 ${pid2}`);
  c.w.close();
  s2.unforward();
  await sleep(1500);
  st = inputState(s2.suffix);
  const marker2 = `oadx-pads-${s2.suffix}`;
  check("kill -9: the pad goes with the server, its tie and marker stay", st.pads.length === 0 && !!st.ties[port2] && sh("ls /data/local/tmp").includes(marker2), `tie ${st.ties[port2] ?? "none"}`);
  // The killed server's display went: Android put its app on the phone's own screen. Out of the way again.
  for (const id of tasks) sh(`am stack remove ${id} 2>/dev/null`);
  const s3 = new Server();
  servers.push(s3);
  await s3.ready();
  st = inputState(null);
  check("the next server takes the tie back and the marker away", !st.ties[port2] && !sh("ls /data/local/tmp").includes(marker2), s3.said(/took back/)[0]?.replace(/^INFO: apps: /, "") ?? "nothing said");
  await s3.done();

  const markersAfter = sh("ls /data/local/tmp").split("\n").filter((l) => l.startsWith("oadx-pads-"));
  check("no marker of ours left", markersAfter.length <= markersBefore.length, markersAfter.join(", ") || "none");
}

let crashed = null;
try {
  await main();
} catch (e) {
  crashed = e;
  check("the test ran to the end", false, e.stack || String(e));
} finally {
  for (const s of servers) {
    try {
      await s.done();
      if (s.pid()) sh(`kill ${s.pid()}`);
    } catch {}
  }
  await sleep(500);
  const recents = sh("dumpsys activity recents | grep 'Recent #'");
  for (const id of tasks) {
    if (new RegExp(`#${id} `).test(recents)) sh(`am stack remove ${id} 2>/dev/null`);
  }
  sh(`rm -f ${PHONE_DEX}`);
  for (const s of servers) sh(`rm -f /data/local/tmp/oadx-pads-${s.suffix} /data/local/tmp/oadx-pads-${s.suffix}.new`);
  // GAME_TEST_LOG=<file>: the servers' own logs, for a record.
  if (process.env.GAME_TEST_LOG) fs.writeFileSync(process.env.GAME_TEST_LOG, servers.map((s) => `── ${s.name}\n${s.lines.join("\n")}\n`).join(""));
  const left = inputState(null);
  const displays = Number(sh("dumpsys display | grep -c 'Open Android DeX app'").trim());
  const forwards = adb("forward", "--list").split("\n").filter((l) => servers.some((s) => l.includes(s.name)));
  check(
    "the phone is left with no pad, tie, display, forward or dex of the test's",
    left.pads.length === 0 && left.ourTies.length === 0 && displays === 0 && forwards.length === 0 && !sh("ls /data/local/tmp").includes("oadx-game-test"),
    `${left.pads.length} pads, ${left.ourTies.length} ties, ${displays} displays, ${forwards.length} forwards`,
  );
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed || crashed ? 1 : 0);
