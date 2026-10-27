#!/usr/bin/env node
// The phone's app server keeping a window whose connection went
// (doc/apps-on-pc.md, "Cable or Wi‑Fi"), checked on a phone or an emulator
// over adb, with nothing of the app's running:
//
//   node test/apps-reconnect-test.mjs [serial]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex,
// built by its build script or `npm run apk`) to a path of its own, runs the
// server under app_process as the app does, and speaks the window wire to it
// through adb forwards: a window that waits for its computer and takes a new
// connection, one taken over by a second, one closed after it came back, an
// app opened on the phone while its window was away, an unknown window, the
// server's lease running out, and "stop". It opens the phone's Settings in
// windows of its own; every one goes back to the phone by the end. About a
// minute, most of it the lease.
//
// Needs Node 18+ and the Android SDK's adb (ANDROID_HOME, or ~/Library/Android/sdk).

import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), "Library", "Android", "sdk");
const ADB = path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
const DEX = path.join(here, "..", "openandroiddex-wmd", "openandroiddex-wmd.dex");
const PHONE_DEX = "/data/local/tmp/oadx-apps-test.dex";
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
    this.exited = new Promise((r) => this.child.on("exit", r));
  }
  async ready() {
    if (!(await this.waitFor("apps ready", 15000))) throw new Error("the server did not start: " + this.lines.join(" | "));
    this.port = Number(adb("forward", "tcp:0", `localabstract:${this.name}`).trim());
  }
  async waitFor(text, ms) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      if (this.lines.some((l) => l.includes(text))) return true;
      await sleep(100);
    }
    return false;
  }
  connect() {
    return new Wire(this.port);
  }
  done() {
    spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
  }
}

/** One connection to the server, framed as Wire.java frames: type, u32 length, bytes. */
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
  json(v) {
    const body = Buffer.from(JSON.stringify(v));
    const head = Buffer.alloc(5);
    head[0] = 0x01;
    head.writeUInt32BE(body.length, 1);
    this.socket.write(Buffer.concat([head, body]));
  }
  /** The next message, or null at the end (or after `ms`). */
  async read(ms = 15000) {
    const until = Date.now() + ms;
    while (!this.queue.length && !this.ended && Date.now() < until) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(until - Date.now())]);
    }
    return this.queue.shift() ?? null;
  }
  /** The next JSON message of type `t` (or a `state`), and whether video came before it. */
  async until(t, ms = 15000) {
    const until = Date.now() + ms;
    let video = false;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return { v: null, video };
      if (m.kind === 0x02) {
        video = true;
        continue;
      }
      if (m.kind === 0x01) {
        const v = JSON.parse(m.data.toString());
        if (v.t === t || v.t === "state") return { v, video };
      }
    }
    return { v: null, video };
  }
  async first() {
    const m = await this.read();
    return m && m.kind === 0x01 ? JSON.parse(m.data.toString()) : m;
  }
  close() {
    this.socket.destroy();
  }
}

