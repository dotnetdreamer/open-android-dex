#!/usr/bin/env node
// "Send to computer" and the phone's photos (doc/send-to-computer.md): the
// phone's half, checked on a phone over adb with nothing of the app running.
//
//   node test/send-test.mjs [serial] [--photos|--share]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex,
// built by its build script or `npm run apk`) to a path of its own, runs the
// app server under app_process as the app does, and speaks its `send` link
// through an adb forward, as the computer does.
//
// Photos (needs nothing installed; runs with the phone locked): the link and
// its hello, a second link taking the first one's place, a snapshot (newest
// first, only the camera's and the screenshots' folders), a picture moved into
// a folder that counts arriving as new with a thumbnail of at most 512 px, a
// pushed file staying out until it is scanned, an original byte for byte and
// from the middle, `changed` and `gone`, a picture removed going, `older` and
// `all` paging through without a repeat or a gap, `status`, and looking
// stopped. The test's pictures live in folders of its own (Pictures/oadx-test-*
// and Pictures/Screenshots/oadx-test-*), and are removed at the end.
//
// Share (needs this build's launcher, with its SendActivity; skipped if the
// phone's has none): the share entry turned on and back, text, then a
// throwaway sharing app of the test's own — built here from test/send/ with
// aapt2, javac, d8, zipalign and apksigner (the launcher's tracked debug key),
// installed, used and uninstalled — sharing private files of its own: one,
// three at once, an empty one, one with a hostile name and a big one, each
// taken byte for byte though the shell could never read them by itself; a
// file that says it is smaller than it is (a hint, every byte taken); a file
// whose open the provider holds past the launcher's 50 s; what a sloppy or hostile app
// shares (a list of Strings, a Parcelable that is no Uri: the launcher stays
// up); a link with a preview picture beside it (sent as the link); a tap
// outside the card before its files are open (nothing is lost); thousands of
// knocks on the port, then a share that still arrives; a share taken back on
// its card; a link dropped half way; no computer; a computer that takes no
// shares. The phone's share entry is left as found.
//
// It never runs on whatever device adb picks: with no serial given it needs
// exactly one attached, and stops if there are more.
//
// Uses the app's own adb (resources/bin) when there is one; ADB=… overrides
// it. Needs Node 18+, and for the share part the Android SDK (ANDROID_HOME, or
// ~/Library/Android/sdk) with build-tools and platform 36.

import { spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
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
const PHONE_DEX = "/data/local/tmp/oadx-send-test.dex";
const PICTURE = path.join(here, "..", "doc", "img", "demo-video.jpg");
const LAUNCHER = "com.ccrstech.openandroiddex.launcher";
const TEST_APP = "com.ccrstech.openandroiddex.sendtest";
const args = process.argv.slice(2);
const serialArg = args.find((a) => !a.startsWith("--"));
const only = args.find((a) => a.startsWith("--"))?.slice(2);
const RUN = randomBytes(2).toString("hex");
const STAGE = `Pictures/oadx-test-${RUN}`;
const SHOTS = "Pictures/Screenshots";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";
const sh = (cmd) => adb("shell", cmd);
const sha = (b) => createHash("sha256").update(b).digest("hex");

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
    this.child = spawn(ADB, adbArgs("shell", `trap '' HUP; CLASSPATH=${PHONE_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.AppServer ${this.name} --no-warm-up`), {
      stdio: ["pipe", "pipe", "inherit"],
    });
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
  connect() {
    return new Wire(this.port);
  }
  async stop() {
    this.child.stdin.write("stop\n");
    await Promise.race([this.exited, sleep(5000)]);
    spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
  }
}

