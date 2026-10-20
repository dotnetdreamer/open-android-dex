#!/usr/bin/env node
// The desktop over adb (doc/usb-desktop.md), checked as far as a computer can
// without the app installed anywhere.
//
//   node test/usb-desktop-test.mjs                  everything off the phone
//   node test/usb-desktop-test.mjs --device [serial]
//        …then the launcher's stream classes on a real phone, run by
//        app_process as the shell user (test/usb-desktop/StreamHarness.java):
//        nothing is installed, the launcher is not touched, and the desktop
//        is a stand-in Xvnc on the phone's own loopback, where the real one
//        is (test/usb-desktop/StandinXvnc.java), changing every pixel of
//        every frame. A headless Chrome decodes the phone's hardware-encoded
//        stream with WebCodecs, sends input and a resize, and the stand-in
//        says they arrived.
//
// Needs Node 24 (it imports the window's .ts files as they are), a JDK, and
// for --device the Android SDK and Google Chrome.

import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { stripTypeScriptTypes } from "node:module";

// VncAuth's DES lives in OpenSSL's legacy provider.
if (!process.execArgv.includes("--openssl-legacy-provider")) {
  const r = spawnSync(process.execPath, ["--openssl-legacy-provider", fileURLToPath(import.meta.url), ...process.argv.slice(2)], {
    stdio: "inherit",
  });
  process.exit(r.status ?? 1);
}

const { startRfbStandin } = await import("./usb-desktop/rfb-standin.mjs");

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const LAUNCHER = path.join(ROOT, "openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher");
const USB = path.join(ROOT, "open-android-dex-tauri/src/features/remote/usb");
const args = process.argv.slice(2);

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${!ok && detail ? ` — ${detail}` : ""}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-usb-"));
const cleanups = [];
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

// ── the window's pure parts ──────────────────────────────────────────────

console.log("h264.ts");
{
  const h = await import(pathToFileURL(path.join(USB, "h264.ts")).href);
  const sps = [0x67, 0x64, 0x00, 0x33, 0xac, 0x2b];
  const pps = [0x68, 0xee, 0x3c, 0x80];
  const idr = [0x65, 0x88, 0x84, 0x00, 0x33];
  const annexB = Uint8Array.from([0, 0, 0, 1, ...sps, 0, 0, 1, ...pps, 0, 0, 0, 1, 0x09, 0xf0, 0, 0, 0, 1, ...idr, 0, 0]);
  const units = h.nalUnits(annexB);
  check("units split at 3- and 4-byte start codes", units.length === 4, `${units.length}`);
  check("a four-byte start code's zero and trailing zeros are not part of a unit", units[3].length === idr.length && units[0].length === sps.length);
  check("codec string from the SPS", h.codecString(Uint8Array.from(sps)) === "avc1.640033", h.codecString(Uint8Array.from(sps)));
  const c = h.avcC(Uint8Array.from(sps), Uint8Array.from(pps));
  check("avcC: version, profile, four-byte lengths, one SPS", c[0] === 1 && c[1] === 0x64 && c[4] === 0xff && c[5] === 0xe1);
  check("avcC: SPS then PPS, each behind its length", c[7] === sps.length && c[8] === 0x67 && c[8 + sps.length] === 1 && c[10 + sps.length] === pps.length);
  check("avcC: High profile's four extra fields", c.length === 11 + sps.length + pps.length + 4 && c[c.length - 4] === 0xfd);
  const main = h.avcC(Uint8Array.from([0x67, 0x4d, 0, 0x28]), Uint8Array.from(pps));
  check("avcC: none for Main", main.length === 11 + 4 + pps.length);
  const a = h.splitAccessUnit(annexB);
  check("access unit: parameter sets taken out, IDR is key", a.key && a.sps?.length === sps.length && a.pps?.length === pps.length);
  check("access unit: slices length-prefixed, AUD dropped", a.data.length === 4 + idr.length && a.data[3] === idr.length && a.data[4] === 0x65);
  const p = h.splitAccessUnit(Uint8Array.from([0, 0, 0, 1, 0x41, 0x9a, 0x02]));
  check("a P slice is not key", !p.key && p.sps === null && p.data.length === 7);
}

