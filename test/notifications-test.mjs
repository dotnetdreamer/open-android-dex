#!/usr/bin/env node
// The phone's half of its notifications on the computer
// (doc/phone-notifications.md), checked on a phone or an emulator over adb,
// with nothing of the app's running:
//
//   node test/notifications-test.mjs [serial]
//
// Pushes the window daemon's dex (openandroiddex-wmd/openandroiddex-wmd.dex,
// built by its build script or `npm run apk`) to a path of its own, runs the
// app server under app_process as the app does, and speaks the control wire to
// it through an adb forward: notifications turned on (a snapshot, nothing
// already there as news), the shell's own `cmd notification post` as the app
// posting (news once, a repost not, a change alerting again only after a
// while), a conversation as its messages, a picture, a dismissal both ways,
// the test notification, a computer asking again, the listener stopping with
// the connection that asked and with one that takes its place, and
// notifications turned off. On an emulator, from its Messages and its phone,
// what each of those did is read back from the phone itself: a window opened
// at a notification (that conversation read, another not), a reply (in the
// sent messages), "Mark as read" (read), and a call answered (active),
// declined (gone) and hung up. Every notification it posts is gone again
// when it finishes.
//
// Uses the app's own adb (resources/bin) when there is one, so a phone shared
// with a running app is not dropped by an adb server of another version; ADB=…
// overrides it. Needs Node 18+.

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
const PHONE_DEX = "/data/local/tmp/oadx-notifications-test.dex";
const serialArg = process.argv[2];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adbArgs = (...a) => (serialArg ? ["-s", serialArg, ...a] : a);
const adb = (...a) => spawnSync(ADB, adbArgs(...a), { encoding: "utf8" }).stdout || "";
/** `cmd notification post`, as the shell: the app posting, for this test. */
const post = (...a) => adb("shell", "cmd", "notification", "post", ...a.map((s) => `'${s.replace(/'/g, "'\\''")}'`));

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "PASS" : "FAIL"} ${name}${detail ? `  (${detail})` : ""}`);
}

/** The phone's app server, as host.rs starts it for notifications, on a socket of its own. */
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
  /** A line of the server's saying `text`, from line `from` on (all it has said, by default). */
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
  async read(ms = 15000) {
    const until = Date.now() + ms;
    while (!this.queue.length && !this.ended && Date.now() < until) {
      await Promise.race([new Promise((r) => (this.waiting = r)), sleep(Math.max(0, until - Date.now()))]);
    }
    return this.queue.shift() ?? null;
  }
  /** The next JSON message that `want` takes, skipping others (video, the sound's word, logs); null if none in `ms`. */
  async next(want, ms = 15000) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      const m = await this.read(until - Date.now());
      if (!m) return null;
      if (m.kind !== 0x01) continue;
      const v = JSON.parse(m.data.toString());
      if (want(v)) return v;
    }
    return null;
  }
  /**
   * The next message each of `wants` takes, in whatever order they come — a
   * phone may say what it did before it says it is done — null for one that
   * none did in `ms`.
   */
  async each(wants, ms = 15000) {
    const got = wants.map(() => null);
    const until = Date.now() + ms;
    const open = (v) => wants.findIndex((w, i) => got[i] === null && w(v));
    while (got.includes(null) && Date.now() < until) {
      const v = await this.next((v) => open(v) >= 0, until - Date.now());
      if (!v) break;
      got[open(v)] = v;
    }
    return got;
  }
  close() {
    this.socket.destroy();
  }
}

const notif = (kind, tag) => (v) => v.t === "notif" && v.kind === kind && (!tag || (v.key || "").includes(`|${tag}|`));
const done = (what) => (v) => v.t === "notifDone" && v.what === what;

/**
 * A real app's notifications: the emulator's Messages, each SMS sent to it
 * over the emulator's console from a number of its own, so each is a
 * conversation, and a notification, of its own.
 */
async function messages(server, control) {
  const MESSAGES = "com.google.android.apps.messaging";
  // This run's own words, so what the phone's messages say is this run's and no earlier one's.
  const run = randomBytes(2).toString("hex");
  const sms = (from, text) => spawnSync(ADB, adbArgs("emu", "sms", "send", from, text));
  const news = (pattern) => (v) => v.t === "notif" && v.kind === "posted" && v.pkg === MESSAGES && pattern.test(v.text);
  const gone = (key) => (v) => v.t === "notif" && v.kind === "removed" && v.key === key;
  /** The phone's own record of its texts (the telephony provider, which Messages keeps): each row's columns. */
  const texts = (box, ...columns) =>
    adb("shell", "content", "query", "--uri", `content://sms/${box}`, "--projection", columns.join(":"))
      .split(/\r?\n/)
      .filter((l) => l.startsWith("Row:"))
      .map((l) => Object.fromEntries(columns.map((c) => [c, (l.match(new RegExp(`\\b${c}=(.*?)(?:, \\w+=|$)`)) ?? [])[1]])));
  /** Whether the text that says `words` has been read, as Messages records it; undefined if there is none. */
  const isRead = (words) => texts("inbox", "read", "body").find((t) => (t.body ?? "").includes(words))?.read;
  const until = async (ms, test) => {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      if (test()) return true;
      await sleep(300);
    }
    return test();
  };

  // Opened in a window of its own, at the conversation — that one, not another.
  sms("5550104", `Call me later (${run})`);
  const other = await control.next(news(/Call me later/), 20000);
  sms("5550101", `Are you coming for dinner? (${run})`);
  const m1 = await control.next(news(/dinner/), 20000);
  check("an SMS arrives as news, from Messages", m1?.alert === true && m1.category === "msg", JSON.stringify(m1 && { title: m1.title, text: m1.text, alert: m1.alert }));
  const reply = m1?.actions?.find((a) => a.reply);
  check("…with the app's own buttons, a reply among them", !!reply && m1.actions.some((a) => !a.reply), JSON.stringify(m1?.actions?.map((a) => a.label)));
  check("…and something to open", m1?.open === true);
  const win = server.connect();
  const winKey = "notif-test-" + randomBytes(3).toString("hex");
  const from = server.lines.length;
  win.json({ t: "open", pkg: MESSAGES, activity: "", w: 412, h: 892, dpi: 160, fps: 30, onClose: "phone", direct: true, key: winKey, notif: m1?.key ?? "" });
  const shown = await win.next((v) => v.t === "display" || v.t === "state", 15000);
  check("a window opens at the notification", shown?.t === "display", JSON.stringify(shown));
  // On a trusted display, by the notification's own intent onto it; on an
  // untrusted one (Android 12), by the app, and then that intent, into its task.
  const launched = (l) => /apps: window \d+: .* on display /.test(l);
  await server.waitFor("ms by ", 4000, from);
  const how = server.lines.slice(from).find(launched) ?? "";
  check("…by the notification's own intent", /by its notification|, then its notification/.test(how) && !how.includes("gone elsewhere"), how.replace(/^.*?: (display \d+ ms)/, "$1"));
  await sleep(800);
  /**
   * Whether Messages shows on display `id`: a task of its there that is
   * visible, not one kept behind — read from that display's own part of the
   * dump, which ends where the supervisor's (every display's tasks) begins.
   */
  const showing = (id) =>
    (adb("shell", "dumpsys", "activity", "activities").split("Display #").find((d) => d.startsWith(`${id} `)) ?? "")
      .split(/\n\s*Resumed activities in task display areas|\nActivity(?:Task|Stack)Supervisor state:/)[0]
      .split("\n")
      .some((l) => l.includes("Task{") && l.includes(MESSAGES) && l.includes(" visible=true"));
  check("…the conversation on the window's display, not the phone's screen", showing(shown?.id) && !showing(0), `window's: ${showing(shown?.id)}, phone's: ${showing(0)}`);
  // Messages records a conversation read when it is on screen: this one, and not the other one waiting.
  const opened = await until(6000, () => isRead(`dinner? (${run})`) === "1");
  check("…at that conversation: Messages has it read, and the other one still waiting", opened && isRead(`Call me later (${run})`) === "0", `dinner read=${isRead(`dinner? (${run})`)}, other read=${isRead(`Call me later (${run})`)}`);
  check("…and leaves the phone, as the app's auto-cancel says", (await control.next(gone(m1?.key), 5000)) !== null);
  // A click on another of its notifications, with the window open: that conversation, in that window.
  control.json({ t: "notifOpen", key: other?.key ?? "", window: winKey, freeform: false });
  const [reopened] = await control.each([done("notifOpen")], 8000);
  check("a click with the app's window open opens it there", reopened?.ok === true, reopened?.message ?? "");
  const there = await until(6000, () => isRead(`Call me later (${run})`) === "1");
  const [inWindow, onPhone] = [showing(shown?.id), showing(0)];
  check("…at that conversation, in the same window, not on the phone's screen", there && inWindow && !onPhone, `read=${isRead(`Call me later (${run})`)}, window's: ${inWindow}, phone's: ${onPhone}`);
  win.json({ t: "close", app: "phone" });
  await win.read(3000);
  win.close();
  control.json({ t: "notifDismiss", key: other?.key ?? "" });
  await control.next(done("notifDismiss"), 5000);

  // Replied to from the computer: in the phone's sent messages, to that number.
  sms("5550102", `What time should we come? (${run})`);
  const m2 = await control.next(news(/What time/), 20000);
  const r = m2?.actions?.find((a) => a.reply);
  const words = `Seven, see you there (${run})`;
  control.json({ t: "notifReply", key: m2?.key ?? "", i: r?.i ?? -1, label: r?.label ?? "", text: words });
  // The app adds the reply to its notification, or clears it — maybe before the phone says it is done.
  const [replied, echo] = await control.each([done("notifReply"), (v) => v.t === "notif" && v.key === m2?.key], 8000);
  check("a reply typed on the computer is handed to the app", replied?.ok === true, replied?.message ?? "");
  const sent = await until(10000, () => texts("sent", "address", "body").some((t) => t.body === words));
  const to = texts("sent", "address", "body").find((t) => t.body === words)?.address;
  check("…and sent: in the phone's sent messages, to the one who wrote", sent && to === "5550102", `${to ?? "not there"}`);
  check("…and the app's own update after it is not news", !echo || echo.kind === "removed" || echo.alert === false, JSON.stringify(echo && { kind: echo.kind, alert: echo.alert, text: echo.text }));

  // One of the app's own buttons: "Mark as read".
  sms("5550103", `Bring the bread (${run})`);
  const m3 = await control.next(news(/bread/), 20000);
  const read = m3?.actions?.find((a) => !a.reply);
  control.json({ t: "notifAction", key: m3?.key ?? "", i: read?.i ?? -1, label: read?.label ?? "" });
  const [pressed, cleared] = await control.each([done("notifAction"), gone(m3?.key)], 8000);
  check(`the app's own "${read?.label}" is pressed`, pressed?.ok === true, pressed?.message ?? "");
  check("…and does what it says: the notification goes", cleared !== null);
  check("…and the text is read, as Messages records it", await until(6000, () => isRead(`bread (${run})`) === "1"), `read=${isRead(`bread (${run})`)}`);

  // Calls, from the emulator's console: what each became, read back from the
  // phone — its call state, and its call log (1 answered, 3 missed, 5 declined).
  const gsm = (...a) => spawnSync(ADB, adbArgs("emu", "gsm", ...a), { encoding: "utf8" }).stdout || "";
  /** The phone's call state, as its telephony registry has it: "0" idle, "1" ringing, "2" in a call. */
  const callState = () => (adb("shell", "dumpsys", "telephony.registry").match(/mCallState=(\d)/) ?? [])[1];
  const callLog = () =>
    adb("shell", "content", "query", "--uri", "content://call_log/calls", "--projection", "_id:number:type")
      .split(/\r?\n/)
      .filter((l) => l.startsWith("Row:"))
      .map((l) => ({ id: Number((l.match(/_id=(\d+)/) ?? [])[1]), number: (l.match(/number=([^,]*)/) ?? [])[1], type: (l.match(/type=(\d+)/) ?? [])[1] }));
  const logStart = Math.max(0, ...callLog().map((c) => c.id));
  /** What this run's call from `number` went down in the call log as. */
  const logged = (number) => callLog().filter((c) => c.number === number && c.id > logStart).sort((a, b) => b.id - a.id)[0]?.type;
  const ringing = () => control.next((v) => v.t === "notif" && v.kind === "call", 20000);
  const endedOf = (key) => (v) => v.t === "notif" && v.kind === "callEnded" && v.key === key;
  const ended = (key) => control.next(endedOf(key), 15000);
  /**
   * What the computer sends for a press of answer, or decline: CallStyle's
   * own; or, for a dialer from before it, the dialer's own button that says
   * so — chosen by its words, as the person reading the toast does, and sent by its index.
   */
  const press = (call, answer) => {
    if (call?.answer || call?.decline) return control.json({ t: "notifCall", key: call.key, answer });
    const b = call?.actions?.find((a) => (answer ? /answer|accept/i : /decline|reject|dismiss/i).test(a.label));
    control.json({ t: "notifCall", key: call?.key ?? "", i: b?.i ?? -1, label: b?.label ?? "" });
  };

  // Answered from the computer.
  gsm("call", "5550199");
  const ring = await ringing();
  check("a ringing call comes as a call", !!ring?.call?.key, JSON.stringify(ring?.call && { app: ring.call.app, title: ring.call.title }));
  if (ring?.call?.answer || ring?.call?.decline) {
    check("…with CallStyle's own answer and decline, said as such", ring.call.answer === true && ring.call.decline === true, JSON.stringify({ answer: ring.call.answer, decline: ring.call.decline }));
  } else {
    // A dialer from before CallStyle: its buttons as it made them, in its order — nothing taken for "answer" by its place.
    const own = (ring?.call?.actions ?? []).map((a) => `${a.i}:${a.label}${a.tint ? " " + a.tint : ""}`);
    check("…from a dialer before CallStyle: its own buttons, by index, in its words and colours", own.length >= 2, JSON.stringify(own));
  }
  control.json({ t: "notifs", on: true });
  const during = await control.next(notif("snapshot"), 5000);
  check("…is among the calls a snapshot has while it rings", !!during?.calls?.some((c) => c.key === ring?.call?.key), JSON.stringify(during?.calls?.map((c) => c.key)));
  press(ring?.call, true);
  const [answered, picked] = await control.each([done("notifCall"), endedOf(ring?.call?.key)], 15000);
  check("Answer is pressed", answered?.ok === true, answered?.message ?? "");
  check("…and the call is answered: the phone is in it", await until(8000, () => callState() === "2"), `call state ${callState()}`);
  check("…and rings here no more", picked !== null);
  gsm("cancel", "5550199");
  await until(8000, () => callState() === "0");
  check("…and the call log has it answered", await until(5000, () => logged("5550199") === "1"), `type ${logged("5550199")}`);

  // Declined from the computer.
  gsm("call", "5550198");
  const ring2 = await ringing();
  press(ring2?.call, false);
  const [declined, refused] = await control.each([done("notifCall"), endedOf(ring2?.call?.key)], 15000);
  check("Decline is pressed", declined?.ok === true, declined?.message ?? "");
  check("…and the call is declined, never answered: the call log has it declined", await until(8000, () => logged("5550198") === "5"), `type ${logged("5550198")}, call state ${callState()}`);
  check("…and rings here no more", refused !== null);

  // Over before anyone answered.
  gsm("call", "5550197");
  const ring3 = await ringing();
  gsm("cancel", "5550197");
  const over = await ended(ring3?.call?.key);
  check("a call ends here when the caller hangs up", !!over && (await until(5000, () => logged("5550197") === "3")), `${over?.key ?? "no end"}, type ${logged("5550197")}`);
  // The missed call that leaves on the phone, cleared.
  const missed = await control.next((v) => v.t === "notif" && v.kind === "posted" && v.pkg === ring3?.call?.pkg, 5000);
  if (missed) control.json({ t: "notifDismiss", key: missed.key });
}