/** One connection, framed as Wire.java frames; after a `photo` or `get` answer, the raw bytes. */
class Wire {
  constructor(port) {
    this.socket = net.connect(port, "127.0.0.1");
    this.buf = Buffer.alloc(0);
    this.queue = [];
    this.raw = false;
    this.rawBytes = [];
    this.waiting = null;
    this.ended = false;
    this.socket.on("data", (d) => {
      if (this.raw) {
        this.rawBytes.push(d);
        return this.wake();
      }
      this.buf = Buffer.concat([this.buf, d]);
      while (!this.raw && this.buf.length >= 5) {
        const n = this.buf.readUInt32BE(1);
        if (this.buf.length < 5 + n) break;
        this.queue.push({ kind: this.buf[0], data: this.buf.subarray(5, 5 + n) });
        this.buf = this.buf.subarray(5 + n);
        if (this.rawAfterFirst) {
          this.raw = true;
          if (this.buf.length) this.rawBytes.push(this.buf);
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
  json(v) {
    const body = Buffer.from(JSON.stringify(v));
    const head = Buffer.alloc(5);
    head[0] = 0x01;
    head.writeUInt32BE(body.length, 1);
    this.socket.write(Buffer.concat([head, body]));
  }
  async next(want, ms = 15000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      while (!this.queue.length && !this.ended && Date.now() < until) {
        await Promise.race([new Promise((r) => (this.waiting = r)), sleep(Math.max(0, until - Date.now()))]);
      }
      const m = this.queue.shift();
      if (!m) return null;
      if (m.kind !== 0x01) continue;
      const v = JSON.parse(m.data.toString());
      if (want(v)) return v;
    }
    return null;
  }
  /** The bytes after the answer, to the end of the connection, or `most` of them. */
  async rest(ms = 60000, most = Infinity) {
    const until = Date.now() + ms;
    while (!this.ended && Date.now() < until && this.rawBytes.reduce((n, b) => n + b.length, 0) < most) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(200)]);
    }
    return Buffer.concat(this.rawBytes);
  }
  close() {
    this.socket.destroy();
  }
}

/**
 * A `get`'s bytes as the phone sends them (AppOffers.java): pieces — 0x04, a
 * u32 length, the bytes — then an empty piece, the end mark. Only bytes that
 * came with their end mark are a whole file: none without it.
 */
function pieces(raw) {
  const out = [];
  let at = 0;
  while (at + 5 <= raw.length && raw[at] === 0x04) {
    const len = raw.readUInt32BE(at + 1);
    if (at + 5 + len > raw.length) break;
    if (len === 0) return Buffer.concat(out);
    out.push(raw.subarray(at + 5, at + 5 + len));
    at += 5 + len;
  }
  return Buffer.alloc(0);
}

const hello = (v) => v.t === "send";
const photos = (kind) => (v) => v.t === "photos" && v.kind === kind;

/** A link, as the computer makes it: its hello. */
async function link(server, share) {
  const w = server.connect();
  w.json({ t: "send", v: 1, computer: "oadx test", os: "test", share });
  const h = await w.next(hello, 10000);
  return { w, h };
}

/** One picture of the phone's, by asking for it on a `photo` connection: the answer and the bytes. */
async function original(server, p, extra = {}) {
  const w = server.connect();
  w.rawAfterFirst = true;
  w.json({ t: "photo", id: p.id, size: p.size, modified: p.modified, offset: 0, ...extra });
  const a = await w.next((v) => v.t === "photo", 10000);
  const bytes = a?.ok ? await w.rest() : Buffer.alloc(0);
  w.close();
  return { a, bytes };
}

/** A JPEG's width and height, from its first SOF marker. */
function jpegSize(b) {
  let i = 2;
  while (i < b.length) {
    if (b[i] !== 0xff) return null;
    const m = b[i + 1];
    const len = b.readUInt16BE(i + 2);
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    i += 2 + len;
  }
  return null;
}

/** Push a picture into a folder that does not count, publish it with a scan, wait until the media store has it. */
async function staged(name) {
  sh(`mkdir -p /sdcard/${STAGE}`);
  adb("push", PICTURE, `/sdcard/${STAGE}/${name}`);
  sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/${STAGE}/${name}`);
  for (let i = 0; i < 40; i++) {
    if (sh(`content query --uri content://media/external/images/media --projection _id --where "_display_name='${name}'"`).includes("_id=")) return true;
    await sleep(250);
  }
  return false;
}