console.log("keysym.ts");
{
  const k = await import(pathToFileURL(path.join(USB, "keysym.ts")).href);
  const ev = (o) => ({ key: "", code: "", ctrlKey: false, altKey: false, metaKey: false, shiftKey: false, ...o });
  const press = (o, mac = false) => {
    const a = k.keyDown(ev(o), mac);
    return a.kind === "press" ? a.keysym : a.kind === "type" ? `type:${a.keysym}` : a.kind;
  };
  check("a letter is itself", press({ key: "a", code: "KeyA" }) === 0x61);
  check("Shift's letter is the capital", press({ key: "A", code: "KeyA", shiftKey: true }) === 0x41);
  check("Ctrl+C is C whatever the layout made", press({ key: "с", code: "KeyC", ctrlKey: true }) === 0x63);
  check("Cmd+V likewise", press({ key: "v", code: "KeyV", metaKey: true }, true) === 0x76);
  check("Latin-1 is itself", press({ key: "é", code: "Digit2" }) === 0xe9);
  check("beyond Latin-1 is the Unicode block", press({ key: "€", code: "KeyE", altKey: true, ctrlKey: true }) === 0x010020ac);
  check("AltGr on Windows types (Ctrl+Alt is not a shortcut)", press({ key: "@", code: "KeyQ", ctrlKey: true, altKey: true }) === 0x40);
  check("Option on a Mac types the character, Alt let go", press({ key: "@", code: "KeyL", altKey: true }, true) === "type:64");
  check("Option with a key that makes nothing is Alt+key", press({ key: "ArrowLeft", code: "ArrowLeft", altKey: true }, true) === 0xff51);
  check("Option on Windows is not special", press({ key: "f", code: "KeyF", altKey: true }) === 0x66);
  check("a dead key sends nothing", press({ key: "Dead", code: "BracketLeft" }) === "none");
  check("Caps Lock stays here", press({ key: "CapsLock", code: "CapsLock" }) === "none");
  check("modifiers by side", press({ key: "Shift", code: "ShiftRight" }) === 0xffe2 && press({ key: "Meta", code: "MetaLeft" }) === 0xffeb);
  check("named keys", press({ key: "Enter", code: "NumpadEnter" }) === 0xff0d && press({ key: "F5", code: "F5" }) === 0xffc2);
  check("space", press({ key: " ", code: "Space" }) === 0x20);
  check("the numpad's digits", press({ key: "1", code: "Numpad1" }) === 0x31);
  check("a key the browser cannot name falls back to its place", press({ key: "Unidentified", code: "KeyZ" }) === 0x7a);
}

// ── the launcher's pure-Java parts, on this JVM ──────────────────────────

const classes = path.join(tmp, "classes");
fs.mkdirSync(classes);
const javac = spawnSync(
  "javac",
  [
    "-nowarn",
    "-d",
    classes,
    path.join(LAUNCHER, "LinuxStreamRfb.java"),
    path.join(LAUNCHER, "LinuxStreamSocket.java"),
    path.join(HERE, "usb-desktop/RfbCheck.java"),
    path.join(HERE, "usb-desktop/SocketCheck.java"),
  ],
  { encoding: "utf8" },
);
if (javac.status !== 0) {
  console.log(javac.stderr);
  check("the launcher's stream classes compile on a JVM", false);
  process.exit(1);
}

function java(main, mainArgs, onLine) {
  const p = spawn("java", ["-cp", classes, `com.ccrstech.openandroiddex.launcher.${main}`, ...mainArgs], { stdio: ["ignore", "pipe", "pipe"] });
  const lines = [];
  let err = "";
  let rest = "";
  p.stdout.on("data", (d) => {
    rest += d;
    let i;
    while ((i = rest.indexOf("\n")) >= 0) {
      const line = rest.slice(0, i);
      rest = rest.slice(i + 1);
      lines.push(line);
      onLine?.(line);
    }
  });
  p.stderr.on("data", (d) => (err += d));
  const done = new Promise((resolve) => p.on("close", (code) => resolve({ code, lines, err })));
  return { p, done };
}

