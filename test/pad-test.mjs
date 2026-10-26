#!/usr/bin/env node
// The phone's half of its screen as the touchpad of its apps' windows on the
// computer (doc/phone-touchpad.md, "The computer's windows"), checked on a
// phone or an emulator over adb, with nothing of the app's running:
//
//   node test/pad-test.mjs [serial]
//
// Needs this build's launcher on the phone (a desktop session installs it, or
// the app does when a window's More asks for the pad) and the window daemon's
// dex (openandroiddex-wmd/openandroiddex-wmd.dex, built by its build script or
// `npm run apk`). Runs the app server from that dex under app_process, as the
// app does, and speaks its control wire: the pad offered, then put up — the
// launcher let draw over other apps for it, and that taken back after — then
// real touches on the phone's screen, read back as what reaches the computer:
// one finger by `input` (a slide as moves, a tap as a press and a release, a
// finger held still as a right click, the pad's ◁ as Back), and two and three
// by the dex's own Probe (`fingers`), which `input` cannot do (a scroll, a
// right click, the window gestures); Settings in a window of its own keeping
// the pad off the phone's screen, as Android does every app's overlay, and the
// pad saying so (Android 15 and later), but not the shade or a screen gone
// dark; something put in front on the phone's screen putting the pad away; ✕
// closing it; and the pad withdrawn.
//
// Uses the app's own adb (resources/bin) when there is one; ADB=… overrides it.

import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const exe = process.platform === "win32" ? "adb.exe" : "adb";
const bundled = path.join(here, "..", "open-android-dex-tauri", "src-tauri", "resources", "bin", exe);
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), "Library", "Android", "sdk");
const ADB = process.env.ADB || (fs.existsSync(bundled) ? bundled : path.join(sdk, "platform-tools", exe));
const DEX = path.join(here, "..", "openandroiddex-wmd", "openandroiddex-wmd.dex");
const PHONE_DEX = "/data/local/tmp/oadx-pad-test.dex";
const LAUNCHER = "com.ccrstech.openandroiddex.launcher";
const serialArg = process.argv[2];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";
const sh = (cmd) => adb("shell", cmd);

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
}

/** The phone's app server, as host.rs starts it, on a socket of its own. */
class Server {
  constructor() {
    this.name = "oadx-pad-test-" + randomBytes(4).toString("hex");
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
    this.exited = new Promise((r) => this.child.on("exit", r));
  }
  async ready() {
    if (!(await this.waitFor("apps ready", 15000))) throw new Error("the server did not start: " + this.lines.join(" | "));
    this.port = Number(adb("forward", "tcp:0", `localabstract:${this.name}`).trim());
  }
  async waitFor(text, ms, from = 0) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      if (this.lines.slice(from).some((l) => l.includes(text))) return true;
      await sleep(100);
    }
    return false;
  }
  done() {
    spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
  }
}

/** The control connection, framed as Wire.java frames: type, u32 length, bytes. */
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
        if (this.buf[0] === 0x01) this.queue.push(JSON.parse(this.buf.subarray(5, 5 + n).toString()));
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
  /** The next message `want` takes, skipping others; null if none in `ms`. */
  async next(want, ms = 10000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const i = this.queue.findIndex(want);
      if (i >= 0) return this.queue.splice(i, 1)[0];
      if (this.ended) return null;
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(Math.max(0, until - Date.now()))]);
    }
    return null;
  }
  /** Every message `want` takes within `ms`, waiting that long. */
  async all(want, ms) {
    await sleep(ms);
    const got = this.queue.filter(want);
    this.queue = this.queue.filter((m) => !want(m));
    return got;
  }
  close() {
    this.socket.destroy();
  }
}

const pad = (k) => (m) => m.t === "pad" && (!k || m.k === k);
const overlay = () => sh(`appops get ${LAUNCHER} SYSTEM_ALERT_WINDOW`).trim();
const overlayAllowed = () => overlay().includes("SYSTEM_ALERT_WINDOW: allow");

/** Whether the pad's window has gone from the phone's screen, its closing animation done, within `ms`. */
async function padGone(ms = 1500) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (!padFrame()) return true;
    await sleep(150);
  }
  return !padFrame();
}