async function photosPart(server, entryWas) {
  console.log("── photos");
  // 1. the link
  const { w: first, h } = await link(server, entryWas);
  check("the link says hello: v, sdk, launcher, now", h?.ok === true && h.v === 1 && h.sdk > 0 && ["ready", "old", "missing"].includes(h.launcher) && Math.abs(h.now - Date.now() / 1000) < 600, JSON.stringify(h));
  const { w, h: h2 } = await link(server, entryWas);
  const firstEnded = await first.next(() => true, 3000);
  check("a second link takes the first one's place, and the first is closed", !!h2 && firstEnded === null && first.ended);
  first.close();

  // 2. a snapshot
  w.json({ t: "photos", on: true, limit: 50, every: 300 });
  const snap = await w.next(photos("snapshot"), 10000);
  const items = snap?.items ?? [];
  const newestFirst = items.every((p, i) => i === 0 || items[i - 1].added > p.added || (items[i - 1].added === p.added && items[i - 1].id > p.id));
  const counted = items.every((p) => /^(DCIM\/|Pictures\/Screenshots\/)/.test(p.path) && p.size > 0 && p.kind === "image");
  check("a snapshot: newest first, only the camera's and the screenshots', none empty, at most the limit", !!snap && newestFirst && counted && items.length <= 50, `${items.length} of ${snap?.total?.count}`);

  // 3. moved into a folder that counts: new, with a thumbnail
  const name = `oadx-test-${RUN}-a.jpg`;
  const stagedOk = await staged(name);
  sh(`mkdir -p /sdcard/${SHOTS}`);
  const t0 = Date.now();
  sh(`mv /sdcard/${STAGE}/${name} /sdcard/${SHOTS}/${name}`);
  const fresh = await w.next((v) => photos("new")(v) && v.items.some((p) => p.name === name), 10000);
  const pic = fresh?.items.find((p) => p.name === name);
  check("a picture moved into Pictures/Screenshots is new", stagedOk && !!pic, pic ? `${Date.now() - t0} ms after mv started` : "");
  const th = pic ? await w.next((v) => v.t === "thumb" && v.id === pic.id, 10000) : null;
  const jpeg = th?.jpeg ? Buffer.from(th.jpeg, "base64") : null;
  const dims = jpeg ? jpegSize(jpeg) : null;
  check("…and its thumbnail is a JPEG no longer than 512 on its long side", !!dims && Math.max(dims.w, dims.h) <= 512, dims ? `${dims.w}x${dims.h}, ${jpeg.length} bytes` : "");

  // 4. a pushed file is not there until it is scanned
  const pushed = `oadx-test-${RUN}-b.jpg`;
  adb("push", PICTURE, `/sdcard/${SHOTS}/${pushed}`);
  const early = await w.next((v) => photos("new")(v) && v.items.some((p) => p.name === pushed), 2500);
  sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/${SHOTS}/${pushed}`);
  const late = await w.next((v) => photos("new")(v) && v.items.some((p) => p.name === pushed), 8000);
  check("a pushed file is not there until it is scanned, and is after", early === null && !!late);

  // 5. its original, byte for byte; from the middle; changed; gone
  const want = fs.readFileSync(PICTURE);
  if (pic) {
    const whole = await original(server, pic);
    check("its original, byte for byte (SHA-256)", whole.a?.ok === true && sha(whole.bytes) === sha(want), `${whole.bytes.length} bytes`);
    const half = Math.floor(want.length / 2);
    const tail = await original(server, pic, { offset: half });
    check("…and again from the middle", tail.a?.ok === true && sha(tail.bytes) === sha(want.subarray(half)));
    const wrong = await original(server, { ...pic, size: pic.size + 1 });
    check("with a size that is not the phone's: changed", wrong.a?.ok === false && wrong.a.why === "changed");
  }
  const none = await original(server, { id: 987654321, size: 1, modified: 1 });
  check("a made-up id: gone", none.a?.ok === false && none.a.why === "gone");

  // 6. removed on the phone: gone
  if (pic) {
    sh(`rm -f /sdcard/${SHOTS}/${name}`);
    const gone = await w.next((v) => photos("gone")(v) && v.ids.includes(pic.id), 10000);
    check("removed on the phone, it is gone", !!gone);
  }

  // 7. older and all page through without a repeat or a gap
  w.json({ t: "photos", on: true, limit: 500, every: 300 });
  const everything = (await w.next(photos("snapshot"), 10000))?.items ?? [];
  const pagedAll = [];
  let after = null;
  for (let i = 0; i < 600; i++) {
    w.json({ t: "all", since: 0, limit: 2, ...(after ? { after } : {}) });
    const page = await w.next(photos("all"), 10000);
    if (!page) break;
    pagedAll.push(...page.items);
    if (!page.next) break;
    after = page.next;
  }
  const ids = (l) => l.map((p) => p.id);
  const sameSet = (a, b) => a.length === b.length && [...a].sort().join() === [...b].sort().join();
  check("`all` pages through, oldest first, without a repeat or a gap", sameSet(ids(pagedAll), ids(everything)) && new Set(ids(pagedAll)).size === pagedAll.length, `${pagedAll.length} of ${everything.length}`);
  const pagedOlder = everything.slice(0, 1);
  for (let i = 0; i < 600 && pagedOlder.length; i++) {
    const last = pagedOlder[pagedOlder.length - 1];
    w.json({ t: "older", before: { added: last.added, id: last.id }, limit: 2 });
    const page = await w.next(photos("older"), 10000);
    if (!page) break;
    pagedOlder.push(...page.items);
    if (!page.more) break;
  }
  check("`older` pages through, newest first, without a repeat or a gap", JSON.stringify(ids(pagedOlder)) === JSON.stringify(ids(everything)), `${pagedOlder.length} of ${everything.length}`);

  // 8. status; looking stopped
  w.json({ t: "status" });
  const st = await w.next((v) => v.t === "status", 5000);
  check("`status` answers: battery, charging, the phone's clock", !!st && typeof st.battery === "number" && typeof st.charging === "boolean" && st.now > 0, JSON.stringify(st));
  w.json({ t: "photos", on: false });
  await sleep(500);
  const after2 = `oadx-test-${RUN}-c.jpg`;
  adb("push", PICTURE, `/sdcard/${SHOTS}/${after2}`);
  sh(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/${SHOTS}/${after2}`);
  const quiet = await w.next(photos("new"), 3000);
  check("with looking stopped, a new picture says nothing", quiet === null);
  w.close();
}

