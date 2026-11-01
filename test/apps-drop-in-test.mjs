#!/usr/bin/env node
// Files dropped on an app's window, given to the app (doc/apps-on-pc.md,
// "Dropping files"), checked on an Android 12+ phone or emulator over adb,
// with nothing of the computer's app running:
//
//   node test/apps-drop-in-test.mjs [serial] [package/activity]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex)
// to a path of its own and runs the server as the app does; the phone needs
// this build's launcher, whose pixel starts the drag (PcDrags) and whose
// cache holds the files (DropsProvider). Opens Settings' search in a window —
// or the activity named, the first text field in it standing for search's —
// and does what the computer does with files dropped on it: each one staged
// ("put" with "stage"), then one "dropIn" at the point it was let go.
//
// - A text file dropped on the search field: a text field takes a drop as
//   its text, reading the file through the permission the drop carries — so
//   Ctrl+A and Ctrl+C then copy exactly the file's text, and nothing of the
//   drop is put in Download as well. Where search's field is no TextView
//   (Compose, on newer Pixel images), this and the next two are said and not
//   tried: name an activity with one.
// - A text file and a picture dropped on it together: the field takes the
//   drop and reads only the text, so the picture, never opened, goes to
//   Download as well, byte for byte — an app using only part of a drop.
// - A text file dropped on it while the window loses the keyboard on the
//   computer every 20 ms: the press carrying the drop is let go where the
//   drop was let go, not short of it, so the field still has the file's text.
// - The same dropped where nothing takes a drop: the answer says the app did
//   not ("refused"), and the file is in Download, byte for byte. What any
//   drop put in Download is deleted again at the end.
// - No input refused, and the window takes the mouse again after.
//
// About a minute. Needs Node 18+ and the Android SDK's adb.

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
const PHONE_DEX = "/data/local/tmp/oadx-apps-drop-in-test.dex";
const serialArg = process.argv[2];
const appArg = process.argv[3];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";
/** A file on the phone as its bytes. */
const phoneBytes = (p) => spawnSync(ADB, adbArgs("exec-out", `cat '${p}'`)).stdout ?? Buffer.alloc(0);

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
  pointer(buttons, x, y) {
    const b = Buffer.alloc(9);
    b[0] = buttons;
    b.writeUInt16BE(x, 1);
    b.writeUInt16BE(y, 3);
    b.writeUInt32BE(0, 5);
    this.send(0x20, b);
  }
  key(down, code, meta = 0) {
    const b = Buffer.alloc(8);
    b[0] = down ? 1 : 0;
    b.writeUInt16BE(code, 2);
    b.writeUInt32BE(meta, 4);
    this.send(0x22, b);
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
  async frame(ms = 15000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return false;
      if (m.kind === 0x02 && m.data.length > 9 && (m.data[0] & 2) === 0) return true;
    }
    return false;
  }
  close() {
    this.socket.destroy();
  }
}

/** One file staged for a drop, as the computer sends it: the server's answer. */
async function stage(server, batch, i, name, bytes) {
  const c = server.connect();
  c.json({ t: "put", name, size: bytes.length, stage: batch, i });
  c.socket.write(bytes);
  const answer = await c.until("put", 15000);
  c.close();
  return answer;
}

/** The staged files dropped on the window at (x, y): the server's answer. */
async function dropIn(server, key, batch, files, x, y) {
  const c = server.connect();
  c.json({ t: "dropIn", window: key, batch, x, y, files });
  const answer = await c.until("dropIn", 20000);
  c.close();
  return answer;
}

/**
 * Where the search field is on the window's display: its middle, from the view
 * hierarchy `dumpsys activity top` prints for the search's task — found by its
 * package (Android 12 prints no display there), and only one as wide as the
 * window: an instance on the phone's own screen is another. Each view's bounds
 * there are in its parent, one level of indent deeper per level, so a view's
 * place on the display is its own plus every ancestor's. Null with `none` set
 * where the window's task is listed and its field is no TextView (Compose, on
 * a Pixel's Settings), which takes no drop of a file as text anyway; null
 * alone where the window's task is not listed. Android 12 leaves it out of
 * `top`, and lists it when asked for the package's activities.
 */