console.log("LinuxStreamRfb against a stand-in Xvnc");
// What Xvnc counts a text at: UTF-8, CRLF line ends, and a NUL.
const guestSize = (t) => Buffer.byteLength(t.replace(/\r?\n/g, "\r\n"), "utf8") + 1;
const lfOf = (t) => t.replace(/\r\n?/g, "\n");
/** Lines of every kind of character, CRLF, until Xvnc would count `bytes`. */
function longText(bytes, tag) {
  let s = "";
  for (let i = 0; guestSize(s) < bytes; i++) s += `${tag} ${i} — Ünïcödé, 日本語, 한국어 and 🙂 on one line\r\n`;
  return s;
}
const writeTexts = (texts) => {
  const file = path.join(tmp, `texts-${texts.length}-${Math.random().toString(36).slice(2)}.txt`);
  fs.writeFileSync(file, texts.map((t) => Buffer.from(t, "utf8").toString("base64")).join("\n") + "\n");
  return file;
};
const clipsOf = (r) => (r.clips ?? []).map((b) => Buffer.from(b, "base64").toString("utf8"));
async function rfbCheck(xvnc, texts, clips, onReady) {
  const run = java("RfbCheck", [String(xvnc.port), "standin1", writeTexts(texts), String(clips)], (line) => {
    if (line === '{"offered":true}') xvnc.paste();
    if (line === '{"ready":true}') onReady();
  });
  const killer = setTimeout(() => run.p.kill(), 40_000);
  const out = await run.done;
  clearTimeout(killer);
  try {
    return JSON.parse(out.lines[out.lines.length - 1]);
  } catch {
    check("RfbCheck reported", false, out.lines.join(" ").slice(0, 400) + out.err);
    return {};
  }
}
{
  const xvnc = await startRfbStandin({ password: "standin1", width: 640, height: 400 });
  const wrong = await java("RfbCheck", [String(xvnc.port), "nope"]).done;
  check("a wrong password is AuthFailed, not a hang", wrong.lines[0] === '{"authFailed":true}', wrong.lines.join(" ") + wrong.err);
  // The computer's: CRLF and Latin-1, an em dash, CJK and an emoji, 200 KB,
  // and then more than Xvnc takes. From the guest: the same kinds, and 200 KB.
  const mine = ["héllo\r\nworld ☃", "an em — dash, 日本語, 🙂\r\nand a second line\r\n", longText(200_000, "mine"), longText(300_000, "huge")];
  const guest = ["from the guést — 日本語 🙂\r\nsecond line", longText(200_000, "guest")];
  const requests = () => xvnc.events.filter((e) => e.type === "clip-request").length;
  const r = await rfbCheck(xvnc, mine, guest.length, async () => {
    for (const t of guest) {
      const asked = requests();
      xvnc.sendClipboard(t);
      for (let i = 0; i < 50 && requests() === asked; i++) await sleep(50);
    }
  });
  const ev = xvnc.events;
  // (the wrong password before it left its own auth-failed in the log)
  check("logs in with VncAuth (the DES is right)", ev.some((e) => e.type === "client-init") && r.updates > 0);
  check("asks to share the desktop, never to own it", ev.some((e) => e.type === "client-init" && e.shared === 1));
  const enc = ev.find((e) => e.type === "set-encodings")?.encodings ?? [];
  check(
    "asks for raw, both desktop sizes, the cursor and the extended clipboard, nothing else",
    JSON.stringify(enc) === JSON.stringify([0, -308, -223, -239, 0xc0a1e5ce | 0]),
    JSON.stringify(enc),
  );
  check("keeps Xvnc's own pixel format: no conversion on its side", !ev.some((e) => e.type === "set-pixel-format"));
  check("reads B,G,R,X back as RGB in the shader", r.swizzle === "bgr", r.swizzle);
  check("every sampled pixel is the stand-in's", r.checked > 1000 && r.mismatches === 0, `${r.mismatches}/${r.checked}`);
  check("the server's name", r.name === "standin:1", r.name);
  check("the cursor's shape, hotspot and mask", r.cursor === "1,2,12x18,r255,a0=255,aLast=255,a1=0", r.cursor);
  check("Xvnc says it resizes", r.resizable === true);
  check("a resize is reported, and pictures follow at the new size", (r.resizes ?? "").split(" ").includes("800x600"), r.resizes);
  const pointers = ev.filter((e) => e.type === "pointer");
  check("pointer, and one off the edge held to it", pointers.some((p) => p.x === 10 && p.y === 20 && p.buttons === 1) && pointers.some((p) => p.x === 639 && p.y === 399));
  const keys = ev.filter((e) => e.type === "key");
  check("key down and up", keys.length === 2 && keys[0].down && !keys[1].down && keys[0].keysym === 0x61);
  check("SetDesktopSize with the size asked", ev.some((e) => e.type === "set-desktop-size" && e.w === 800 && e.h === 600));

  const caps = ev.find((e) => e.type === "clip-caps");
  const want = (1 << 24) | (1 << 25) | (1 << 27) | (1 << 28) | 1;
  check("the extended clipboard: its capabilities in answer to Xvnc's, text, nothing unasked", caps && (caps.flags & want) === want && JSON.stringify(caps.sizes) === "[0]", JSON.stringify(caps));
  const notifies = ev.filter((e) => e.type === "clip-notify");
  const provides = ev.filter((e) => e.type === "clip-provide");
  check("each text is announced, and handed over only when the guest asks", notifies.filter((n) => n.text).length === 3 && provides.length === 4, `${notifies.length} notify, ${provides.length} provide`);
  check("UTF-8 whole: CRLF and Latin-1, an em dash, CJK, an emoji", provides[0]?.text === lfOf(mine[0]) && provides[1]?.text === lfOf(mine[1]), JSON.stringify(provides.slice(0, 2).map((p) => p.text)));
  check("as Xvnc counts it: CRLF line ends, a NUL, the length with it", provides.slice(0, 3).every((p) => p.crlf && p.nul && p.valid) && provides[1].bytes === guestSize(mine[1]), JSON.stringify(provides.slice(0, 3).map((p) => [p.crlf, p.nul, p.valid, p.bytes])));
  check("200 KB in one piece", provides[2]?.text === lfOf(mine[2]) && provides[2].bytes > 200_000 && provides[2].bytes <= 262_144, `${provides[2]?.bytes}`);
  check("more than Xvnc's MaxCutText is never offered: the guest keeps the last", provides[3]?.text === lfOf(mine[2]) && !ev.some((e) => e.type === "clip-too-long"), JSON.stringify(provides.map((p) => p.bytes)));
  check("the text is kept, for a paste later on", provides[3]?.text === lfOf(mine[2]));
  check("taking it back offers nothing, so Xvnc lets go", notifies.at(-1)?.text === false, JSON.stringify(notifies.at(-1)));
  check("nothing went the Latin-1 way, and nothing was refused", !ev.some((e) => ["clipboard", "clip-too-long", "clip-bad-zlib", "clip-unexpected"].includes(e.type)));
  const back = clipsOf(r);
  check("the guest's comes back whole: UTF-8, LF", back[0] === lfOf(guest[0]), JSON.stringify(back[0]));
  check("…and 200 KB of it", back[1] === lfOf(guest[1]), `${back[1]?.length} of ${lfOf(guest[1]).length}`);
  check("…each asked for once", ev.filter((e) => e.type === "clip-request").length === 2);
  await xvnc.close();
}
{
  // A server without the extended clipboard: standard cut text, Latin-1.
  const xvnc = await startRfbStandin({ password: "standin1", width: 640, height: 400, extendedClipboard: false });
  const r = await rfbCheck(xvnc, ["héllo\r\nworld ☃"], 1, () => setTimeout(() => xvnc.sendClipboard("from the guést"), 100));
  const cut = xvnc.events.filter((e) => e.type === "clipboard");
  check("without it, the clipboard goes as Latin-1, LF, '?' for what it cannot carry", cut[0]?.text === "héllo\nworld ?", JSON.stringify(cut));
  check("…taking it back is an empty cut text", cut[1]?.text === "", JSON.stringify(cut[1]));
  check("…and the guest's comes back", clipsOf(r)[0] === "from the guést", JSON.stringify(clipsOf(r)));
  await xvnc.close();
}