async function main() {
  if (!fs.existsSync(DEX)) throw new Error(`no ${DEX}: build the daemon first (openandroiddex-wmd/build.cmd or build.sh)`);
  const sdkLevel = Number(adb("shell", "getprop", "ro.build.version.sdk").trim());
  console.log(`phone: ${adb("shell", "getprop", "ro.product.model").trim()}, API ${sdkLevel}`);
  spawnSync(ADB, adbArgs("push", DEX, PHONE_DEX));
  const tags = ["oadx-a", "oadx-b", "oadx-c", "oadx-d", "oadx-m"].map((t) => `${t}-${randomBytes(2).toString("hex")}`);
  const [tagA, tagB, tagC, tagD, tagM] = tags;
  const server = new Server();
  await server.ready();
  const control = server.connect();
  control.json({ t: "control" });
  const hello = await control.next((v) => v.t === "hello");
  check("the server says hello", !!hello, hello ? `Android ${hello.release}` : "");

  // ── on ──
  control.json({ t: "notifs", on: true });
  const on = await control.next((v) => v.t === "notifs");
  check("notifications turn on", on?.ok === true, on?.message ?? "");
  const snap = await control.next(notif("snapshot"));
  check("a snapshot of what is there, none of it news", Array.isArray(snap?.items) && snap.items.every((i) => i.alert === false), `${snap?.items?.length} items`);
  check("…and of the calls ringing", Array.isArray(snap?.calls), JSON.stringify(snap?.calls?.map((c) => c.key)));
  // Asked again while listening — its first answer lost, say: everything again.
  control.json({ t: "notifs", on: true });
  const onAgain = await control.next((v) => v.t === "notifs");
  const snapAgain = await control.next(notif("snapshot"), 5000);
  check("asked again while listening, it says so and tells everything again", onAgain?.ok === true && Array.isArray(snapAgain?.items));

  // ── news, once ──
  post("-t", "Open Android DeX test", tagA, "First words");
  const a1 = await control.next(notif("posted", tagA), 8000);
  check("a new notification arrives as news", a1?.alert === true && a1.title === "Open Android DeX test" && a1.text === "First words", JSON.stringify(a1 && { alert: a1.alert, title: a1.title, text: a1.text }));
  check("…with who posted it", a1?.pkg === "com.android.shell" && typeof a1?.app === "string" && a1.app.length > 0, a1 ? `${a1.pkg} "${a1.app}"` : "");
  post("-t", "Open Android DeX test", tagA, "Second words");
  const a2 = await control.next(notif("posted", tagA), 8000);
  check("new words at once are an update, not a second alert", a2?.alert === false && a2.text === "Second words", JSON.stringify(a2 && { alert: a2.alert, text: a2.text }));
  post("-t", "Open Android DeX test", tagA, "Second words");
  check("the same words again are nothing at all", (await control.next(notif("posted", tagA), 2000)) === null);
  post("-t", "Open Android DeX test", tagA, "Third words");
  const a3 = await control.next(notif("posted", tagA), 8000);
  check("…and news again a moment later", a3?.alert === true && a3.text === "Third words");

  // ── a conversation ──
  post("-S", "messaging", "--conversation", "Family", "--message", "Mom:Dinner at 7?", "--message", "Dad:Bring bread", tagM, "x");
  const m = await control.next(notif("posted", tagM), 8000);
  check("a conversation is titled by its name", m?.title === "Family" && m?.conversation === "Family", m?.title);
  check("…and says its last messages, each with who said it", m?.text === "Mom: Dinner at 7?\nDad: Bring bread", JSON.stringify(m?.text));

  // ── a sender's picture ──
  const png = path.join(os.tmpdir(), `oadx-avatar-${randomBytes(3).toString("hex")}.png`);
  // One pixel: enough to be a picture.
  fs.writeFileSync(png, Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64"));
  spawnSync(ADB, adbArgs("push", png, "/data/local/tmp/oadx-avatar.png"));
  fs.unlinkSync(png);
  post("-t", "With a picture", "-I", "file:///data/local/tmp/oadx-avatar.png", tagB, "Who sent this");
  const b = await control.next(notif("posted", tagB), 8000);
  check("a notification brings its sender's picture", typeof b?.avatar === "string" && Buffer.from(b.avatar, "base64").subarray(1, 4).toString() === "PNG", b?.avatar ? `${b.avatar.length} chars` : "none");

  // ── a real app's: the emulator's Messages, an SMS sent to it ──
  // The shell's own posts carry no content intent or buttons on recent
  // Android ("not allowed to send as package android"), so these need an app.
  if ((serialArg ?? "").startsWith("emulator-")) await messages(server, control);
  else console.log("     (an emulator's Messages is what checks opening, replying and a button: skipped on a phone)");

  // ── dismissed from the computer ──
  control.json({ t: "notifDismiss", key: a3?.key ?? "" });
  const [d, dGone] = await control.each([done("notifDismiss"), notif("removed", tagA)], 5000);
  check("dismissing from the computer clears it on the phone", d?.ok === true, d?.message ?? "");
  check("…and the phone says it is gone", dGone !== null);
  control.json({ t: "notifAction", key: "0|nothing|0|null|0", i: 0, label: "Nope" });
  const bad = await control.next(done("notifAction"), 5000);
  check("a button on a notification that has gone is refused, and said", bad?.ok === false && !!bad.message, bad?.message ?? "");

  // ── the test notification ──
  control.json({ t: "notifTest", title: "Open Android DeX", text: "A test from the test" });
  const [t1, t2] = await control.each([done("notifTest"), (v) => v.t === "notif" && v.kind === "posted" && v.test === true], 10000);
  check("the phone posts a test notification", t1?.ok === true, t1?.message ?? "");
  check("…which comes back marked as one, as news", t2?.alert === true && t2.title === "Open Android DeX", JSON.stringify(t2 && { alert: t2.alert, title: t2.title }));

  // ── the connection that asked goes: the phone stops listening ──
  // A computer away is not read for: and one that turned notifications off
  // while it was away, never reaching the phone to say so, never is.
  let from = server.lines.length;
  control.close();
  check("the phone stops listening when the connection that asked goes", await server.waitFor("no longer listening (the connection that asked", 5000, from));
  const again = server.connect();
  again.json({ t: "control" });
  // Before any notification, which the computer would take for its hello.
  const first = await again.next(() => true);
  check("a computer back on a new connection hears hello first", first?.t === "hello", first?.t);
  post("-t", "Open Android DeX test", tagD, "Nobody asked for this");
  check("…and nothing it has not asked for", (await again.next((v) => v.t === "notif", 2500)) === null);
  again.json({ t: "notifs", on: true });
  const resync = await again.next(notif("snapshot"), 5000);
  check("asked on it, it tells everything again", Array.isArray(resync?.items) && resync.items.some((i) => (i.key || "").includes(tagM)), `${resync?.items?.length} items`);

  // ── off ──
  for (const i of resync?.items ?? []) {
    if (tags.some((t) => (i.key || "").includes(`|${t}|`))) again.json({ t: "notifDismiss", key: i.key });
  }
  await sleep(800);
  again.json({ t: "notifs", on: false });
  const off = await again.next((v) => v.t === "notifs", 5000);
  check("notifications turn off", off?.ok === true && off.on === false);
  post("-t", "Open Android DeX test", tagC, "Nobody hears this");
  check("…and nothing is read after", (await again.next(notif("posted"), 2500)) === null);

  // ── another connection takes the place of the one that asked ──
  again.json({ t: "notifs", on: true });
  await again.next(notif("snapshot"), 5000);
  from = server.lines.length;
  const third = server.connect();
  third.json({ t: "control" });
  await third.next((v) => v.t === "hello");
  check("a connection taking the place of the one that asked stops its notifications", await server.waitFor("no longer listening (a new control connection", 5000, from));
  again.json({ t: "notifs", on: true });
  const stale = await again.next((v) => v.t === "notifs", 5000);
  check("…and the one replaced cannot ask for them again", stale?.ok === false, stale?.message ?? "");

  // What this test posted, gone from the phone: on again for a moment to clear the last ones.
  third.json({ t: "notifs", on: true });
  const snap2 = await third.next(notif("snapshot"), 5000);
  for (const i of snap2?.items ?? []) {
    if (tags.some((t) => (i.key || "").includes(`|${t}|`)) || i.test) third.json({ t: "notifDismiss", key: i.key });
  }
  await sleep(800);
  third.json({ t: "stop" });
  await Promise.race([server.exited, sleep(5000)]);
  again.close();
  third.close();
  server.done();
  adb("shell", "rm", "-f", PHONE_DEX, "/data/local/tmp/oadx-avatar.png");
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
