#!/usr/bin/env node
// The windows' Mute (doc/apps-on-pc.md, "Mute"): its rules on this computer,
// and, given a phone, on the phone itself.
//
//   node test/apps-mute-test.mjs            its rules (AppMute.java), with a stand-in phone
//   node test/apps-mute-test.mjs <serial>   ...and on the phone, over adb
//
// The rules, on this computer's JVM (test/apps-mute/MuteRules.java): on and
// off, the hold for the computer's capture (begun, never, and a phone that
// cannot say), a Mute ended by itself, a mute the phone had of its own,
// unmutes that do not take — kept marked for the next server — and a server
// replaced by another, which owns the phone's sound from then on.
//
// On the phone: the window daemon's dex (openandroiddex-wmd, built by its
// build script) run under app_process as the app runs it and spoken to on the
// wire as apps-reconnect-test.mjs speaks to it, beside a helper
// (test/apps-mute/MuteHelper.java) that says the music stream's state and
// captures the phone's output as the companion does (REMOTE_SUBMIX). What
// AudioManager's list of recordings shows of that capture is said as a note.
// It mutes the phone's media for seconds at a time and opens Settings in
// windows of its own, which go back to the phone. It will not run while a
// Mute is on (some server's mark is there: its servers would undo it as they
// start); media muted before it is unmuted for the run and muted again after;
// by the end the phone is as it was, and nothing it pushed is left — a mark
// is removed only if one of this run's own servers, by name, left it, and the
// servers' lock file, which the app's servers make and share, is left be.
// About a minute.
//
//   node test/apps-mute-test.mjs --mutants  each defect the audits found put back into a copy
//                                            of AppMute.java, which the rules must then fail
//
// Needs a JDK (JAVA_HOME, or javac and java on PATH); for the phone also the
// Android SDK (ANDROID_HOME, else its usual place) for android.jar and d8, and
// adb — the app's own (resources/bin) when there is one; ADB=… overrides it.
// Give it the running app's adb, so its adb server is not restarted under it.

import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const WMD = path.join(root, "openandroiddex-wmd");
const WMD_SRC = path.join(WMD, "src", "com", "ccrstech", "openandroiddex", "wmd");
const serialArg = process.argv[2];
const win = process.platform === "win32";