console.log("LinuxStreamSocket");
{
  let port = 0;
  const server = java("SocketCheck", [], (line) => {
    if (!port) port = Number(line);
  });
  cleanups.push(() => server.p.kill());
  for (let i = 0; i < 50 && !port; i++) await sleep(100);
  const raw = (text) =>
    new Promise((resolve) => {
      const s = net.connect(port, "127.0.0.1", () => s.write(text));
      let got = "";
      s.on("data", (d) => (got += d.toString("latin1")));
      s.on("close", () => resolve(got));
      s.on("error", () => resolve(got));
      setTimeout(() => s.destroy(), 2000);
    });
  const probe = await raw("GET /probe HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
  check("GET /probe answers JSON and closes", probe.startsWith("HTTP/1.1 200") && probe.includes('"id":"test-id"'), probe.slice(0, 60));
  const echo = await new Promise((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/stream`);
    ws.binaryType = "arraybuffer";
    const got = [];
    const big = new Uint8Array(300_000).map((_, i) => i & 0xff);
    ws.onopen = () => {
      ws.send("hello");
      ws.send(big);
    };
    ws.onmessage = (m) => {
      got.push(m.data);
      if (got.length === 2) {
        ws.close();
        resolve({ got, big });
      }
    };
    ws.onerror = () => resolve({ got, big });
    setTimeout(() => resolve({ got, big }), 5000);
  });
  check("text both ways", echo.got[0] === "echo:hello", String(echo.got[0]));
  const back = echo.got[1] ? new Uint8Array(echo.got[1]) : new Uint8Array();
  check(
    "a 300 KB binary message both ways, 64-bit lengths and masking",
    back.length === 300_001 && back[0] === 0x7e && back[1] === 0 && back[300_000] === (299_999 & 0xff),
    `${back.length}`,
  );
  const upgrade = (origin) =>
    raw(
      "GET /stream HTTP/1.1\r\nHost: 127.0.0.1:1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" +
        (origin ? `Origin: ${origin}\r\n` : "") +
        "\r\n",
    );
  const ok = await upgrade("tauri://localhost");
  check("the app's page is upgraded, with RFC 6455's accept", ok.includes("101 Switching") && ok.includes("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
  check("so is a page on loopback", (await upgrade("http://127.0.0.1:1420")).includes("101"));
  check("a page from elsewhere is not", !(await upgrade("https://example.com")).includes("101"));
  check("nor is another host", !(await raw("GET /stream HTTP/1.1\r\nHost: example.com\r\nUpgrade: websocket\r\nSec-WebSocket-Key: x\r\n\r\n")).includes("101"));
  server.p.kill();
}

// ── on a phone ───────────────────────────────────────────────────────────

if (args.includes("--device")) {
  await onDevice();
}

for (const c of cleanups) {
  try {
    c();
  } catch {}
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);

async function onDevice() {
  const SDK = process.env.ANDROID_HOME || path.join(os.homedir(), "Library/Android/sdk");
  const ADB = path.join(SDK, "platform-tools/adb");
  const at = args.indexOf("--device");
  let serial = args[at + 1] && !args[at + 1].startsWith("--") ? args[at + 1] : null;
  if (!serial) {
    const list = spawnSync(ADB, ["devices"], { encoding: "utf8" }).stdout.split("\n").slice(1);
    const ready = list.map((l) => l.split("\t")).filter((p) => p[1] === "device");
    if (ready.length !== 1) {
      check("exactly one phone on adb (or name one: --device <serial>)", false, `${ready.length}`);
      return;
    }
    serial = ready[0][0];
  }
  const adb = (...a) => spawnSync(ADB, ["-s", serial, ...a], { encoding: "utf8" });
  console.log(`on ${serial}`);

  // The harness: the stream classes and a main, as one dex.
  const platform = fs.readdirSync(path.join(SDK, "platforms")).filter((p) => p.startsWith("android-36")).sort().pop();
  const tools = fs.readdirSync(path.join(SDK, "build-tools")).sort().pop();
  const hc = path.join(tmp, "harness");
  fs.mkdirSync(hc);
  const built = spawnSync(
    "javac",
    [
      "-nowarn",
      "-source",
      "17",
      "-target",
      "17",
      "-cp",
      path.join(SDK, "platforms", platform, "android.jar"),
      "-d",
      hc,
      ...["LinuxStream", "LinuxStreamRfb", "LinuxStreamEncoder", "LinuxStreamSocket", "DexLog"].map((c) => path.join(LAUNCHER, `${c}.java`)),
      path.join(HERE, "usb-desktop/StreamHarness.java"),
      path.join(HERE, "usb-desktop/StandinXvnc.java"),
    ],
    { encoding: "utf8" },
  );
  const classFiles = [];
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : classFiles.push(path.join(d, e.name))));
  if (built.status === 0) walk(hc);
  const dexDir = path.join(tmp, "dex");
  fs.mkdirSync(dexDir);
  const d8 = built.status === 0 && spawnSync(path.join(SDK, "build-tools", tools, "d8"), ["--min-api", "26", "--output", dexDir, ...classFiles], { encoding: "utf8" });
  if (!d8 || d8.status !== 0) {
    check("the harness builds", false, (built.stderr || "") + (d8 ? d8.stderr : ""));
    return;
  }
  const DEX = "/data/local/tmp/oadx-stream-test.dex";
  check("the harness goes onto the phone", adb("push", path.join(dexDir, "classes.dex"), DEX).status === 0);
  cleanups.push(() => adb("shell", "rm", "-f", DEX));

  const at2 = args.indexOf("--size");
  const [W, H] = (at2 >= 0 ? args[at2 + 1] : "2560x1440").split("x").map(Number);
  const SOCKET = "oadx-test.linux-stream";
  const harness = spawn(
    ADB,
    ["-s", serial, "shell", `CLASSPATH=${DEX} app_process / com.ccrstech.openandroiddex.launcher.StreamHarness ${SOCKET} standin:${W}x${H} standin1`],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  let hout = "";
  // What the stand-in was told, from its EV lines.
  const xvnc = {
    events: [],
    sendClipboard: (text) => harness.stdin.write(`clip ${Buffer.from(text, "utf8").toString("base64")}\n`),
    close: async () => {},
  };
  let partial = "";
  harness.stdout.on("data", (d) => {
    hout += d;
    partial += d;
    let i;
    while ((i = partial.indexOf("\n")) >= 0) {
      const line = partial.slice(0, i).trim();
      partial = partial.slice(i + 1);
      if (line.startsWith("EV ")) {
        try {
          xvnc.events.push(JSON.parse(line.slice(3)));
        } catch {}
      }
    }
  });
  harness.stderr.on("data", (d) => (hout += d));
  cleanups.push(() => harness.kill());
  for (let i = 0; i < 100 && !hout.includes("listening @"); i++) await sleep(100);
  check("the harness listens on its abstract socket", hout.includes("listening @"), hout.slice(0, 300));

  // adb's own protocol, as remote_usb.rs speaks it; one stream per connection.
  const adbOpen = (service) =>
    new Promise((resolve, reject) => {
      const s = net.connect(Number(process.env.ANDROID_ADB_SERVER_PORT || 5037), "127.0.0.1");
      let stage = 0;
      let buf = Buffer.alloc(0);
      const ask = (req) => s.write(req.length.toString(16).padStart(4, "0") + req);
      const onData = (d) => {
        buf = Buffer.concat([buf, d]);
        while (stage < 2 && buf.length >= 4) {
          const st = buf.subarray(0, 4).toString();
          if (st !== "OKAY") {
            s.destroy();
            reject(new Error(`adb: ${buf.toString()}`));
            return;
          }
          buf = buf.subarray(4);
          stage++;
          if (stage === 1) ask(service);
          else {
            s.removeListener("data", onData);
            if (buf.length) s.unshift(buf);
            resolve(s);
          }
        }
      };
      s.on("connect", () => ask(`host:transport:${serial}`));
      s.on("data", onData);
      s.on("error", reject);
    });
  const proxy = net.createServer(async (c) => {
    c.pause();
    try {
      const p = await adbOpen(`localabstract:${SOCKET}`);
      p.setNoDelay(true);
      c.setNoDelay(true);
      c.pipe(p);
      p.pipe(c);
      c.resume();
      c.on("close", () => p.destroy());
      p.on("close", () => c.destroy());
      c.on("error", () => p.destroy());
      p.on("error", () => c.destroy());
    } catch {
      c.destroy();
    }
  });
  await new Promise((r) => proxy.listen(0, "127.0.0.1", r));
  cleanups.push(() => proxy.close());
  const PP = proxy.address().port;

  const probe = await new Promise((resolve) => {
    const s = net.connect(PP, "127.0.0.1", () => s.write("GET /probe HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
    let got = "";
    s.on("data", (d) => (got += d));
    s.on("close", () => resolve(got));
    s.on("error", () => resolve(got));
    setTimeout(() => s.destroy(), 4000);
  });
  check("the probe answers through adb", probe.includes('"id":"harness"'), probe.slice(0, 120));

  // The page and its module, from here.
  const chromePath = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  if (!fs.existsSync(chromePath)) {
    check("Google Chrome, for the decode", false, chromePath);
    return;
  }
  const h264js = stripTypeScriptTypes(fs.readFileSync(path.join(USB, "h264.ts"), "utf8"));
  const site = http.createServer((req, res) => {
    const u = new URL(req.url, "http://x");
    const send = (type, body) => {
      res.writeHead(200, { "content-type": type });
      res.end(body);
    };
    if (u.pathname === "/page.html") send("text/html", '<!doctype html><script type="module" src="/decode-page.mjs"></script>');
    else if (u.pathname === "/decode-page.mjs") send("text/javascript", fs.readFileSync(path.join(HERE, "usb-desktop/decode-page.mjs")));
    else if (u.pathname === "/h264.js") send("text/javascript", h264js);
    else {
      res.writeHead(404);
      res.end();
    }
  });
  await new Promise((r) => site.listen(0, "127.0.0.1", r));
  cleanups.push(() => site.close());

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-chrome-"));
  const chrome = spawn(chromePath, ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--no-first-run", "--no-default-browser-check", "about:blank"], {
    stdio: ["ignore", "ignore", "pipe"],
  });
  cleanups.push(() => {
    chrome.kill();
    setTimeout(() => fs.rmSync(profile, { recursive: true, force: true }), 500);
  });
  let devtools = "";
  chrome.stderr.on("data", (d) => {
    const m = String(d).match(/DevTools listening on (ws:\/\/\S+)/);
    if (m) devtools = m[1];
  });
  for (let i = 0; i < 100 && !devtools; i++) await sleep(100);
  const cdp = new WebSocket(devtools);
  await new Promise((r) => (cdp.onopen = r));
  let id = 0;
  const pending = new Map();
  cdp.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  };
  const call = (method, params = {}, sessionId) =>
    new Promise((resolve) => {
      const n = ++id;
      pending.set(n, resolve);
      cdp.send(JSON.stringify({ id: n, method, params, sessionId }));
    });
  const bitrate = 40_000_000;
  const pageUrl = `http://127.0.0.1:${site.address().port}/page.html?ws=${encodeURIComponent(`ws://127.0.0.1:${PP}/stream`)}&w=${W}&h=${H}&bitrate=${bitrate}`;
  const target = await call("Target.createTarget", { url: pageUrl });
  const attached = await call("Target.attachToTarget", { targetId: target.result.targetId, flatten: true });
  const session = attached.result.sessionId;
  const evaluate = async (expr) => (await call("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true }, session)).result?.result?.value;

  await sleep(1500);
  const t0 = Date.now();
  const before = (await evaluate("window.result && window.result.decoded")) || 0;
  await sleep(5000);
  let r = await evaluate("window.result");
  const fps = ((r.decoded - before) * 1000) / (Date.now() - t0);
  check("the phone streams: running, then a video config", r.states.includes("running") && r.videos.length >= 1, JSON.stringify(r.states));
  const v = r.videos[0] || {};
  console.log(`        encoder ${v.encoder}${v.hw ? "" : " (software)"}${v.lowLatency ? ", low latency" : ", PLAIN config"}, ${v.w}x${v.h}, ${Math.round(v.bitrate / 1e6)} Mb/s; codec ${r.codec}`);
  check("encoded at the desktop's own size", v.dw === W && v.dh === H && v.cw === W && v.ch === H, JSON.stringify(v));
  check("on a hardware encoder", v.hw === true, v.encoder);
  check("Chrome's WebCodecs decodes it, through the app's own h264.ts", r.decoded > 30 && r.decodeErrors.length === 0, `${r.decoded} decoded, errors ${r.decodeErrors.join("; ")}`);
  check("every frame at the desktop's size", Object.keys(r.decodedSizes).join() === `${W}x${H}`, JSON.stringify(r.decodedSizes));
  const s = r.stats[r.stats.length - 1] || {};
  console.log(
    `        ${fps.toFixed(1)} fps decoded here; the phone says ${s.fps} fps, ${(s.kbps / 1000).toFixed(1)} Mb/s, ` +
      `${s.encodeMs} ms in the encoder (swap ${s.swapMs}), ${s.sendMs} ms handing it to adb, link ${s.rttMs} ms (best ${s.bestRttMs}) → window ${s.window}; ` +
      `round trip ${Math.min(...r.rtt).toFixed(1)} ms at best, ${median(r.rtt).toFixed(1)} typical; decode ${median(r.decodeMs).toFixed(1)} ms`,
  );
  console.log(
    `        per update (${s.mpixPerUpdate} Mpx): waiting for Xvnc ${s.waitMs} ms, reading ${s.readMs}, uploading ${s.uploadMs}, ` +
      `drawing into the encoder ${s.frameMs}`,
  );
  // Wireless debugging's own latency sets its frame rate (the window holds
  // its round trip in frames); only a cable is held to a number.
  const wireless = serial.includes(":") || serial.includes("adb-tls-connect");
  if (wireless) console.log(`        (wireless debugging: the frame rate is the link's, not checked)`);
  else check(`a ${W}x${H} desktop changing every pixel of every frame comes through at 45+ fps`, fps >= 45, fps.toFixed(1));
  check("the pointer's shape arrives as a bitmap", r.cursor && r.cursor.size[0] === 12 && r.cursor.bytes === 12 * 18 * 4, JSON.stringify(r.cursor));

  await evaluate("window.act({pointer:[100,200,1]}) && window.act({pointer:[100,200,0]}) && window.act({key:[0x62,true]}) && window.act({key:[0x62,false]})");
  const mine = "cl\u00e9 — 日本語 🙂\nline two";
  await evaluate(`window.act({json:{t:"clip",text:${JSON.stringify(mine)}}})`);
  await sleep(800);
  const ev = xvnc.events;
  check("pointer through to Xvnc", ev.some((e) => e.type === "pointer" && e.x === 100 && e.y === 200 && e.buttons === 1));
  check("keys through to Xvnc", ev.some((e) => e.type === "key" && e.keysym === 0x62 && e.down) && ev.some((e) => e.type === "key" && e.keysym === 0x62 && !e.down));
  check(
    "the clipboard through to Xvnc, UTF-8 by the extended clipboard (zlib on the phone)",
    ev.some((e) => e.type === "clip-provide" && e.text === mine && e.nul) && !ev.some((e) => e.type === "clipboard" || e.type === "clip-bad"),
    JSON.stringify(ev.filter((e) => e.type.startsWith("clip"))),
  );
  const theirs = "guest copy — 한국어 ✓ 🙂\nsecond line";
  xvnc.sendClipboard(theirs);
  await evaluate('window.act({json:{t:"resize",w:1920,h:1080}})');
  await sleep(1500);
  const d0 = (await evaluate("window.result.decodedSizes['1920x1080']")) || 0;
  const t1 = Date.now();
  await sleep(3000);
  r = await evaluate("window.result");
  const fps2 = (((r.decodedSizes["1920x1080"] || 0) - d0) * 1000) / (Date.now() - t1);
  const s2 = r.stats[r.stats.length - 1] || {};
  console.log(
    `        after the resize: ${fps2.toFixed(1)} fps decoded here; the phone says ${s2.fps} fps, ${(s2.kbps / 1000).toFixed(1)} Mb/s, ` +
      `${s2.encodeMs} ms in the encoder, ${s2.sendMs} ms to adb; round trip ${median(r.rtt.slice(-6)).toFixed(1)} ms`,
  );
  check("a resize reaches Xvnc", ev.some((e) => e.type === "set-desktop-size" && e.w === 1920 && e.h === 1080));
  const last = r.videos[r.videos.length - 1] || {};
  check("and a new encoder at the new size", last.dw === 1920 && last.dh === 1080, JSON.stringify(last));
  check(
    wireless ? "decoded at the new size" : "decoded at the new size, 45+ fps",
    (wireless ? fps2 > 5 : fps2 >= 45) && r.decodeErrors.length === 0,
    fps2.toFixed(1) + r.decodeErrors.join(";"),
  );
  check("the guest's clipboard reaches the page, whole", r.clip === theirs, JSON.stringify(r.clip));

  cdp.close();
  await xvnc.close();
}

function median(a) {
  if (!a || !a.length) return NaN;
  const s = [...a].sort((x, y) => x - y);
  return s[s.length >> 1];
}