function searchField(pkg, width, out) {
  return fieldIn("top", pkg, width, out) ?? (out.none ? null : fieldIn(pkg, pkg, width, out));
}

function fieldIn(what, pkg, width, out) {
  const dump = adb("shell", `dumpsys activity ${what}`);
  for (const block of dump.split(/\n(?=TASK )/)) {
    if (!block.split("\n").some((l) => /^\s*ACTIVITY /.test(l) && l.includes(pkg + "/"))) continue;
    const ancestors = []; // [indent, left, top] of the views above the current line
    let root = null;
    for (const line of block.split("\n")) {
      const m = line.match(/^(\s*)([\w.$]+)\{[^}]*?\s(-?\d+),(-?\d+)-(-?\d+),(-?\d+)/);
      if (!m) continue;
      const indent = m[1].length;
      const [l, t, r, b] = m.slice(3).map(Number);
      if (root === null) root = r - l;
      if (root !== width) break; // another instance, elsewhere
      while (ancestors.length && ancestors[ancestors.length - 1][0] >= indent) ancestors.pop();
      const left = ancestors.reduce((s, a) => s + a[1], 0) + l;
      const top = ancestors.reduce((s, a) => s + a[2], 0) + t;
      if (/EditText|AutoComplete/.test(m[2]) && r > l && b > t) {
        return { x: left + Math.round((r - l) / 2), y: top + Math.round((b - t) / 2), raw: line.trim().slice(0, 120) };
      }
      ancestors.push([indent, l, t]);
    }
    if (root === width) out.none = true;
  }
  return null;
}