// ── the share part ──

const logcat = () => sh("logcat -d -s OpenDeX:I").split(/\r?\n/).filter((l) => l.includes("[send]"));

/** The file the test app makes for entry i: byte j is (j * 31 + i * 7 + 3) & 0xff. */
function pattern(i, size) {
  const b = Buffer.alloc(size);
  for (let j = 0; j < size; j++) b[j] = (j * 31 + i * 7 + 3) & 0xff;
  return b;
}

function buildTestApp() {
  // On Windows the build tools are d8.bat and apksigner.bat beside aapt2.exe and zipalign.exe; a .bat
  // runs only through cmd.exe. The dex goes into the APK with the JDK's jar, which javac's JDK has
  // everywhere, where zip is a Mac's and Linux's only.
  const win = process.platform === "win32";
  const tool = (dir, name) => path.join(dir, name + (win ? (name === "d8" || name === "apksigner" ? ".bat" : ".exe") : ""));
  const bt = ["36.1.0", "36.0.0", "35.0.0", "34.0.0"].map((v) => path.join(sdk, "build-tools", v)).find((d) => fs.existsSync(tool(d, "d8")));
  const jar = path.join(sdk, "platforms", "android-36", "android.jar");
  const jdk = (name) => [process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, "bin", name + (win ? ".exe" : "")), `/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/${name}`, name].find((j) => j && (j === name || fs.existsSync(j)));
  const javac = jdk("javac");
  if (!bt || !fs.existsSync(jar)) {
    console.log(`SKIP the sharing app: no Android build-tools 34–36 with d8, or no platform android-36, under ${sdk}`);
    return null;
  }
  const src = path.join(here, "send");
  const out = path.join(src, "build");
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(path.join(out, "classes"), { recursive: true });
  const run = (cmd, a) => {
    const r = cmd.endsWith(".bat") ? spawnSync("cmd.exe", ["/d", "/s", "/c", cmd, ...a], { encoding: "utf8" }) : spawnSync(cmd, a, { encoding: "utf8" });
    if (r.status !== 0) throw new Error(`${path.basename(cmd)}: ${r.stderr || r.stdout || r.error}`);
  };
  run(tool(bt, "aapt2"), ["link", "-o", path.join(out, "base.apk"), "-I", jar, "--manifest", path.join(src, "AndroidManifest.xml")]);
  const sources = fs.readdirSync(path.join(src, "src/com/ccrstech/openandroiddex/sendtest")).map((f) => path.join(src, "src/com/ccrstech/openandroiddex/sendtest", f));
  run(javac, ["-nowarn", "-source", "17", "-target", "17", "-cp", jar, "-d", path.join(out, "classes"), ...sources]);
  const classes = [];
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : classes.push(path.join(d, e.name))));
  walk(path.join(out, "classes"));
  run(tool(bt, "d8"), ["--min-api", "26", "--output", out, ...classes]);
  fs.copyFileSync(path.join(out, "base.apk"), path.join(out, "unaligned.apk"));
  run(jdk("jar"), ["--update", "--no-manifest", "--file", path.join(out, "unaligned.apk"), "-C", out, "classes.dex"]);
  run(tool(bt, "zipalign"), ["-f", "4", path.join(out, "unaligned.apk"), path.join(out, "aligned.apk")]);
  const key = path.join(here, "..", "openandroiddex-launcher", "app", "debug.keystore");
  run(tool(bt, "apksigner"), ["sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", path.join(out, "sendtest.apk"), path.join(out, "aligned.apk")]);
  return path.join(out, "sendtest.apk");
}

