#!/usr/bin/env node
// The Android desktop's own video (doc/dex-video.md), checked as far as a
// computer can, and then on a phone.
//
//   node test/dex-video-test.mjs                  the window's pure parts
//   node test/dex-video-test.mjs --device [serial] [--size WxH]
//        …then the desktop's own server (openandroiddex-wmd, Desktop) on a real
//        phone, run by app_process as the shell user exactly as the computer
//        runs it, on a display of its own — the phone's DeX desktop, if one is
//        running, is not touched. The phone's Settings is opened on that display
//        in a task of its own; a headless Chrome decodes the stream with
//        WebCodecs through the app's own h264.ts; the wheel scrolls Settings to
//        make the picture move; then a resize, and the clipboard both ways, with
//        the phone's own clipboard put back afterwards.
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

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const WMD = path.join(ROOT, "openandroiddex-wmd");
const USB = path.join(ROOT, "open-android-dex-tauri/src/features/remote/usb");
const DEX_UI = path.join(ROOT, "open-android-dex-tauri/src/features/dex");
const args = process.argv.slice(2);

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${!ok && detail ? ` — ${detail}` : ""}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const median = (a) => {
  if (!a || !a.length) return NaN;
  const s = [...a].sort((x, y) => x - y);
  return s[s.length >> 1];
};
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "oadx-dex-"));
const cleanups = [];
process.on("exit", () => fs.rmSync(tmp, { recursive: true, force: true }));

// ── the window's pure parts ──────────────────────────────────────────────

if (fs.existsSync(path.join(DEX_UI, "androidKeys.ts"))) {
  const { pureChecks } = await import(pathToFileURL(path.join(HERE, "dex-video/pure.mjs")).href);
  await pureChecks({ check, DEX_UI, USB });
}

if (args.includes("--device")) {
  await onDevice();
}

for (const c of cleanups.reverse()) {
  try {
    await c();
  } catch {}
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);