const bin = (tool) => (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", tool) : tool);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${detail && !ok ? ` — ${detail}` : detail ? `  (${detail})` : ""}`);
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-mute-"));
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

// ── the rules, on this computer ──

console.log("the rules, on this JVM");
{
  const out = path.join(tmp, "rules");
  const javac = spawnSync(bin("javac"), ["-nowarn", "-d", out, path.join(WMD_SRC, "AppMute.java"), path.join(here, "apps-mute", "MuteRules.java")], {
    encoding: "utf8",
  });
  if (javac.status !== 0) {
    console.log((javac.stdout || "") + (javac.stderr || "") + (javac.error ? String(javac.error) : ""));
    check("AppMute compiles on a JVM", false);
    process.exit(1);
  }
  const run = spawnSync(bin("java"), ["-cp", out, "com.ccrstech.openandroiddex.wmd.MuteRules"], { encoding: "utf8" });
  for (const line of (run.stdout || "").split(/\r?\n/)) {
    if (line.startsWith("  ok")) passed++;
    else if (line.startsWith("  FAIL")) failed++;
    if (line && !/^\d+\/\d+ passed$/.test(line)) console.log(line);
  }
  if (run.status !== 0 && !failed) check("the rules ran", false, run.stderr || String(run.error));
}

// ── the rules' own check: each defect the audits found, put back ──

// Into a copy of AppMute.java (never the file itself), one at a time: the rules
// must fail for each. (The fourth audit finding of the first round is Rust's:
// apps::host::tests::an_answer_heard_late_does_not_undo_a_lift_said_since.)
const MUTANTS = [
  ["no owner check (an earlier server's end undoing a newer server's mute)", [["if (ownOnly && !mine) {", "if (false) {"]]],
  [
    "the mark dropped when an unmute is not confirmed",
    [["                return false;\n            }\n            if (mine) dropMark();", "                if (mine) dropMark();\n                return false;\n            }\n            if (mine) dropMark();"]],
  ],
  ["a phone that cannot say taken as capturing", [["unsure |= capturing == null;", "if (capturing == null) { began = true; break; }"]]],
  [
    "no turn between servers at all",
    [
      ["held = file.getChannel().lock();", "held = null;"],
      ["synchronized (PROCESS) {", "synchronized (new Object()) {"],
    ],
  ],
  ["the file lock alone gone (a process's own threads still take turns)", [["held = file.getChannel().lock();", "held = null;"]]],
  [
    "the read-back after an unmute unguarded, and a hold's thread not catching",
    [
      ["? read() : Boolean.TRUE;", "? (Boolean) phone.muted() : Boolean.TRUE;"],
      ["                if (generation == asked) {\n                    holding = false;\n                    on = true;\n                    tell = true;\n                }\n", ""],
    ],
  ],
  ["a mark once lost taken as this server's over a newer server's", [["return m == null ? markLost : owner.equals(m);", "return markLost || owner.equals(m);"]]],
  ["a change made with no turn to be had", [["close(file);\n                return without;", "close(file);\n                return body.get();"]]],
  ["a mute not read back taken as done", [["if (called && Boolean.TRUE.equals(now)) {", "if (called && !Boolean.FALSE.equals(now)) {"]]],
  ["a state not read before muting taken as sound on", [['if (was == null) return "the phone will not say whether its media is muted";', "if (was == null) was = Boolean.FALSE;"]]],
  ["a failed mute's mark dropped without reading the phone", [['if (own && !holding) settle("a mute that was not confirmed");', "if (own && !holding) dropMark();"]]],
];

if (serialArg === "--mutants") {
  console.log("\neach defect put back into a copy of AppMute.java");
  const original = fs.readFileSync(path.join(WMD_SRC, "AppMute.java"), "utf8").replace(/\r\n/g, "\n");
  for (const [name, edits] of MUTANTS) {
    let code = original;
    const lost = edits.find(([from]) => !code.includes(from));
    if (lost) {
      check(`${name}: it can be put back`, false, `no "${lost[0].slice(0, 50)}" in AppMute.java`);
      continue;
    }
    for (const [from, to] of edits) code = code.replace(from, to);
    const dir = fs.mkdtempSync(path.join(tmp, "mutant-"));
    fs.writeFileSync(path.join(dir, "AppMute.java"), code);
    const out = path.join(dir, "out");
    const c = spawnSync(bin("javac"), ["-nowarn", "-d", out, path.join(dir, "AppMute.java"), path.join(here, "apps-mute", "MuteRules.java")], { encoding: "utf8" });
    if (c.status !== 0) {
      check(`${name}: it compiles, put back`, false, (c.stderr || "").split(/\r?\n/)[0]);
      continue;
    }
    const r = spawnSync(bin("java"), ["-cp", out, "com.ccrstech.openandroiddex.wmd.MuteRules"], { encoding: "utf8", timeout: 120000 });
    const fails = (r.stdout || "").split(/\r?\n/).filter((l) => l.startsWith("  FAIL")).map((l) => l.slice(7).split(" — ")[0]);
    check(`caught: ${name}`, fails.length > 0, `the rules all passed${r.status ? ` (exit ${r.status})` : ""}`);
    if (fails.length) console.log(`          by: ${fails.slice(0, 3).join(" | ")}${fails.length > 3 ? ` | …${fails.length - 3} more` : ""}`);
  }
  console.log(`\n${passed}/${passed + failed} passed`);
  process.exit(failed ? 1 : 0);
}

if (!serialArg) {
  console.log(`\n${passed}/${passed + failed} passed (give a phone's serial to check it on the phone too, or --mutants)`);
  process.exit(failed ? 1 : 0);
}

// ── on the phone ──

const exe = win ? "adb.exe" : "adb";
const bundled = path.join(root, "open-android-dex-tauri", "src-tauri", "resources", "bin", exe);
const sdk =
  process.env.ANDROID_HOME ||
  (win ? path.join(process.env.LOCALAPPDATA || "", "Android", "Sdk") : path.join(os.homedir(), "Library", "Android", "sdk"));
const ADB = process.env.ADB || (fs.existsSync(bundled) ? bundled : path.join(sdk, "platform-tools", exe));
const PHONE_DEX = "/data/local/tmp/oadx-mute-test.dex";
const HELPER_DEX = "/data/local/tmp/oadx-mute-helper.dex";
const MARK = "/data/local/tmp/oadx-apps-muted";
// How a hold for the computer's capture ends, in the phone's words (AppMute's release reasons).
const PLAYS_AGAIN = /the phone's media plays again/;
const CAPTURE_BEGAN = /the computer's capture began \d+ ms after the unmute/;
/** Held the whole time: no capture came, or the phone could not say whether one did. */
const HELD_THROUGH = /no capture began in \d+ ms|could not say when the capture begins; held \d+ ms/;

const adbArgs = (...a) => ["-s", serialArg, ...a];
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";

/** The newest of `dir`'s entries that `ok` takes, by its version-like name. */
function newest(dir, ok) {
  if (!fs.existsSync(dir)) return null;
  const v = (s) => s.split(/[^0-9]+/).filter(Boolean).map(Number);
  const names = fs.readdirSync(dir).filter((n) => ok(path.join(dir, n)));
  names.sort((a, b) => {
    const [x, y] = [v(a), v(b)];
    for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
    return 0;
  });
  return names.length ? path.join(dir, names[names.length - 1]) : null;
}

/** A tool that may be a .bat on Windows, which Node runs only through the shell. */
function runTool(tool, args) {
  if (win && /\.bat$/i.test(tool)) {
    return spawnSync(`"${tool}" ${args.map((a) => `"${a}"`).join(" ")}`, { encoding: "utf8", shell: true });
  }
  return spawnSync(tool, args, { encoding: "utf8" });
}

console.log(`\non the phone ${serialArg}`);
const platform = fs.existsSync(path.join(sdk, "platforms", "android-36", "android.jar"))
  ? path.join(sdk, "platforms", "android-36")
  : newest(path.join(sdk, "platforms"), (p) => fs.existsSync(path.join(p, "android.jar")));
const buildTools = newest(path.join(sdk, "build-tools"), (p) => fs.existsSync(path.join(p, win ? "d8.bat" : "d8")));
const classes = path.join(WMD, "build", "classes");
const dex = path.join(WMD, "openandroiddex-wmd.dex");
if (!platform || !buildTools) {
  console.log(`  FAIL the Android SDK at ${sdk} has android.jar and d8`);
  process.exit(1);
}
if (!fs.existsSync(classes) || !fs.existsSync(dex)) {
  console.log(`  FAIL ${dex} and its build/classes: build them first (openandroiddex-wmd/build.cmd or build.sh)`);
  process.exit(2);
}
const model = adb("shell", "getprop", "ro.product.model").trim();
if (!model) {
  console.log(`  FAIL a phone answers on adb (${ADB})`);
  process.exit(1);
}
console.log(`phone: ${model}, Android ${adb("shell", "getprop", "ro.build.version.release").trim()}`);

{
  const out = path.join(tmp, "helper");
  const jar = path.join(platform, "android.jar");
  const javac = spawnSync(
    bin("javac"),
    ["-nowarn", "-Xlint:-options", "-source", "17", "-target", "17", "-cp", [jar, classes].join(path.delimiter), "-d", out, path.join(here, "apps-mute", "MuteHelper.java")],
    { encoding: "utf8" },
  );
  if (javac.status !== 0) {
    console.log((javac.stdout || "") + (javac.stderr || ""));
    check("the phone's helper compiles", false);
    process.exit(1);
  }
  const files = [];
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : e.name.endsWith(".class") && files.push(path.join(d, e.name))));
  walk(out);
  const d8 = runTool(path.join(buildTools, win ? "d8.bat" : "d8"), ["--min-api", "26", "--lib", jar, "--classpath", classes, "--output", tmp, ...files]);
  if (d8.status !== 0) {
    console.log((d8.stdout || "") + (d8.stderr || ""));
    check("the phone's helper dexes", false);
    process.exit(1);
  }
  adb("push", dex, PHONE_DEX);
  adb("push", path.join(tmp, "classes.dex"), HELPER_DEX);
}