/** One share from the test app: its `shared`, and each file taken and checked. */
async function shareFiles(server, w, entries, label, { take = true, says = null, hold = null } = {}) {
  sh("logcat -c");
  const spec = entries.map(([n, s]) => `${n}:${s}`).join(",");
  const extra = (says !== null ? ` --es says ${says}` : "") + (hold !== null ? ` --es hold ${hold}` : "");
  const t0 = Date.now();
  sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es files '${spec.replace(/'/g, "'\\''")}'${extra}`);
  const shared = await w.next((v) => v.t === "shared", 60000);
  if (shared) shared.ms = Date.now() - t0;
  if (!shared) {
    const seen = sh("logcat -d | grep -E 'OpenDeX|oadx-sendtest|START u0|SendActivity' | grep -v SurfaceFlinger | tail -8");
    check(`${label}: shared`, false, (logcat().join(" | ") + " || " + seen.replace(/\s+/g, " ")).slice(0, 900));
    return null;
  }
  if (!take) return shared;
  let ok = shared.items.length === entries.length;
  const landed = [];
  for (let i = 0; i < shared.items.length; i++) {
    const g = server.connect();
    g.rawAfterFirst = true;
    g.json({ t: "get", id: shared.offer, i });
    const a = await g.next((v) => v.t === "get", 20000);
    const bytes = a?.ok ? pieces(await g.rest(180000)) : Buffer.alloc(0);
    g.close();
    const want = pattern(i, entries[i][1]);
    const same = a?.ok && bytes.length === want.length && sha(bytes) === sha(want);
    ok &&= !!same;
    landed.push(a?.name ?? "");
  }
  w.json({ t: "sendDone", share: shared.share, ok, did: "saved", landed, failed: 0 });
  check(`${label}: shared, and every byte taken`, ok, `${shared.items.map((x) => `${x.name} ${x.size}`).join(", ")}`);
  return shared;
}