async function onDevice() {
  const SDK = process.env.ANDROID_HOME || path.join(os.homedir(), "Library/Android/sdk");
  // The app's own adb when it is staged: a client of another version would
  // restart the adb server under any desktop session that is running.
  const bundled = path.join(ROOT, "open-android-dex-tauri/src-tauri/resources/bin/adb");
  const ADB = fs.existsSync(bundled) ? bundled : path.join(SDK, "platform-tools/adb");
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
  const shell = (cmd) => (adb("shell", cmd).stdout || "").trim();
  const wireless = serial.includes(":") || serial.includes("adb-tls-connect");
  console.log(`\non ${serial}${wireless ? " (wireless debugging)" : ""}, Android ${shell("getprop ro.build.version.release")}`);

  // The window daemon's dex, as the app bundles it, and a clipboard helper beside it.
  const built = spawnSync(path.join(WMD, "build.sh"), [], { encoding: "utf8", env: { ...process.env, ANDROID_HOME: SDK } });
  check("the window daemon's dex builds", built.status === 0, (built.stdout + built.stderr).slice(-600));
  if (built.status !== 0) return;
  const platform = fs.readdirSync(path.join(SDK, "platforms")).filter((p) => p.startsWith("android-36")).sort().pop();
  const tools = fs.readdirSync(path.join(SDK, "build-tools")).sort().pop();
  const clipClasses = path.join(tmp, "clip");
  fs.mkdirSync(clipClasses);
  const javac = spawnSync(
    "javac",
    ["-nowarn", "-source", "17", "-target", "17", "-cp", [path.join(SDK, "platforms", platform, "android.jar"), path.join(WMD, "build/classes")].join(":"),
      "-d", clipClasses, path.join(HERE, "dex-video/ClipCheck.java")],
    { encoding: "utf8" },
  );
  const clipDex = path.join(tmp, "clipdex");
  fs.mkdirSync(clipDex);
  const d8 = javac.status === 0 &&
    spawnSync(path.join(SDK, "build-tools", tools, "d8"), ["--min-api", "26", "--output", clipDex, path.join(clipClasses, "com/ccrstech/openandroiddex/wmd/ClipCheck.class")], { encoding: "utf8" });
  if (!d8 || d8.status !== 0) {
    check("the clipboard helper builds", false, (javac.stderr || "") + (d8 ? d8.stderr : ""));
    return;
  }
  const DEX = "/data/local/tmp/oadx-desktop-test.dex";
  const CLIP = "/data/local/tmp/oadx-clipcheck.dex";
  check("the dex goes onto the phone", adb("push", path.join(WMD, "openandroiddex-wmd.dex"), DEX).status === 0);
  adb("push", path.join(clipDex, "classes.dex"), CLIP);
  cleanups.push(() => adb("shell", "rm", "-f", DEX, CLIP));
  const clipboard = (...a) => shell(`CLASSPATH=${DEX}:${CLIP} app_process / com.ccrstech.openandroiddex.wmd.ClipCheck ${a.join(" ")}`);
  const theirs = clipboard("get");
  cleanups.push(() => (theirs && theirs !== "-" ? clipboard("set", theirs) : clipboard("clear")));

  // The server, as scrcpy.rs runs it: stdin held open for as long as the session lives.
  const sizeAt = args.indexOf("--size");
  const [W, H] = (sizeAt >= 0 ? args[sizeAt + 1] : "1280x800").split("x").map(Number);
  const SOCKET = "oadx-desktop-test";
  const server = spawn(ADB, ["-s", serial, "shell", `CLASSPATH=${DEX} app_process / com.ccrstech.openandroiddex.wmd.Desktop ${W}x${H}/160 ${SOCKET}`], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  let out = "";
  server.stdout.on("data", (d) => (out += d));
  server.stderr.on("data", (d) => (out += d));
  cleanups.push(() => server.kill());
  for (let i = 0; i < 100 && !/New display: .*\(id=\d+\)/.test(out); i++) await sleep(100);
  const announced = out.match(/New display: (\d+)x(\d+)\/(\d+) \(id=(\d+)\)/);
  check("the server makes the display and says so in scrcpy's words", !!announced && +announced[1] === W && +announced[2] === H, out.slice(0, 400));
  if (!announced) return;
  const displayId = Number(announced[4]);
  const info = shell(`dumpsys display | grep -F 'Open Android DeX' | grep -F DisplayDeviceInfo`);
  check("a trusted display of its own, owned by the shell", info.includes("FLAG_TRUSTED") && info.includes("owner com.android.shell"), info.slice(0, 300));
  console.log(`        ${["FLAG_TRUSTED", "FLAG_OWN_DISPLAY_GROUP", "FLAG_ALWAYS_UNLOCKED", "FLAG_OWN_FOCUS"].filter((f) => info.includes(f)).join(", ")}`);
  const started = shell(`am start --display ${displayId} --activity-multiple-task -n com.android.settings/.Settings`);
  check("Settings opens on it, in a task of its own", !/Error|Exception/.test(started), started);

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
    else if (u.pathname === "/decode-page.mjs") send("text/javascript", fs.readFileSync(path.join(HERE, "dex-video/decode-page.mjs")));
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
  cleanups.push(() => cdp.close());
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
  const bitrate = wireless ? 12_000_000 : 20_000_000;
  const pageUrl = `http://127.0.0.1:${site.address().port}/page.html?ws=${encodeURIComponent(`ws://127.0.0.1:${PP}/stream`)}&w=${W}&h=${H}&bitrate=${bitrate}`;
  const target = await call("Target.createTarget", { url: pageUrl });
  const attached = await call("Target.attachToTarget", { targetId: target.result.targetId, flatten: true });
  const session = attached.result.sessionId;
  const evaluate = async (expr) => (await call("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true }, session)).result?.result?.value;

  // Settings drawing itself, then nothing moving.
  await sleep(2500);
  let r = await evaluate("window.result");
  const v = r.videos[0] || {};
  check("a video config before the first frame", r.videos.length >= 1 && v.codec === "avc", JSON.stringify(r.videos));
  console.log(`        encoder ${v.encoder}${v.hw ? "" : " (software)"}${v.lowLatency ? ", low latency" : ", PLAIN config"}, ${v.w}x${v.h}, ${Math.round(v.bitrate / 1e6)} Mb/s; codec ${r.codec}; display ${v.display} at ${v.dpi} dpi`);
  check("encoded at the display's own size", v.dw === W && v.dh === H && v.cw === W && v.ch === H, JSON.stringify(v));
  check("on a hardware encoder", v.hw === true, v.encoder);
  check("Chrome's WebCodecs decodes it, through the app's own h264.ts", r.decoded > 0 && r.decodeErrors.length === 0, `${r.decoded} decoded, errors ${r.decodeErrors.join("; ")}`);
  check("every frame at the display's size", Object.keys(r.decodedSizes).join() === `${W}x${H}`, JSON.stringify(r.decodedSizes));
  const still0 = r.decoded;
  await sleep(2000);
  r = await evaluate("window.result");
  const stillFps = (r.decoded - still0) / 2;
  console.log(`        a still desktop: ${stillFps.toFixed(1)} frames a second (the encoder repeating the last picture)`);
  check("a still desktop costs next to nothing", stillFps <= 15, stillFps.toFixed(1));

  // The wheel, over Settings' list, for four seconds.
  const moving0 = r.decoded;
  const lat0 = r.latency.length;
  await evaluate(`window.act({scrolling:[${W >> 1},${H >> 1},4000]})`);
  const t0 = Date.now();
  await sleep(4000);
  r = await evaluate("window.result");
  const fps = ((r.decoded - moving0) * 1000) / (Date.now() - t0);
  const lat = r.latency.slice(lat0);
  const s = r.stats[r.stats.length - 1] || {};
  console.log(
    `        scrolling: ${fps.toFixed(1)} fps decoded here; the phone says ${s.fps} fps, ${(s.kbps / 1000).toFixed(1)} Mb/s, ` +
      `${s.encodeMs} ms from its compositor to the encoder's output (swap ${s.swapMs}), ${s.sendMs} ms handing it to adb, ` +
      `link ${s.rttMs} ms (best ${s.bestRttMs}) → window ${s.window}`,
  );
  console.log(
    `        latency, compositor to decoded here: ${median(lat).toFixed(1)} ms typical, ${Math.min(...lat).toFixed(1)} at best ` +
      `(${lat.length} frames); round trip ${Math.min(...r.rtt).toFixed(1)} ms at best, ${median(r.rtt).toFixed(1)} typical`,
  );
  check("the wheel goes in: scrolling moves the picture", fps > Math.max(stillFps * 2, 10), `${fps.toFixed(1)} fps moving vs ${stillFps.toFixed(1)} still`);
  if (wireless) console.log("        (wireless debugging: the frame rate is the link's, not checked)");
  else check("on the cable, 30+ fps while it moves", fps >= 30, fps.toFixed(1));
  check("the latency is measured", lat.length > 10 && median(lat) > 0 && median(lat) < 1000, `${lat.length} samples, median ${median(lat)}`);

  // A window resized: the display follows it, in its own pixels.
  const W2 = W + 320;
  const H2 = H + 160;
  await evaluate(`window.act({json:{t:"resize",w:${W2},h:${H2}}})`);
  for (let i = 0; i < 40 && !out.includes(`Display resized: ${W2}x${H2}`); i++) await sleep(100);
  check("the display follows, and says so as scrcpy's patched server does", new RegExp(`Display resized: ${W2}x${H2}/\\d+ \\(id=${displayId}\\)`).test(out), out.split("\n").filter((l) => l.includes("resized")).join(" | "));
  const wmSize = shell(`wm size -d ${displayId}`);
  check("the phone agrees", wmSize.includes(`${W2}x${H2}`), wmSize);
  await sleep(1200);
  await evaluate(`window.act({scrolling:[${W2 >> 1},${H2 >> 1},1500]})`);
  await sleep(1600);
  r = await evaluate("window.result");
  const last = r.videos[r.videos.length - 1] || {};
  check("a new encoder at the new size", last.dw === W2 && last.dh === H2, JSON.stringify(last));
  check("decoded at the new size", (r.decodedSizes[`${W2}x${H2}`] || 0) > 5 && r.decodeErrors.length === 0, JSON.stringify(r.decodedSizes) + r.decodeErrors.join(";"));

  // The clipboard, both ways, whole.
  const mine = "clé — 日本語 🙂\nline two";
  await evaluate(`window.act({json:{t:"clip",text:${JSON.stringify(mine)}}})`);
  await sleep(600);
  const onPhone = Buffer.from(clipboard("get"), "base64").toString("utf8");
  check("the computer's clipboard reaches the phone's, whole", onPhone === mine, JSON.stringify(onPhone));
  r = await evaluate("window.result");
  check("…and is not sent back up as a copy", !r.clips.includes(mine), JSON.stringify(r.clips));
  const fromPhone = "phone copy — 한국어 ✓ 🙂\nsecond line";
  clipboard("set", Buffer.from(fromPhone, "utf8").toString("base64"));
  await sleep(800);
  r = await evaluate("window.result");
  check("what the phone copies reaches the window, whole", r.clips.includes(fromPhone), JSON.stringify(r.clips));

  // The adb shell going away is the end of the desktop.
  server.stdin.end();
  for (let i = 0; i < 50 && !out.includes("stopping"); i++) await sleep(100);
  await sleep(500);
  check("its stdin closing ends the server", out.includes("stopping: the computer's adb shell closed"), out.slice(-300));
  // By its id: a desktop the app is running has a display of the same name.
  const after = shell(`dumpsys display | grep -c -E 'displayId[ =]${displayId}[^0-9]' || true`);
  check("…and the display, with Settings on it, goes too", after === "0", after);
  r = await evaluate("window.result");
  check("the window hears the stream end", r.closed === true);
}