/** The helper's STATE lines: {tag: {muted, volume, submix, listed}}. */
function parseStates(text) {
  const states = {};
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^STATE (\S+) muted=(\w+) volume=(\d+) submix=(\w+) listed=(\S+)/);
    if (m) states[m[1]] = { muted: m[2] === "true", volume: Number(m[3]), submix: m[4], listed: m[5] };
  }
  return states;
}
const helperCmd = (...a) => ["shell", `CLASSPATH=${PHONE_DEX}:${HELPER_DEX} exec app_process / com.ccrstech.openandroiddex.wmd.MuteHelper ${a.join(" ")}`];
const stream = () => parseStates(spawnSync(ADB, adbArgs(...helperCmd("state")), { encoding: "utf8" }).stdout || "").now;
/** A capture of the phone's output for `ms`, as the companion's: its STATE lines when it is done. */
function captureFor(ms) {
  const child = spawn(ADB, adbArgs(...helperCmd("capture", String(ms))), { stdio: ["ignore", "pipe", "inherit"] });
  let text = "";
  child.stdout.on("data", (b) => (text += b.toString()));
  return new Promise((r) => child.on("exit", () => r({ text, states: parseStates(text) })));
}
const mark = () => {
  const t = adb("shell", `cat ${MARK} 2>/dev/null || echo -`).trim();
  return t === "-" ? null : t;
};