async function sharePart(server, entryWas) {
  console.log("── share");
  const dump = sh(`dumpsys package ${LAUNCHER}`);
  if (!dump.includes(".SendActivity")) {
    console.log("SKIP the share part: the phone's launcher has no SendActivity (this build's is put there by a desktop start, or the Photos tab's Set it up)");
    return;
  }
  const entryOn = () => /enabledComponents:[\s\S]*?SendActivity/.test(sh(`dumpsys package ${LAUNCHER} | grep -A4 enabledComponents`));

  // 10. the entry follows the computer's switch
  let { w, h } = await link(server, true);
  await sleep(1500);
  check("a link that takes shares turns the share entry on", entryOn(), JSON.stringify(h));
  w.close();
  ({ w, h } = await link(server, false));
  await sleep(1500);
  check("…and one that takes none turns it back off", !entryOn());
  w.close();
  ({ w } = await link(server, true));
  await sleep(1500);

  // 11. text
  sh("logcat -c");
  sh(`am start -n ${LAUNCHER}/.SendActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'oadx send test ${RUN}'`);
  const text = await w.next((v) => v.t === "shared" && v.kind === "text", 10000);
  check("text: shared, as text", text?.text === `oadx send test ${RUN}`);
  if (text) w.json({ t: "sendDone", share: text.share, ok: true, did: "clipboard", landed: [], failed: 0 });
  await sleep(1500);
  check("…and the card hears it is done", logcat().some((l) => l.includes(`share ${text?.share} done: clipboard`)));

  // 12. the throwaway sharing app
  let apk;
  try {
    apk = buildTestApp();
  } catch (e) {
    console.log(`SKIP the sharing app: it did not build (${e.message.split("\n")[0]})`);
  }
  if (apk) {
    const inst = spawnSync(ADB, adbArgs("install", "-r", apk), { encoding: "utf8", timeout: 60000 });
    if (inst.status !== 0) {
      console.log("SKIP the sharing app: the phone would not install it (Play Protect may be asking)", (inst.stderr || inst.stdout).trim());
    } else {
      await shareFiles(server, w, [["oadx-one.bin", 1000]], "one file");
      await shareFiles(server, w, [["oadx-a.jpg", 70_000], ["oadx-b.pdf", 3], ["oadx-c.txt", 12345]], "three at once");
      await shareFiles(server, w, [["oadx-empty.bin", 0]], "an empty one");
      const hostile = await shareFiles(server, w, [["../../etc/oadx:pa?ss*.txt", 64]], "a hostile name");
      check("…its name is the phone's word, left for the computer to make safe", hostile?.items?.[0]?.name === "../../etc/oadx:pa?ss*.txt");
      await shareFiles(server, w, [["oadx-big.bin", 64 * 1024 * 1024]], "a 64 MB one");

      // 12a. a file that says it is smaller than it is, through a pipe: the size it said is passed on as a
      // hint (exact: false), and every byte comes — the computer takes what comes, minding its disk
      // (inbox.rs, `copy_watching`).
      const small = await shareFiles(server, w, [["oadx-says-small.bin", 300_000]], "says 1000 bytes", { take: false, says: 1000 });
      if (small) {
        const g = server.connect();
        g.rawAfterFirst = true;
        g.json({ t: "get", id: small.offer, i: 0 });
        const a = await g.next((v) => v.t === "get", 10000);
        const bytes = a?.ok ? pieces(await g.rest(30000)) : Buffer.alloc(0);
        g.close();
        const same = bytes.length === 300_000 && sha(bytes) === sha(pattern(0, 300_000));
        w.json({ t: "sendDone", share: small.share, ok: same, did: "saved", landed: same ? ["oadx-says-small.bin"] : [], failed: same ? 0 : 1 });
        check("…its size is passed on as a hint, not exact, and every byte of it comes", small.items[0]?.size === 1000 && a?.size === 1000 && a?.exact === false && same, `said ${small.items[0]?.size}, answer ${a?.size} exact ${a?.exact}, ${bytes.length} bytes came`);
      }

      // 12b. what a sloppy or hostile app shares: the launcher stays up, and the card says there is nothing to send
      const launcherPid = () => sh(`pidof ${LAUNCHER}`).trim();
      for (const [mode, what] of [["strings", "a list of Strings"], ["parcel", "a Parcelable that is no Uri"]]) {
        const before = launcherPid();
        sh("logcat -c");
        sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es mode ${mode}`);
        await sleep(2500);
        const after = launcherPid();
        const nothing = logcat().some((l) => l.includes("nothing here to send"));
        check(`EXTRA_STREAM as ${what}: the launcher stays up, and its card says there is nothing to send`, !!before && before === after && nothing, `launcher ${before || "?"} then ${after || "gone"}`);
        sh("input keyevent 4");
        await sleep(600);
      }

      // 12c. a link with a picture of its own in ClipData only, as a browser shares a page: the link
      sh("logcat -c");
      const url = `https://example.com/oadx-${RUN}`;
      sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es mode text-and-picture --es text '${url}'`);
      const page = await w.next((v) => v.t === "shared", 10000);
      if (page) w.json({ t: "sendDone", share: page.share, ok: true, did: "offered", landed: [], failed: 0 });
      check("a link with a preview picture beside it: sent as the link", page?.kind === "text" && page.text === url, page ? `${page.kind} ${page.text ?? page.items?.map((x) => x.name)}` : "nothing");
      await sleep(1500);

      // 12d. a tap outside the card while its files are still being opened: nothing is lost
      await tapOutsideCard(server, w);

      // 12e. thousands of knocks from an app, then a share that still arrives
      const before = await shareFiles(server, w, [["oadx-before-knocks.bin", 1000]], "a share before the knocks");
      sh("logcat -c");
      sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es mode knocks --ei n 3000`);
      let knocked = "";
      for (let i = 0; i < 240 && !knocked; i++) {
        await sleep(500);
        knocked = sh("logcat -d -s oadx-sendtest:I").split(/\r?\n/).find((l) => l.includes("knocked")) ?? "";
      }
      const after = knocked ? await shareFiles(server, w, [["oadx-after-knocks.bin", 1000]], "a share after them") : null;
      check("3000 knocks and 100 silent connections, then a share: it still arrives at once", !!after && after.ms < 5000,
        `${knocked.replace(/^.*knocked/, "knocked")}; shared ${before?.ms ?? "?"} ms before, ${after?.ms ?? "?"} ms after`);

      // 12f. a provider that does not answer in time: the launcher lets go after 50 s (its binder thread
      // free again), the share ends as unreadable, and a share meanwhile arrives at once.
      sh("logcat -c");
      sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es files 'oadx-stuck.bin:1000' --es hold 56000`);
      await sleep(2500);
      const meanwhile = await shareFiles(server, w, [["oadx-meanwhile.bin", 1000]], "a share while another's file will not open");
      let stuck = { given: false, unread: false, back: false };
      for (let i = 0; i < 160 && !(stuck.unread && stuck.back); i++) {
        await sleep(500);
        const ls = sh("logcat -d -s OpenDeX:I").split(/\r?\n/);
        stuck = {
          given: ls.some((l) => l.includes("not open after 50 s: given up on")),
          unread: ls.some((l) => l.includes("done: not delivered (unreadable)")),
          back: ls.some((l) => l.includes("came back after it was given up on")),
        };
      }
      check("a file that will not open: given up on after 50 s, the share unreadable, and one meanwhile arrives at once", !!meanwhile && meanwhile.ms < 5000 && stuck.given && stuck.unread,
        `meanwhile in ${meanwhile?.ms ?? "?"} ms; given up ${stuck.given}, unreadable ${stuck.unread}, the open came back and was closed ${stuck.back}`);

      // 13. taken back: Back on its card while the test app holds the file's opening
      sh("logcat -c");
      sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es files 'oadx-held.bin:5000' --es hold 2500`);
      await sleep(900);
      sh("input keyevent 4");
      const said = [];
      const until = Date.now() + 8000;
      while (Date.now() < until) {
        const v = await w.next((v) => v.t === "shared" || v.t === "cancelled", until - Date.now());
        if (!v) break;
        said.push(v);
        if (v.t === "cancelled") break;
      }
      const offered = said.find((v) => v.t === "shared");
      const back = said.find((v) => v.t === "cancelled");
      let gotAfter = null;
      if (offered) {
        const g = server.connect();
        g.json({ t: "get", id: offered.offer, i: 0 });
        gotAfter = await g.next((v) => v.t === "get", 5000);
        g.close();
      }
      check("taken back on its card: nothing is offered, or what was is taken back and can no longer be taken", !offered || (!!back && gotAfter?.ok === false), said.map((v) => v.t).join(", ") || "nothing");

      // …and a link dropped half way through a file: the card hears it was lost
      sh("logcat -c");
      const half = await shareFiles(server, w, [["oadx-half.bin", 32 * 1024 * 1024]], "a file for the dropped link", { take: false });
      if (half) {
        const g = server.connect();
        g.rawAfterFirst = true;
        g.json({ t: "get", id: half.offer, i: 0 });
        await g.next((v) => v.t === "get", 10000);
        await g.rest(10000, 1024 * 1024);
        g.close();
        w.close();
        await sleep(2500);
        check("a link dropped half way: the card hears it was lost", logcat().some((l) => l.includes(`share ${half.share} done: not delivered (lost)`)), logcat().slice(-2).join(" | "));
      }
      ({ w } = await link(server, true));
    }
  }

  // 14. no computer; a computer that takes no shares
  w.close();
  await sleep(800);
  sh("logcat -c");
  sh(`am start -n ${LAUNCHER}/.SendActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'oadx nobody'`);
  await sleep(3500);
  check("with no link: the card says no computer is connected", logcat().some((l) => l.includes("no computer is connected")));
  sh("input keyevent 4");
  ({ w } = await link(server, false));
  await sleep(800);
  // That link took the entry away, as it should; a share already on its way
  // (another computer's entry, a switch turned off mid-way) still reaches it.
  sh(`content call --uri content://${LAUNCHER}.apps --method share.enable --arg '{"on":true}'`);
  sh("logcat -c");
  sh(`am start -n ${LAUNCHER}/.SendActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'oadx not taken'`);
  await sleep(3000);
  check("with a link that takes no shares: the card says it is turned off", logcat().some((l) => l.includes("takes no shares: 1 refused")));
  sh("input keyevent 4");
  w.close();
  // The entry as it was found.
  sh(`content call --uri content://${LAUNCHER}.apps --method share.enable --arg '{"on":${entryWas}}'`);
  check("the share entry is left as it was found", entryOn() === entryWas);
}