/** Which display Settings' task is on, as the phone's activity manager has it. */
function settingsDisplay() {
  let display = null;
  for (const line of adb("shell", "dumpsys activity activities").split("\n")) {
    const d = line.match(/^Display #(\d+)/);
    if (d) display = Number(d[1]);
    if (line.includes("* Task{") && line.includes("com.android.settings") && display !== null) return display;
  }
  return null;
}

async function openWindow(server, key) {
  const w = server.connect();
  w.json({ t: "open", pkg: "com.android.settings", activity: "", w: 400, h: 800, dpi: 160, fps: 30, onClose: "phone", direct: false, key });
  const { v: display } = await w.until("display");
  await w.until("video");
  return { w, display };
}

function attach(server, key, direct = false) {
  const w = server.connect();
  w.json({ t: "attach", key, w: 400, h: 800, dpi: 160, direct });
  return w;
}

const key = () => randomBytes(12).toString("hex");

if (!fs.existsSync(DEX)) {
  console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.sh, or npm run apk).`);
  process.exit(2);
}
adb("push", DEX, PHONE_DEX);

const server = new Server();
await server.ready();
console.log(`server ${server.name} on port ${server.port}`);

// A dropped connection: the window waits, and attach gives it back with a picture.
const k1 = key();
const { w: a, display: d1 } = await openWindow(server, k1);
check("a window opens with a key", d1?.t === "display", JSON.stringify(d1));
a.close(); // gone, without a "close": the cable pulled
await sleep(1500);
check("its app stays on its display while the computer is away", settingsDisplay() === d1?.id, `display ${settingsDisplay()} vs ${d1?.id}`);
const b = attach(server, k1);
const bFirst = await b.first();
check("attach answers 'attached' first", bFirst?.t === "attached", JSON.stringify(bFirst));
const { v: bVideo } = await b.until("video");
check("the picture comes again after attach", bVideo?.t === "video");

// A second connection takes the window from the first, which is shut.
const c = attach(server, k1, true);
const cFirst = await c.first();
check("a second attach takes the window over", cFirst?.t === "attached", JSON.stringify(cFirst));
while (await b.read(5000)) {}
check("the connection it replaced is closed", b.ended);
const { v: cVideo } = await c.until("video");
check("on a cable the picture goes straight into the encoder", cVideo?.paced === false, JSON.stringify(cVideo)?.slice(0, 120));

// Closed after it came back: the app goes back to the phone.
c.close();
await sleep(1000);
const d = attach(server, k1);
await d.first();
d.json({ t: "close", app: "phone" });
await sleep(2500);
check("a window closed after it came back hands its app back", settingsDisplay() === 0, `display ${settingsDisplay()}`);
d.close();

// The app opened on the phone while its window was away: the window ends, and says so.
const k2 = key();
const { w: e, display: d2 } = await openWindow(server, k2);
check("a second window opens", settingsDisplay() === d2?.id, `display ${settingsDisplay()} vs ${d2?.id}`);
e.close();
await sleep(1000);
adb("shell", "am start -W -n com.android.settings/.Settings --display 0");
await sleep(3000);
check("opened on the phone, the app is on the phone's own screen", settingsDisplay() === 0, `display ${settingsDisplay()}`);
const f = attach(server, k2);
const fFirst = await f.first();
check("a computer coming back is told the window closed", fFirst?.state === "closed", JSON.stringify(fFirst));
f.close();

// A window the server never had is "unknown": a computer opens its app again then.
const g = attach(server, "nobody");
const gFirst = await g.first();
check("an unknown window is 'unknown'", gFirst?.state === "unknown", JSON.stringify(gFirst));
g.close();

// The lease: its shell gone, no control connection, a window away — it waits, then stops.
const k3 = key();
const { w: h, display: d3 } = await openWindow(server, k3);
h.close();
server.child.stdin.end(); // the adb shell's end: the cable pulled
const t0 = Date.now();
await sleep(5000);
check("with its shell gone the server waits", server.child.exitCode === null && settingsDisplay() === d3?.id, `display ${settingsDisplay()}`);
const stopped = await server.waitFor("the computer did not come back", 40000);
const took = (Date.now() - t0) / 1000;
check("it stops when nobody came back in time", stopped && took > 25 && took < 40, `${took.toFixed(1)} s`);
await sleep(3000);
check("and the app went back to the phone", settingsDisplay() === 0, `display ${settingsDisplay()}`);
server.done();

// "stop" on its stdin is the computer done: at once.
const server2 = new Server();
await server2.ready();
await openWindow(server2, key());
const t1 = Date.now();
server2.child.stdin.write("stop\n");
const said = await server2.waitFor("stopping: the computer asked", 10000);
check("'stop' stops it at once", said && Date.now() - t1 < 5000, `${((Date.now() - t1) / 1000).toFixed(1)} s`);
await sleep(2000);
check("its app back on the phone", settingsDisplay() === 0, `display ${settingsDisplay()}`);
server2.done();
await Promise.race([server2.exited, sleep(3000)]);

adb("shell", `rm -f ${PHONE_DEX}`);
console.log(`\n${passed}/${passed + failed} passed`);
process.exit(failed ? 1 : 0);