if (!fs.existsSync(DEX)) {
  console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.cmd or build.sh, or npm run apk).`);
  process.exit(2);
}
const sdkLevel = Number(adb("shell", "getprop ro.build.version.sdk").trim());
if (sdkLevel < 31) {
  console.error(`Android 12 or later is needed (this phone is API ${sdkLevel}).`);
  process.exit(2);
}
const resolved =
  appArg ?? adb("shell", "cmd package resolve-activity --brief -a com.android.settings.action.SETTINGS_SEARCH").trim().split(/\r?\n/).pop() ?? "";
const [pkg, cls] = resolved.trim().split("/");
if (!pkg || !cls) {
  console.error((appArg ? "Not an activity: " : "No Settings search on this phone: ") + resolved);
  process.exit(2);
}
const activity = cls.startsWith(".") ? pkg + cls : cls;
const appName = appArg ? pkg : "Settings' search";

adb("push", DEX, PHONE_DEX);
const server = new Server();
await server.ready();
console.log(`server ${server.name} on port ${server.port}; ${pkg}/${activity}`);

const control = server.connect();
control.json({ t: "control" });
const key = randomBytes(12).toString("hex");
const w = server.connect();
w.json({ t: "open", pkg, activity, w: 720, h: 1280, dpi: 320, fps: 30, onClose: "close", direct: false, key });
const opened = await w.until("display");
check(`${appName} opens in a window`, typeof opened?.id === "number", JSON.stringify(opened));
check("…and shows: its picture comes", await w.frame(15000));
w.json({ t: "focus" });
await sleep(3000); // its field focused, and the launcher's pixel up (drag.watch)

/** The search field emptied: Ctrl+A, then Delete. */
async function emptyField() {
  for (const [down, code, meta] of [[true, 29, 0x3000], [false, 29, 0x3000], [true, 67, 0], [false, 67, 0]]) {
    w.key(down, code, meta);
    await sleep(60);
  }
  await sleep(400);
}

/** The field's text as Ctrl+A and Ctrl+C copy it: the last clip the phone reports in 2.5 s, as one copy may be reported twice. */
async function fieldText() {
  control.queue.length = 0; // nothing of an earlier copy
  for (const [down, code] of [[true, 29], [false, 29], [true, 31], [false, 31]]) {
    w.key(down, code, 0x3000);
    await sleep(60);
  }
  let last = null;
  const until = Date.now() + 2500;
  for (let c; (c = await control.until("clip", until - Date.now())); ) last = c;
  return last;
}

// Empty the field: an earlier query may still be in it.
await emptyField();

let field = null;
const listed = {};
for (let tries = 0; tries < 3 && !field && !listed.none; tries++) {
  if (tries) await sleep(700);
  field = searchField(pkg, 720, listed);
}
if (!field && !listed.none && !appArg) {
  // Android 12 lists no activity on a window's display: where a Galaxy's
  // Settings search has its field in a 720×1280 window, its toolbar.
  field = { x: 555, y: 62, raw: "not listed: the toolbar, where Samsung's search has its field" };
}
const words = `dropped from a computer ${randomBytes(3).toString("hex")}`;
const fellBack = []; // paths in Download of drops on the field that no app took
if (field) {
  console.log(`  the search field: ${field.x},${field.y}  (${field.raw})`);
  // A text file dropped on the field: the field takes it as its text.
  const batch1 = randomBytes(6).toString("hex");
  const name1 = `oadx-in-${batch1}.txt`;
  const staged1 = await stage(server, batch1, 0, name1, Buffer.from(words));
  check("a file staged for a drop: in the launcher's cache, not Download", staged1?.ok === true && staged1.staged === true && !staged1.path, JSON.stringify(staged1));
  check("…and not in Download", !adb("shell", `ls /sdcard/Download/${name1} 2>/dev/null`).trim());
  const t0 = Date.now();
  const in1 = await dropIn(server, key, batch1, [{ i: 0, name: staged1?.name ?? name1 }], field.x, field.y);
  for (const f of in1?.files ?? []) fellBack.push(f.path);
  const dropMs = Date.now() - t0;
  check("dropped on the search field, the app takes it", in1?.ok === true && in1.taken === true, `${JSON.stringify(in1)}, ${dropMs} ms`);
  check("…and opens it: nothing of it is put in Download as well", in1?.taken === true && !in1.files?.length, JSON.stringify(in1?.files ?? null));
  await sleep(600);
  const clip = await fieldText();
  check("…as text fields take a drop: the file's own text, read through the drop's permission", clip?.text === words, JSON.stringify(clip));

  // A drop the app takes and uses part of: a text field takes any drop and
  // reads only text, so a picture dropped with a text file is never opened.
  await emptyField();
  const batch3 = randomBytes(6).toString("hex");
  const words3 = `dropped with a picture ${randomBytes(3).toString("hex")}`;
  const picture = randomBytes(4096); // its type is its name's: no text field opens it
  const text3 = await stage(server, batch3, 0, `oadx-in-${batch3}.txt`, Buffer.from(words3));
  const pic3 = await stage(server, batch3, 1, `oadx-in-${batch3}.png`, picture);
  const t3 = Date.now();
  const in3 = await dropIn(server, key, batch3, [{ i: 0, name: text3?.name }, { i: 1, name: pic3?.name }], field.x, field.y);
  const ms3 = Date.now() - t3;
  for (const f of in3?.files ?? []) fellBack.push(f.path);
  const kept = in3?.files ?? [];
  check(
    "a text file and a picture dropped on it together: taken, and the picture, which it never opened, in Download as well — the text file not",
    in3?.ok === true && in3.taken === true && kept.length === 1 && kept[0].ok === true && kept[0].name === pic3?.name,
    `${JSON.stringify(in3)}, ${ms3} ms`,
  );
  check("…byte for byte", kept[0]?.path ? phoneBytes(kept[0].path).equals(picture) : false, kept[0]?.path ?? "no path");
  await sleep(600);
  const clip3 = await fieldText();
  check("…and the field has the text file's text", clip3?.text?.trim() === words3, JSON.stringify(clip3));

  // The window losing the keyboard on the computer while the drop is carried:
  // a blur lets go of every key and button, but the drop's press is the drop's.
  await emptyField();
  const batch4 = randomBytes(6).toString("hex");
  const words4 = `dropped while the window lost focus ${randomBytes(3).toString("hex")}`;
  const text4 = await stage(server, batch4, 0, `oadx-in-${batch4}.txt`, Buffer.from(words4));
  let blurs = 0;
  const blurring = setInterval(() => {
    w.json({ t: "blur" });
    blurs++;
  }, 20);
  const in4 = await dropIn(server, key, batch4, [{ i: 0, name: text4?.name }], field.x, field.y);
  clearInterval(blurring);
  for (const f of in4?.files ?? []) fellBack.push(f.path);
  w.json({ t: "focus" });
  check(
    "dropped on it while the window loses the keyboard every 20 ms: taken where it was let go, and opened",
    in4?.ok === true && in4.taken === true && !in4.files?.length,
    `${JSON.stringify(in4)}, ${blurs} blurs`,
  );
  await sleep(600);
  const clip4 = await fieldText();
  check("…the field has its text", clip4?.text === words4, JSON.stringify(clip4));
} else {
  console.log("  no TextView for the search field here (a newer Pixel's is Compose): the drops on it are not tried");
}

// The same, where nothing takes a drop: the app refuses it, and it goes to
// Download. Nothing on the way is clicked: the press that starts the drag is at
// the display's corner, where an app's Back arrow often is.
/** The activity in front on the window's display, as `am stack list` says: under a root task line with its display, the tasks' lines. */
function topOf() {
  let onOurs = false;
  for (const l of adb("shell", "am stack list").split("\n")) {
    const root = l.match(/^\S.*displayId=(\d+)/);
    if (root) onOurs = Number(root[1]) === opened.id;
    else if (onOurs && l.includes("topActivity=")) return (l.match(/topActivity=ComponentInfo\{([^}]+)\}/) ?? [])[1] ?? l.trim();
  }
  return "";
}
const before = topOf();
const batch2 = randomBytes(6).toString("hex");
const name2 = `oadx-in-${batch2}.txt`;
const staged2 = await stage(server, batch2, 0, name2, Buffer.from(words));
const in2 = await dropIn(server, key, batch2, [{ i: 0, name: staged2?.name ?? name2 }], 360, 1200);
const landed = in2?.files?.[0] ?? {};
check("dropped where nothing takes it: refused, and the file in Download", in2?.ok === true && in2.taken === false && in2.why === "refused" && landed.ok === true, JSON.stringify(in2));
const onPhone = adb("shell", `cat '${landed.path ?? "/nonexistent"}'`);
check("…byte for byte", onPhone === words, JSON.stringify(onPhone.slice(0, 60)));
await sleep(500);
const after = topOf();
check("…and the app was not clicked on the way: what it shows is as it was", after === before, `${before.slice(0, 90)} → ${after.slice(0, 90)}`);

// The window takes the mouse again: a press is a press. On the field, where a
// click changes nothing (below it, search results open, and stay open the next time).
const at = field ?? { x: 360, y: 60 };
w.pointer(0, at.x, at.y);
await sleep(100);
w.pointer(1, at.x, at.y);
await sleep(80);
w.pointer(0, at.x, at.y);
await sleep(400);
const refused = server.lines.filter((l) => l.includes("input refused")).length;
check("no input was refused", refused === 0, `${refused} refusals`);

w.json({ t: "close", app: "close" }); // the app ended, not handed back to the phone's own screen
w.close();
control.close();
await sleep(1500);
server.done();
await sleep(1000);
// What went to Download: the drop nothing took, and the one on the field if it fell back too.
for (const path of [landed.path, ...fellBack]) {
  if (!path) continue;
  adb("shell", `rm -f '${path}'`);
  adb("shell", `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://${path}'`);
}
adb("shell", `rm -f ${PHONE_DEX}`);
for (const l of server.lines.filter((l) => /drop|drag|staged|launcher/.test(l))) console.log("  phone: " + l);
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