/**
 * A tap outside the card while its files are still being opened (the test app
 * holds the opening 2.5 s): the card stays, and the share still arrives. Taps
 * only when the card itself is in front on the phone's own screen, awake and
 * unlocked; otherwise skipped.
 */
async function tapOutsideCard(server, w) {
  sh("logcat -c");
  sh(`am start -f 0x10008000 -n ${TEST_APP}/.Share --es files 'oadx-tapped.bin:5000' --es hold 2500`);
  await sleep(700);
  const top = sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:' | head -3");
  const awake = /mWakefulness=Awake/.test(sh("dumpsys power | grep mWakefulness="));
  const locked = /mShowingLockscreen=true|isStatusBarKeyguard=true|mKeyguardShowing=true/.test(sh("dumpsys window | grep -E 'mShowingLockscreen|isStatusBarKeyguard|mKeyguardShowing' | head -3"));
  if (!top.includes(`${LAUNCHER}/.SendActivity`) || !awake || locked) {
    console.log(`SKIP a tap outside the card: the card is not in front on an awake, unlocked screen (${top.trim().split(/\r?\n/)[0] ?? ""})`);
    const v = await w.next((v) => v.t === "shared", 10000);
    if (v) w.json({ t: "sendDone", share: v.share, ok: false, did: "saved", landed: [], failed: 1, message: "" });
    sh("input keyevent 4");
    return;
  }
  // Near the top of the screen: the dimmed app behind the card, which sits at the bottom.
  const size = sh("wm size").match(/(\d+)x(\d+)/);
  const x = size ? Math.round(Number(size[1]) / 2) : 500;
  const y = size ? Math.round(Number(size[2]) * 0.12) : 300;
  sh(`input -d 0 tap ${x} ${y}`);
  await sleep(300);
  // Android 12 says mResumedActivity, with no topResumedActivity: the same lines the card was found by above.
  const stays = sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity:' | head -3").includes(`${LAUNCHER}/.SendActivity`);
  const shared = await w.next((v) => v.t === "shared", 15000);
  let ok = false;
  if (shared) {
    const g = server.connect();
    g.rawAfterFirst = true;
    g.json({ t: "get", id: shared.offer, i: 0 });
    const a = await g.next((v) => v.t === "get", 10000);
    const bytes = a?.ok ? pieces(await g.rest(20000)) : Buffer.alloc(0);
    g.close();
    ok = sha(bytes) === sha(pattern(0, 5000));
    w.json({ t: "sendDone", share: shared.share, ok, did: "saved", landed: ok ? ["oadx-tapped.bin"] : [], failed: ok ? 0 : 1 });
  }
  await sleep(1500);
  const said = logcat();
  check("a tap outside the card before its files are open: the card stays, the share still arrives, and the card hears it", stays && ok && said.some((l) => l.includes(`share ${shared?.share} done: saved`)), `${stays ? "stayed" : "went"}; ${said.slice(-1).join("")}`);
}

