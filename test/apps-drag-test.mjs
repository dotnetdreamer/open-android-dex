#!/usr/bin/env node
// A drag carried out of an app's window (doc/apps-on-pc.md, "Dragging out"),
// checked on an Android 12+ phone or emulator over adb, with nothing of the
// computer's app running:
//
//   node test/apps-drag-test.mjs [serial]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex)
// to a path of its own and runs the server as the app does; the phone needs
// this build's launcher, whose one-pixel window catches the drag (PcDrags).
// It puts a picture of its own in Download, opens the Files app in a window
// as the computer does, presses on the picture with the mouse and drags it —
// a real Android drag — then does what the computer does when its pointer
// leaves the window: "dragOut", and takes the file on a connection of its own
// ("get"), checked byte for byte. Also: a press that is no drag hands nothing
// over and is left alone, and an item no longer on offer is refused. The
// picture is deleted again at the end. About half a minute.
//
// The Files app must open at Downloads, in its grid (it does on a fresh
// emulator, and goes back there): the picture is the newest file, the first
// in the grid. Needs Node 18+ and the Android SDK's adb.

import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const sdk = process.env.ANDROID_HOME || path.join(os.homedir(), "Library", "Android", "sdk");
const ADB = path.join(sdk, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
const DEX = path.join(here, "..", "openandroiddex-wmd", "openandroiddex-wmd.dex");
const PHONE_DEX = "/data/local/tmp/oadx-apps-drag-test.dex";
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

/** A PNG of one colour, `size` pixels square: a picture the Files app shows a thumbnail of. */
function png(size, rgb) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (b) => {
    let c = 0xffffffff;
    for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([len, body, sum]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(size * 3).map((_, i) => rgb[i % 3])]);
  const raw = Buffer.concat(Array.from({ length: size }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
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

/** One connection to the server, framed as Wire.java frames — and, for a `get`, the raw bytes after. */
class Wire {
  constructor(port) {
    this.socket = net.connect(port, "127.0.0.1");
    this.buf = Buffer.alloc(0);
    this.queue = [];
    this.raw = null;
    this.waiting = null;
    this.ended = false;
    this.socket.on("data", (d) => {
      if (this.raw) {
        this.raw.push(d);
        return;
      }
      this.buf = Buffer.concat([this.buf, d]);
      while (this.buf.length >= 5 && !this.raw) {
        const n = this.buf.readUInt32BE(1);
        if (this.buf.length < 5 + n) break;
        const m = { kind: this.buf[0], data: this.buf.subarray(5, 5 + n) };
        this.queue.push(m);
        this.buf = this.buf.subarray(5 + n);
        if (this.rawAfterFirst) {
          // Everything after a get's answer is the file.
          this.raw = [this.buf];
          this.buf = Buffer.alloc(0);
        }
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
  pointer(buttons, x, y) {
    const b = Buffer.alloc(9);
    b[0] = buttons;
    b.writeUInt16BE(x, 1);
    b.writeUInt16BE(y, 3);
    b.writeUInt32BE(0, 5);
    this.send(0x20, b);
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
      if ((v.t === t && also(v)) || v.t === "state") return v;
    }
    return null;
  }
  /** Everything after the first message, until the phone closes the connection. */
  async rest(ms = 30000) {
    const until = Date.now() + ms;
    while (!this.ended && Date.now() < until) await sleep(50);
    return Buffer.concat(this.raw ?? []);
  }
  close() {
    this.socket.destroy();
  }
}

/** The pointer down at `from`, moved to `to` over `ms`, still held. */
async function pressAndDrag(w, from, to, ms) {
  w.pointer(0, from[0], from[1]);
  await sleep(80);
  w.pointer(1, from[0], from[1]);
  await sleep(120);
  const steps = 16;
  for (let i = 1; i <= steps; i++) {
    w.pointer(1, Math.round(from[0] + ((to[0] - from[0]) * i) / steps), Math.round(from[1] + ((to[1] - from[1]) * i) / steps));
    await sleep(ms / steps);
  }
}

if (!fs.existsSync(DEX)) {
  console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.sh, or npm run apk).`);
  process.exit(2);
}
const sdkLevel = Number(adb("shell", "getprop ro.build.version.sdk").trim());
if (sdkLevel < 31) {
  console.error(`Android 12 or later is needed (this phone is API ${sdkLevel}).`);
  process.exit(2);
}
const files = adb("shell", "pm list packages").split("\n").map((l) => l.replace("package:", "").trim());
const FILES = ["com.google.android.documentsui", "com.android.documentsui"].find((p) => files.includes(p));
if (!FILES) {
  console.error("No Files app (DocumentsUI) on this phone.");
  process.exit(2);
}

// The picture: the newest file in Download, so the first in the grid.
const picture = png(96, [30, 140, 230]);
const name = `oadx-drag-${randomBytes(3).toString("hex")}.png`;
const local = path.join(os.tmpdir(), name);
fs.writeFileSync(local, picture);
adb("push", local, `/sdcard/Download/${name}`);
fs.unlinkSync(local);
adb("shell", `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/${name}`);
adb("push", DEX, PHONE_DEX);

const server = new Server();
await server.ready();
console.log(`server ${server.name} on port ${server.port}; ${FILES}; ${name}`);

const W = 720;
const H = 1440;
const w = server.connect();
w.json({ t: "open", pkg: FILES, activity: "", w: W, h: H, dpi: 320, fps: 30, onClose: "phone", direct: false, key: randomBytes(12).toString("hex") });
const display = await w.until("display");
check("the Files app opens in a window", display?.t === "display", JSON.stringify(display));
await w.until("video");
await sleep(3000); // its grid loaded

// A press that only moves within the app is no drag: nothing to hand over.
w.pointer(0, 360, 1300);
await sleep(80);
w.pointer(1, 360, 1300);
await sleep(100);
w.pointer(1, 380, 1320);
const noDrag = await w.until("drag", 800);
w.json({ t: "dragOut" });
const none = await w.until("dragged", 3000);
check("a press with no drag under way hands nothing over", none?.none === true, JSON.stringify(none));
w.pointer(0, 380, 1320);
check("…and the app heard no drag start", noDrag === null, JSON.stringify(noDrag));
await sleep(500);

// The picture, pressed and dragged: Android's own drag starts in the window.
// A slow emulator takes seconds to list Download: tried again until it has.
let t0 = 0;
let on = null;
for (let attempt = 1; attempt <= 5 && !on; attempt++) {
  t0 = Date.now();
  await pressAndDrag(w, [200, 560], [420, 1000], 700);
  on = await w.until("drag", 2500, (v) => v.on === true);
  if (!on) {
    w.pointer(0, 420, 1000);
    console.log(`  no drag on attempt ${attempt}; the grid may not be there yet`);
    await sleep(2500);
  }
}
check("the phone says a drag has started", on?.on === true, JSON.stringify(on));
check("…and what it carries", (on?.mimes ?? []).some((m) => m.startsWith("image/")), JSON.stringify(on?.mimes));

// The computer's pointer leaves the window: the drag is dropped on the launcher's pixel.
const out0 = Date.now();
w.json({ t: "dragOut" });
const dragged = await w.until("dragged", 4000);
const handOverMs = Date.now() - out0;
check("dragOut hands the drag over", typeof dragged?.id === "number" && dragged.items?.length === 1, JSON.stringify(dragged));
const item = dragged?.items?.[0] ?? {};
check("…with its name, type and size", item.name === name && item.mime === "image/png" && item.size === picture.length, JSON.stringify(item));
check("…in good time", handOverMs < 1500, `${handOverMs} ms after dragOut, ${out0 - t0} ms after the press`);
const off = await w.until("drag", 3000, (v) => v.on === false);
check("the phone's drag has ended", off?.on === false, JSON.stringify(off));

// The computer still holds its button: its moves reach nothing until it lets go.
w.pointer(1, 700, 1400);
w.pointer(0, 700, 1400);
await sleep(300);

// The file itself, on a connection of its own.
const g = server.connect();
g.rawAfterFirst = true;
g.json({ t: "get", id: dragged?.id ?? -1, i: 0 });
const head = await g.read(5000);
const answer = head && head.kind === 0x01 ? JSON.parse(head.data.toString()) : null;
check("get answers with the file's name and size", answer?.ok === true && answer.name === name && answer.size === picture.length, JSON.stringify(answer));
const bytes = await g.rest();
const sha = (b) => createHash("sha256").update(b).digest("hex");
check("…then the file, byte for byte", sha(bytes) === sha(picture), `${bytes.length} of ${picture.length} bytes`);

const gone = server.connect();
gone.json({ t: "get", id: 9999, i: 0 });
const goneAnswer = await gone.until("get", 5000);
check("an item no longer on offer is refused", goneAnswer?.ok === false, JSON.stringify(goneAnswer));
gone.close();

// The app still takes the mouse: a press there is a press, not the hand-over's.
w.pointer(0, 360, 1300);
await sleep(100);
w.pointer(1, 360, 1300);
await sleep(80);
w.pointer(0, 360, 1300);
await sleep(400);
const stuck = server.lines.filter((l) => l.includes("input refused")).length;
check("the window takes input after the hand-over", stuck === 0, `${stuck} refusals`);

w.json({ t: "close", app: "phone" });
w.close();
await sleep(1500);
server.done();
await sleep(1500);
adb("shell", `rm -f /sdcard/Download/${name}`);
adb("shell", `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/${name}`);
adb("shell", `rm -f ${PHONE_DEX}`);
for (const l of server.lines.filter((l) => l.includes("drag"))) console.log("  phone: " + l);
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