/** The phone's app server, as host.rs starts it, on a socket of its own. */
class Server {
  constructor() {
    this.name = "oadx-mute-" + randomBytes(4).toString("hex");
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
    this.control = new Wire(this.port);
    this.control.json({ t: "control" });
    this.hello = await this.control.first();
    this.sound = (await this.control.until("audio")).v;
  }
  /** Where its next line will be: said, line and waitFor given it look only at what it says from there on. */
  mark() {
    return this.lines.length;
  }
  /** The first line since `from` that says `text` (words, or a RegExp), or "". */
  line(text, from = 0) {
    const says = typeof text === "string" ? (l) => l.includes(text) : (l) => text.test(l);
    return this.lines.slice(from).find(says) ?? "";
  }
  said(text, from = 0) {
    return this.line(text, from) !== "";
  }
  async waitFor(text, ms, from = 0) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      if (this.said(text, from)) return true;
      await sleep(100);
    }
    return this.said(text, from);
  }
  /** Ask for the Mute: the phone's answer, and its word of the sound after it. */
  async mute(on, afterCapture = false) {
    this.control.json({ t: "mute", on, afterCapture });
    const { v: answer } = await this.control.until("muted");
    const { v: sound } = await this.control.until("audio");
    return { answer, sound };
  }
  async window() {
    const w = new Wire(this.port);
    w.json({ t: "open", pkg: "com.android.settings", activity: "", w: 400, h: 800, dpi: 160, fps: 30, onClose: "phone", direct: false, key: randomBytes(12).toString("hex") });
    const { v: display } = await w.until("display");
    if (display?.t !== "display") throw new Error("no window: " + JSON.stringify(display));
    await w.until("video");
    return w;
  }
  stop() {
    try {
      this.child.stdin.write("stop\n");
    } catch {}
  }
  done() {
    this.control?.close();
    if (this.port) spawnSync(ADB, adbArgs("forward", "--remove", `tcp:${this.port}`));
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
  async read(ms = 15000) {
    const until = Date.now() + ms;
    while (!this.queue.length && !this.ended && Date.now() < until) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(until - Date.now())]);
    }
    return this.queue.shift() ?? null;
  }
  /** The next JSON message of type `t` (or a window's `state`). */
  async until(t, ms = 15000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return { v: null };
      if (m.kind !== 0x01) continue;
      const v = JSON.parse(m.data.toString());
      if (v.t === t || v.t === "state") return { v };
    }
    return { v: null };
  }
  async first() {
    const m = await this.read();
    return m && m.kind === 0x01 ? JSON.parse(m.data.toString()) : m;
  }
  close() {
    this.socket.destroy();
  }
}