/** Whether Android is keeping the pad's window off the screen for a window that asks it to. */
function padForceHidden() {
  const dump = sh("dumpsys window windows");
  const at = dump.indexOf("Touchpad}");
  return at >= 0 && dump.slice(at, at + 4000).includes("mForceHideNonSystemOverlayWindow=true");
}

/** Whether a toast is on the phone's screen within `ms`: SystemUI draws one for a text toast. */
async function toastWithin(ms) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (/Window\{[0-9a-f]+ u0 Toast\}/.test(sh("dumpsys window windows"))) return true;
    await sleep(200);
  }
  return false;
}

/** The pad's window on the phone's screen, as the window manager has it: its frame, or null. */
function padFrame() {
  const dump = sh("dumpsys window windows");
  const at = dump.indexOf("Touchpad}");
  if (at < 0) return null;
  const m = dump.slice(at, at + 4000).match(/\b[mM]?[Ff]rame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  return m ? { left: +m[1], top: +m[2], right: +m[3], bottom: +m[4] } : null;
}

async function main() {
  if (!fs.existsSync(DEX)) throw new Error(`no ${DEX}: build the daemon first (openandroiddex-wmd/build.cmd or build.sh)`);
  const sdkLevel = Number(adb("shell", "getprop", "ro.build.version.sdk").trim());
  console.log(`phone: ${adb("shell", "getprop", "ro.product.model").trim()}, API ${sdkLevel}`);
  if (sdkLevel < 30) {
    console.log("the phone's touchpad needs Android 11: nothing to check");
    return;
  }
  if (!adb("shell", "pm", "path", LAUNCHER).includes("package:")) throw new Error("no launcher on the phone: open the desktop once, or a window's More → Use phone as touchpad");
  const density = Number((sh("wm density").match(/(\d+)\s*$/m) ?? [])[1] || 160) / 160;
  const dp = (v) => Math.round(v * density);
  // The pad's grant is checked from a launcher not let draw over other apps; what it had is put back at the end.
  const overlayBefore = overlay();
  sh(`appops set ${LAUNCHER} SYSTEM_ALERT_WINDOW default`);
  spawnSync(ADB, adbArgs("push", DEX, PHONE_DEX));
  sh("input keyevent KEYCODE_WAKEUP");
  sh("wm dismiss-keyguard");
  sh("input keyevent KEYCODE_HOME");

  const server = new Server();
  await server.ready();
  const control = new Wire(server.port);
  control.json({ t: "control" });
  check("the server says hello", !!(await control.next((m) => m.t === "hello")));

  // ── offered ──
  control.json({ t: "padOn", open: false });
  const offer = await control.next((m) => m.t === "padOn");
  check("the pad is offered", offer?.ok === true && offer.open === false, JSON.stringify(offer));
  check("…the launcher let draw it over other apps", overlayAllowed(), overlay());
  check("…on a port only the launcher gets through: anyone else is refused", await (async () => {
    const port = Number((server.lines.join("\n").match(/pad: listening on 127\.0\.0\.1:(\d+)/) ?? [])[1]);
    if (!port) return false;
    // Through an adb forward, which reaches it as the shell — not the launcher.
    const local = Number(adb("forward", "tcp:0", `tcp:${port}`).trim());
    const from = server.lines.length;
    const s = net.connect(local, "127.0.0.1");
    s.on("error", () => {});
    s.write('{"t":"pad","token":"not this one"}\n{"t":"move","dx":500,"dy":0}\n');
    await sleep(800);
    s.destroy();
    spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${local}`));
    const moved = await control.next(pad("move"), 500);
    return server.lines.slice(from).some((l) => l.includes("pad: refused a connection from uid 2000")) && !moved;
  })());

  // ── up ──
  control.json({ t: "padOn", open: true });
  const up = await control.next((m) => m.t === "padOn");
  check("the pad goes up on the phone", up?.ok === true && up.open === true, JSON.stringify(up));
  const opened = await control.next(pad("open"), 8000);
  check("…and connects to the server, which tells the computer", !!opened);
  await sleep(600);
  const frame = padFrame();
  check("…a window over the phone's screen", !!frame, JSON.stringify(frame));
  if (!frame) throw new Error("no pad to touch");
  const cx = Math.round((frame.left + frame.right) / 2);
  const cy = Math.round((frame.top + frame.bottom) / 2);

  // ── what the fingers do ──
  sh(`input swipe ${cx - dp(80)} ${cy} ${cx + dp(80)} ${cy} 400`);
  const moves = await control.all(pad("move"), 1200);
  const dx = moves.reduce((s, m) => s + m.dx, 0);
  const dy = moves.reduce((s, m) => s + m.dy, 0);
  check("a slide moves the computer's pointer: as far right as the finger went", moves.length > 0 && dx > 20 && Math.abs(dy) < dx / 4,
    `${moves.length} moves, dx ${dx.toFixed(1)}, dy ${dy.toFixed(1)}`);
  const presses = (await control.all(pad("buttons"), 300)).map((m) => m.b);
  check("…and presses nothing", presses.length === 0, JSON.stringify(presses));

  sh(`input tap ${cx} ${cy}`);
  const tap = (await control.all(pad("buttons"), 900)).map((m) => m.b);
  check("a tap is a click: the primary button down, then up", JSON.stringify(tap) === "[1,0]", JSON.stringify(tap));

  // Held still past the long-press: a right click, as the finger lifts.
  sh(`input swipe ${cx} ${cy} ${cx} ${cy} 900`);
  const held = (await control.all(pad("buttons"), 900)).map((m) => m.b);
  check("a finger held still is a right click", JSON.stringify(held) === "[2,0]", JSON.stringify(held));

  // Two fingers: `input` has one, so the dex's Probe puts them down, as the shell.
  const fingers = (n, x1, y1, x2, y2, ms) =>
    sh(`CLASSPATH=${PHONE_DEX} app_process / com.ccrstech.openandroiddex.wmd.Probe fingers ${n} ${x1} ${y1} ${x2} ${y2} ${ms} ${dp(60)}`);
  const put = fingers(2, cx, cy - dp(80), cx, cy + dp(80), 500);
  const wheel = await control.all(pad("wheel"), 1500);
  const v = wheel.reduce((s, m) => s + m.v, 0);
  const moved = (await control.all(pad("move"), 100)).length;
  check("two fingers slid down scroll, in notches, and move no pointer", wheel.length > 0 && Math.abs(v) >= 1 && moved === 0,
    `${put.trim()}: ${wheel.length} wheel messages, ${v.toFixed(2)} notches, ${moved} moves`);
  // Two fingers tapped: a right click.
  fingers(2, cx, cy, cx, cy, 60);
  const twoTap = (await control.all(pad("buttons"), 900)).map((m) => m.b);
  check("two fingers tapped are a right click", JSON.stringify(twoTap) === "[2,0]", JSON.stringify(twoTap));
  // Three: Settings → Touchpad's gestures, as they come out of the box (right: next window,
  // left: previous, a tap: Back; up and down are the desktop's own panels, nothing here).
  fingers(3, cx - dp(70), cy, cx + dp(70), cy, 300);
  const right = await control.next(pad("act"), 2000);
  check("three fingers to the right: the next of the phone's windows here", right?.action === "nextwindow", JSON.stringify(right));
  fingers(3, cx + dp(70), cy, cx - dp(70), cy, 300);
  const left = await control.next(pad("act"), 2000);
  check("…to the left: the previous one", left?.action === "prevwindow", JSON.stringify(left));
  fingers(3, cx, cy, cx, cy, 60);
  const threeTap = await control.next(pad("key"), 2000);
  check("three fingers tapped: Back", threeTap?.code === 4, JSON.stringify(threeTap));
  fingers(3, cx, cy + dp(70), cx, cy - dp(70), 300);
  const upward = await control.all((m) => m.t === "pad" && (m.k === "act" || m.k === "key" || m.k === "buttons"), 900);
  check("…and up, the desktop’s own Apps, reaches nothing here", upward.length === 0, JSON.stringify(upward));

  // The pad's ◁, alone on its bottom row for the computer's windows.
  sh(`input tap ${cx} ${frame.bottom - dp(8) - dp(30)}`);
  const back = await control.next(pad("key"), 2000);
  check("the pad's ◁ is Back", back?.code === 4, JSON.stringify(back));

  // ── kept off the screen ──
  // Android keeps every app's overlay off the screen while Settings, or a screen like it,
  // shows anywhere — in a window of the computer's too. Android 15 lets the pad see that.
  if (sdkLevel >= 35) {
    const win = new Wire(server.port);
    win.json({ t: "open", pkg: "com.android.settings", activity: "", w: 400, h: 800, dpi: 160, fps: 30, onClose: "close", direct: false, key: randomBytes(12).toString("hex") });
    const display = await win.next((m) => m.t === "display", 15000);
    const off = await control.next(pad("hidden"), 8000);
    check("Settings in a window of its own: Android keeps the pad off the phone's screen, and the pad says so",
      !!display && off?.on === true && padForceHidden(), `${JSON.stringify(off)}, force-hidden ${padForceHidden()}`);
    // A toast from an app in the background that may not post notifications is dropped by Android.
    if (/POST_NOTIFICATIONS: granted=true/.test(sh(`dumpsys package ${LAUNCHER}`))) {
      check("…and the phone says why", await toastWithin(3000));
    } else {
      console.log("(the launcher may not post notifications here, which keeps its toast off the phone too: not checked)");
    }
    win.json({ t: "close", app: "close" });
    const on = await control.next(pad("hidden"), 8000);
    check("…and that it is back once the window closes", on?.on === false && !padForceHidden(), JSON.stringify(on));
    win.close();
    // What only covers the pad, or takes the whole screen away, is not that.
    sh("cmd statusbar expand-notifications");
    const shade = await control.next(pad("hidden"), 4000);
    sh("cmd statusbar collapse");
    check("the shade pulled down over the pad is not taken for it", !shade, JSON.stringify(shade));
    sh("input keyevent KEYCODE_SLEEP");
    const dark = await control.next(pad("hidden"), 3000);
    sh("input keyevent KEYCODE_WAKEUP");
    sh("wm dismiss-keyguard");
    const lit = await control.next(pad("hidden"), 4000);
    check("…nor the phone's screen going dark and lit again", !dark && !lit, JSON.stringify(dark ?? lit));
  } else {
    console.log("(below Android 15 the pad cannot see Android keep it off the screen: not checked)");
  }

  // ── out of the way ──
  // Something put in front on the phone's screen — Settings, as a call or the shade's app would be.
  sh("am start -W -a android.settings.SETTINGS");
  const away = await control.next(pad("closed"), 4000);
  check("an app put in front on the phone's screen puts the pad away", !!away && (await padGone()));
  sh("input keyevent KEYCODE_HOME");

  // Up again, and ✕.
  control.json({ t: "padOn", open: true });
  await control.next((m) => m.t === "padOn");
  await control.next(pad("open"), 8000);
  await sleep(600);
  const again = padFrame();
  if (again) sh(`input tap ${again.right - dp(12) - dp(24)} ${again.top + dp(4) + dp(26)}`);
  const closed = await control.next(pad("closed"), 3000);
  check("the pad's ✕ closes it, and the computer hears", !!again && !!closed && (await padGone()));

  // ── withdrawn ──
  control.json({ t: "padOn", open: true });
  await control.next((m) => m.t === "padOn");
  await control.next(pad("open"), 8000);
  control.json({ t: "padOff" });
  check("withdrawn: the pad goes from the phone", await padGone(5000));
  await sleep(500);
  check("…and the launcher no longer draws over other apps: as it was", !overlayAllowed(), overlay());
  control.json({ t: "padOn", open: false });
  await control.next((m) => m.t === "padOn");
  const lines = server.lines.length;
  control.json({ t: "stop" });
  await Promise.race([server.exited, sleep(6000)]);
  check("the server stopping withdraws it too", server.lines.slice(lines).some((l) => l.includes("pad: no longer offered (the server is stopping)")));

  control.close();
  server.done();
  // What the launcher had before, back.
  const was = (overlayBefore.match(/SYSTEM_ALERT_WINDOW: (\w+)/) ?? [])[1];
  if (was) sh(`appops set ${LAUNCHER} SYSTEM_ALERT_WINDOW ${was}`);
  adb("shell", "rm", "-f", PHONE_DEX);
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