/** The one phone this test runs on: the serial given, or the only one attached. Never whatever adb picks. */
function onePhone() {
  if (serialArg) return true;
  const devices = spawnSync(ADB, ["devices"], { encoding: "utf8" }).stdout.split(/\r?\n/).filter((l) => /\tdevice$/.test(l)).map((l) => l.split("\t")[0]);
  if (devices.length === 1) return true;
  console.error(devices.length
    ? `More than one device is attached (${devices.join(", ")}): name the one to test, as in node test/send-test.mjs <serial>.`
    : "No device is attached.");
  return false;
}

async function main() {
  if (!onePhone()) process.exit(2);
  if (!fs.existsSync(DEX)) {
    console.error(`No ${DEX}: build it first (openandroiddex-wmd/build.sh, or npm run apk).`);
    process.exit(2);
  }
  adb("push", DEX, PHONE_DEX);
  const shotsWere = sh(`ls -d /sdcard/${SHOTS} 2>/dev/null`).trim() !== "";
  const entryWas =/enabledComponents:[\s\S]*?SendActivity/.test(sh(`dumpsys package ${LAUNCHER} | grep -A4 enabledComponents`));
  const server = new Server();
  try {
    await server.ready();
    if (only !== "share") await photosPart(server, entryWas);
    if (only !== "photos") await sharePart(server, entryWas);
  } catch (e) {
    check("the run finished", false, String(e && e.stack ? e.stack.split("\n").slice(0, 3).join(" ") : e));
  } finally {
    await server.stop();
    // 9 and 15: nothing is left — the folders, the dex, the test app.
    // Pictures/Screenshots too, if this run made it (a Samsung keeps its screenshots in DCIM).
    sh(`rm -rf /sdcard/${STAGE}; rm -f /sdcard/${SHOTS}/oadx-test-${RUN}-*; ${shotsWere ? "" : `rmdir /sdcard/${SHOTS} 2>/dev/null;`} rm -f ${PHONE_DEX}`);
    if (sh(`pm list packages ${TEST_APP}`).includes(TEST_APP) && !process.env.KEEP_TEST_APP) adb("uninstall", TEST_APP);
    fs.rmSync(path.join(here, "send", "build"), { recursive: true, force: true });
    await sleep(1500);
    const rows = sh(`content query --uri content://media/external/images/media --projection _id --where "_display_name LIKE 'oadx-test-${RUN}%'"`);
    check("nothing is left: the test's folders, its rows, the dex", !rows.includes("_id=") && !sh(`ls -d /sdcard/${STAGE} ${PHONE_DEX} 2>/dev/null`).trim());
    check("…and the sharing app is gone from the phone", !sh(`pm list packages ${TEST_APP}`).includes(TEST_APP));
  }
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main();