const helper = (...a) => spawnSync(ADB, adbArgs(...helperCmd(...a)), { encoding: "utf8" });
const before = stream();
if (!before) {
  console.log("  FAIL the helper says the phone's music stream");
  adb("shell", `rm -f ${PHONE_DEX} ${HELPER_DEX}`);
  process.exit(1);
}
// A Mute some server holds is that server's: this test's servers would undo it as they start.
const theirs = mark();
if (theirs !== null) {
  console.log(`  FAIL no Mute on the phone already — ${theirs} holds one: turn it off in its window first; this test leaves it be`);
  adb("shell", `rm -f ${PHONE_DEX} ${HELPER_DEX}`);
  process.exit(1);
}
console.log(`the phone's media: ${before.muted ? "muted (unmuted for the run, muted again after)" : "playing"}, volume ${before.volume}`);
if (before.muted) helper("unmute");
const servers = [];
/** A mark this run's own servers made — named exactly, not by the test's prefix: another run's is its own. */
const ours = (m) => m !== null && servers.some((s) => s.name === m);
try {
  // Its own: what the phone says of a capture of its output.
  const seen = await captureFor(1200);
  check("the audio policy says when the phone's output is captured", seen.states.capturing?.submix === "true" && seen.states["after-capture"]?.submix === "false", seen.text.replace(/\s+/g, " "));
  const listed = seen.states.capturing?.listed ?? "?";
  console.log(
    `  note  AudioManager's recordings while it ran: ${listed.split(",").includes("8") ? "the capture (REMOTE_SUBMIX, 8) among them" : "not the capture"} (sources: ${listed === "-" ? "none" : listed})`,
  );

  const a = new Server();
  servers.push(a);
  await a.ready();
  check("the phone's word of its sound says the Mute, off", a.sound?.muted === false, JSON.stringify(a.sound)?.slice(0, 120));
  let r = await a.mute(true);
  check("on with no window open: refused", r.answer?.ok === false && /no window/.test(r.answer?.message) && r.answer?.muted === false && !stream().muted, JSON.stringify(r.answer));

  const wa = await a.window();
  r = await a.mute(true);
  check("on: the answer and the word after it say muted", r.answer?.ok === true && r.answer?.muted === true && r.sound?.muted === true, JSON.stringify(r.answer));
  check("the phone's media is muted, and the mark names the server", stream().muted && mark() === a.name, `mark ${mark()}`);

  // Off, held for a capture that starts a second later, as the companion's does. What the
  // phone says of each hold is read from that hold's own start: "no capture began" holds the
  // words "capture began" too, and a hold's line from a step before would answer for this one.
  const sinceCapture = a.mark();
  r = await a.mute(false, true);
  check("off for a capture: the Mute is off at once", r.answer?.ok === true && r.answer?.muted === false && r.sound?.muted === false, JSON.stringify(r.answer));
  check("the media still silent while no capture runs", stream().muted);
  await sleep(500);
  const capture = captureFor(2500);
  const began = await a.waitFor(CAPTURE_BEGAN, 8000, sinceCapture);
  check("it plays once the capture runs", began, a.line(PLAYS_AGAIN, sinceCapture) || "the phone said nothing of its media playing");
  const ran = await capture;
  check("…and while the capture still runs: unmuted, the mark gone", ran.states["capturing-end"]?.muted === false && mark() === null, ran.text.replace(/\s+/g, " "));

  // Off for a capture that never comes: held the whole time, then played.
  await a.mute(true);
  const sinceHold = a.mark();
  const t0 = Date.now();
  await a.mute(false, true);
  await sleep(2000);
  const held = stream().muted;
  const gave = await a.waitFor(HELD_THROUGH, 9000, sinceHold);
  check(
    "no capture: held, then played after the hold",
    held && gave && !stream().muted && Date.now() - t0 >= 5500,
    `${((Date.now() - t0) / 1000).toFixed(1)} s; ${a.line(PLAYS_AGAIN, sinceHold) || "the phone said nothing of its media playing"}`,
  );

  // A second server, as a computer starting again makes one while the first still waits.
  r = await a.mute(true);
  const b = new Server();
  servers.push(b);
  await b.ready();
  check("a new server undoes the earlier one's mute as it starts", b.said(`left muted by server ${a.name}`) && !stream().muted && mark() === null, b.lines.filter((l) => l.includes("sound")).join(" | "));
  const wb = await b.window();
  r = await b.mute(true);
  check("and mutes it as its own", r.answer?.ok === true && stream().muted && mark() === b.name, `mark ${mark()}`);
  const sinceClose = a.mark();
  wa.json({ t: "close", app: "phone" });
  const lifted = await a.waitFor("another server's to unmute now", 10000, sinceClose);
  check("the earlier server's last window closing leaves the later one's mute alone", lifted && stream().muted && mark() === b.name, `mark ${mark()}`);
  r = await b.mute(false);
  check("the later one unmutes it", r.answer?.ok === true && !stream().muted && mark() === null);

  // Its last window closing lifts the Mute, and the phone says so. (A server
  // killed while muted is the rules' to check: killed, its window's app would
  // land on top of the phone's own screen.)
  await b.mute(true);
  wb.json({ t: "close", app: "phone" });
  const { v: after } = await b.control.until("audio", 10000);
  check("the last window closing lifts it, and the phone says so", after?.muted === false && !stream().muted && mark() === null, JSON.stringify(after)?.slice(0, 120));
  const alone = servers.filter((s) => s.said("no turn-taking"));
  check("the servers took turns on the phone's lock, none going on alone", !alone.length, alone.map((s) => s.lines.find((l) => l.includes("no turn-taking"))).join(" | "));
} catch (e) {
  check("the run went to its end", false, String(e?.stack || e));
} finally {
  for (const s of servers) s.stop();
  await sleep(2500);
  for (const s of servers) s.done();
  // As it was, either way: muted again if it was, playing if it was.
  let end = stream();
  if (end && end.muted !== before.muted) {
    helper(before.muted ? "mute" : "unmute");
    end = stream();
  }
  // Only a mark this run's servers left (there was none before it). The lock
  // file beside it stays: it is the servers' to take turns on, any server's,
  // and one deleted under a server that holds it would let two in at once.
  if (ours(mark())) adb("shell", `rm -f ${MARK}`);
  check(
    "the phone as it was: its media muted or not, its volume, no mark of this run's",
    end?.muted === before.muted && end?.volume === before.volume && !ours(mark()),
    `${JSON.stringify(before)} -> ${JSON.stringify(end)}`,
  );
  adb("shell", `rm -f ${PHONE_DEX} ${HELPER_DEX}`);
}

console.log(`\n${passed}/${passed + failed} passed`);
process.exit(failed ? 1 : 0);
