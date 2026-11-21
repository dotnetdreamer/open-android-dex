#!/usr/bin/env node
// "Open in a browser" (doc/linux-browser.md), checked as far as a computer
// can without a phone.
//
//   node test/linux-browser-test.mjs [--browser <chrome or edge exe>] [--shots <dir>]
//
// The server is the launcher's own: LinuxBrowserServer, LinuxBrowser,
// LinuxBrowserCert and LinuxStreamSocket compiled for this JVM, serving the
// page from the APK's assets (test/linux-browser/BrowserHarness.java). Only
// the desktop is a stand-in: H.264 made by ffmpeg, sent as LinuxStream sends
// the phone's encoder's, and every message the page sends printed back.
//
// Then a real browser, headless, on this computer's network address, from
// its first look: the certificate warning clicked through as a person does,
// the code typed, the picture decoded and drawn, Low, Medium and High, the
// pointer, keys, the clipboard both ways, a resize, and every way a session
// ends.
//
//   node test/linux-browser-test.mjs --device <serial> [--size 1920x1080] [--throttle <Mb/s>]
//
// …the same on a real phone, over its Wi-Fi: the server, LinuxBrowserPeek,
// LinuxStream and the phone's hardware encoder, run by app_process as the
// shell user (test/linux-browser/PhoneHarness.java). Nothing is installed and
// no app is touched; the desktop is test/usb-desktop/StandinXvnc, changing
// every pixel of every frame. Says what each quality gets: frames a second,
// bitrate, the phone's encode time, this computer's decode time. With
// --throttle, also a second browser through a proxy here that lets the
// phone's stream through at that many Mb/s, once with the page saying when
// frames arrived (LinuxStreamRate) and once as a computer's window does (the
// rule of a full link): what each keeps the round trip at.
//
// Needs Node 24, a JDK (JAVA_HOME), ffmpeg, and Chrome or Edge; for --device
// the Android SDK, with the phone on adb.

import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import tls from 'node:tls';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
// OADX_LAUNCHER_DIR: another copy of the launcher's classes, as OADX_PAGE_DIR.
const LAUNCHER = process.env.OADX_LAUNCHER_DIR || path.join(ROOT, 'openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher');
// OADX_PAGE_DIR: another copy of the page to test (an older one, for a check
// that must fail on it), leaving the repo's own to whatever else serves it.
const ASSETS = process.env.OADX_PAGE_DIR || path.join(ROOT, 'openandroiddex-launcher/app/src/main/assets/linux/browser');
const args = process.argv.slice(2);
const argOf = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const SHOTS = argOf('--shots');
const JDK = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
const JAVA = JDK ? path.join(JDK, 'java') : 'java';
const JAVAC = JDK ? path.join(JDK, 'javac') : 'javac';

let passed = 0;
let failed = 0;
function check(name, ok, detail = '') {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? '  ok  ' : '  FAIL'} ${name}${!ok && detail ? ` — ${detail}` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-browser-'));
const cleanups = [];
function cleanup() {
  for (const c of cleanups.reverse()) {
    try {
      c();
    } catch {
      // gone already
    }
  }
  cleanups.length = 0;
  if (cleanedUp) return;
  cleanedUp = true;
  try {
    // A browser just gone still has its profile's files held for a while on
    // Windows (EPERM), its new files still being scanned: up to 30 s.
    fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 120, retryDelay: 250 });
  } catch (e) {
    console.log(`note: ${tmp} is left behind (${e.code}); delete it by hand`);
  }
}
let cleanedUp = false;
process.on('exit', cleanup);

/** This computer's address on its network: what a person types is the phone's, but the page is the same. */
function lanAddress() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal && /^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))\./.test(a.address)) return a.address;
    }
  }
  return null;
}

// ── the page's pure parts, under Node ───────────────────────────────────

console.log('h264.js');
{
  const h = await import(pathToFileURL(path.join(ASSETS, 'h264.js')).href);
  const sps = [0x67, 0x64, 0x00, 0x33, 0xac, 0x2b];
  const pps = [0x68, 0xee, 0x3c, 0x80];
  const idr = [0x65, 0x88, 0x84, 0x00, 0x33];
  const annexB = Uint8Array.from([0, 0, 0, 1, ...sps, 0, 0, 1, ...pps, 0, 0, 0, 1, 0x09, 0xf0, 0, 0, 0, 1, ...idr, 0, 0]);
  check('units split at 3- and 4-byte start codes', h.nalUnits(annexB).length === 4);
  check('the codec string from the SPS', h.codecString(Uint8Array.from(sps)) === 'avc1.640033');
  const c = h.avcC(Uint8Array.from(sps), Uint8Array.from(pps));
  check('avcC: version, profile, four-byte lengths, one SPS, the High fields', c[0] === 1 && c[1] === 0x64 && c[4] === 0xff && c[5] === 0xe1 && c.length === 11 + 6 + 4 + 4);
  const a = h.splitAccessUnit(annexB);
  check('an access unit: its parameter sets, a key frame, the AUD dropped, the slice length-prefixed',
    a.sps && a.pps && a.key && a.data.length === 4 + idr.length && a.data[3] === idr.length);
}

console.log('keysym.js');
{
  const k = await import(pathToFileURL(path.join(ASSETS, 'keysym.js')).href);
  const ev = (o) => ({ key: '', code: '', ctrlKey: false, altKey: false, metaKey: false, shiftKey: false, ...o });
  check('a letter types its character', k.keyDown(ev({ key: 'a', code: 'KeyA' }), false).keysym === 0x61);
  check('Ctrl+C on a Cyrillic layout is Ctrl+C', k.keyDown(ev({ key: 'с', code: 'KeyC', ctrlKey: true }), false).keysym === 0x63);
  check('F5 is F5', k.keyDown(ev({ key: 'F5', code: 'F5' }), false).keysym === 0xffc2);
  check('Caps Lock stays here', k.keyDown(ev({ key: 'CapsLock', code: 'CapsLock' }), false).kind === 'none');
  check("a Mac's Option types its character", k.keyDown(ev({ key: '@', code: 'KeyL', altKey: true }), true).kind === 'type');
  check('an emoji goes as Unicode', k.charKeysym('🙂') === (0x01000000 | 0x1f642));
}

if (args.includes('--device')) {
  await onDevice();
  console.log(`\n${passed} passed, ${failed} failed`);
  cleanup();
  process.exit(failed ? 1 : 0);
}

// ── the launcher's server, on this JVM ──────────────────────────────────

const classes = path.join(tmp, 'classes');
fs.mkdirSync(classes);
const javac = spawnSync(JAVAC, [
  '-nowarn', '-d', classes,
  ...['LinuxBrowser', 'LinuxBrowserCert', 'LinuxBrowserServer', 'LinuxStreamSocket', 'LinuxStreamAudio', 'LinuxAudioLag', 'LinuxFiles',
    'LinuxStreamRate'].map((c) => path.join(LAUNCHER, `${c}.java`)),
  path.join(HERE, 'linux-browser/DexLog.java'),
  path.join(HERE, 'linux-browser/BrowserHarness.java'),
  path.join(HERE, 'linux-browser/ShaperCheck.java'),
  path.join(HERE, 'linux-browser/RateCheck.java'),
  path.join(HERE, 'linux-browser/StoreCheck.java'),
], { encoding: 'utf8' });
check("the launcher's browser classes compile on a JVM, with no Android in reach", javac.status === 0, javac.stderr);
if (javac.status !== 0) process.exit(1);

console.log("the sound's shapes");
{
  // Pure tones through LinuxStreamAudio.Shaper: what the smaller rates keep,
  // and what they filter out rather than fold back.
  const r = spawnSync(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.ShaperCheck'], { encoding: 'utf8' });
  const got = {};
  for (const line of (r.stdout || '').split('\n')) {
    const m = /^SHAPE (\d+) (\d+) (\d+) (\d+) ([\d.]+)/.exec(line.trim());
    if (m) got[`${m[1]}/${m[2]}/${m[3]}`] = { frames: Number(m[4]), ratio: Number(m[5]) };
  }
  const near = (k, lo, hi) => got[k] && got[k].ratio >= lo && got[k].ratio <= hi;
  check('Low: 16 kHz mono, a second of sound into 16,000 frames; 440 Hz and 3 kHz come through whole',
    got['16000/1/440']?.frames === 16000 && near('16000/1/440', 0.97, 1.03) && near('16000/1/3000', 0.97, 1.03), r.stdout + r.stderr);
  check('…and 12 kHz, past what 16 kHz carries, is filtered out (under 1%), not folded back', near('16000/1/12000', 0, 0.01), JSON.stringify(got['16000/1/12000']));
  check('Medium without Opus: 24 kHz stereo; 440 Hz and 5 kHz whole, 15 kHz filtered out',
    got['24000/2/440']?.frames === 24000 && near('24000/2/440', 0.97, 1.03) && near('24000/2/5000', 0.97, 1.03) && near('24000/2/15000', 0, 0.01),
    JSON.stringify([got['24000/2/440'], got['24000/2/5000'], got['24000/2/15000']]));
  check('High: the tap as it is, 48 kHz stereo; and a mono of a tone the same in both ears is that tone',
    got['48000/2/440']?.frames === 48000 && near('48000/2/440', 0.999, 1.001) && near('48000/1/440', 0.999, 1.001));
}

console.log('the browsers let in, while requests read the list');
{
  // test/linux-browser/StoreCheck.java: 200 sign-outs, each with a browser
  // just let in, while two threads read the list as every request does. On
  // Windows a file being read cannot be replaced; given up at the first
  // refusal (before 2026-11-20), 8 to 15 sign-outs in 200 left the browser in.
  const r = spawnSync(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.StoreCheck'], { encoding: 'utf8' });
  const m = /STORE signouts=(\d+) left=(\d+) kept=(\d+) reads=(\d+)/.exec(r.stdout || '');
  check('a sign-out while browsers\' requests read the list: every browser out, and a new code, all 200 times',
    !!m && Number(m[1]) === 200 && Number(m[2]) === 0 && Number(m[3]) === 0 && Number(m[4]) > 500, (r.stdout + r.stderr).slice(-600));
}

console.log('the bitrate, from how late frames arrive (simulated links)');
{
  // LinuxStreamRate beside the rule it replaced (still the rule for a
  // computer that says only that a frame arrived), on links simulated in
  // test/linux-browser/RateCheck.java: frames sized by the bitrate, LinuxStream's
  // window of frames in flight, one bottleneck, and a phone's Wi-Fi in power
  // save holding acknowledgements. A simulation, not a network.
  const r = spawnSync(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.RateCheck'], { encoding: 'utf8' });
  const got = {};
  for (const line of (r.stdout || '').split('\n')) {
    const m = /^RATE (\S+) (\S+) (.*)$/.exec(line.trim());
    if (!m) continue;
    const kv = {};
    for (const part of m[3].split(' ')) {
      const [k, v] = part.split('=');
      kv[k] = Number(v);
    }
    got[`${m[1]}/${m[2]}`] = kv;
    console.log(`  ${m[1].padEnd(14)} ${m[2].padEnd(4)} late ${String(kv.medianMs).padStart(6)} ms median, ${String(kv.p95Ms).padStart(6)} ms at 95%, `
      + `${String(kv.mbps).padStart(6)} Mb/s, ${kv.cuts} cuts${kv.firstCutS >= 0 ? `, first ${kv.firstCutS} s after the drop` : ''}`);
  }
  const g = (k) => got[k] || {};
  check('a cable: neither rule cuts the bitrate', g('cable/new').cuts === 0 && g('cable/old').cuts === 0, r.stdout + r.stderr);
  check("Wi-Fi in power save, with room to spare: the new rule never cuts (frames arrive on time; only the acknowledgements wait), where the old one cut for nothing",
    g('powersave/new').cuts === 0 && g('powersave/old').cuts > 0 && g('powersave/new').mbps > g('powersave/old').mbps * 2,
    JSON.stringify([g('powersave/new'), g('powersave/old')]));
  check('a link slower than the picture: frames late by under 60 ms at the 95th percentile, a quarter of the old rule\'s or less',
    g('slow/new').p95Ms < 60 && g('slow/new').p95Ms * 4 < g('slow/old').p95Ms, JSON.stringify([g('slow/new'), g('slow/old')]));
  check('…the same with power save on top, without the bitrate collapsing',
    g('slowpowersave/new').p95Ms < 60 && g('slowpowersave/new').mbps >= 3, JSON.stringify(g('slowpowersave/new')));
  check('a link that drops from 40 to 6 Mb/s: the first cut within half a second, where the old rule took a second, and a quarter of its delay',
    g('drop/new').firstCutS >= 0 && g('drop/new').firstCutS < 0.5 && g('drop/old').firstCutS >= 1 && g('drop/new').p95Ms * 4 < g('drop/old').p95Ms,
    JSON.stringify([g('drop/new'), g('drop/old')]));
  check('a link that comes back: the bitrate climbs back to what the page asked for within 15 s',
    g('recover/new').mbpsAt >= 20, JSON.stringify([g('recover/new'), g('recover/old')]));
  check('a stream that starts at 25 Mb/s on a link of 1.5, its first key frame ten frames\' worth: under 100 ms at the 95th percentile, where the old rule took a third of a second',
    g('start/new').p95Ms < 100 && g('start/old').p95Ms > 2 * g('start/new').p95Ms, JSON.stringify([g('start/new'), g('start/old')]));
  check('a link that stops now and then (1.5 s of every 6): cut while it is stopped, with no acknowledgement to decide by, and carrying what the old rule carries',
    g('stalls/new').byHeld > 0 && g('stalls/new').mbps > g('stalls/old').mbps * 0.8, JSON.stringify([g('stalls/new'), g('stalls/old')]));
}

// The stand-in desktop: two sizes, as the phone's encoder makes them, no B-frames.
const h264 = path.join(tmp, 'h264');
fs.mkdirSync(h264);
for (const [name, size] of [['big', '1280x720'], ['small', '852x480']]) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=60`,
    '-t', '4', '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-bf', '0', '-g', '240',
    '-x264-params', 'aud=1:repeat-headers=1', '-tune', 'zerolatency', '-bsf:v', 'h264_mp4toannexb', '-f', 'h264',
    path.join(h264, `${name}.h264`)], { encoding: 'utf8' });
  if (r.status !== 0) {
    check(`ffmpeg makes the ${name} stand-in`, false, r.stderr || String(r.error));
    process.exit(1);
  }
}

const IP = lanAddress();
check("this computer has an address on a home network for the browser's test", !!IP);
if (!IP) process.exit(1);

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, '0.0.0.0', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}
const PORT = await freePort();
const state = path.join(tmp, 'state');

// Opus as libopus makes it, for the stand-in of the phone's encoder: Low's
// mono 32 kb/s and Medium's stereo 96 kb/s, 20 ms a packet, as the phone's.
const opusDir = path.join(tmp, 'opus');
fs.mkdirSync(opusDir);
for (const [name, ch, rate] of [['mono', 1, '32k'], ['stereo', 2, '96k']]) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
    '-t', '4', '-ac', String(ch), '-c:a', 'libopus', '-b:a', rate, '-frame_duration', '20', path.join(opusDir, `${name}.opus`)], { encoding: 'utf8' });
  if (r.status !== 0) {
    check(`ffmpeg makes the ${name} Opus`, false, r.stderr || String(r.error));
    process.exit(1);
  }
}

const harness = spawn(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.BrowserHarness', state, String(PORT),
  path.join(h264, 'big.h264'), path.join(h264, 'small.h264'), ASSETS,
  path.join(opusDir, 'mono.opus'), path.join(opusDir, 'stereo.opus')], { stdio: ['pipe', 'pipe', 'pipe'] });
cleanups.push(() => harness.kill());
const events = [];
/** Every other line the harness says, for command(). */
const outLines = [];
let ready = null;
let lastCode = null;
let lastTokens = null;
let harnessErr = '';
{
  let rest = '';
  harness.stdout.on('data', (d) => {
    rest += d;
    let i;
    while ((i = rest.indexOf('\n')) >= 0) {
      const line = rest.slice(0, i).replace(/\r$/, '');
      rest = rest.slice(i + 1);
      if (!line.startsWith('EV ')) outLines.push({ at: Date.now(), line });
      if (line.startsWith('READY ')) {
        const [, port, code, fp] = line.split(' ');
        ready = { port: Number(port), code, fp };
      } else if (line.startsWith('CODE ')) {
        lastCode = line.slice(5);
      } else if (line.startsWith('TOKENS ')) {
        lastTokens = Number(line.slice(7));
      } else if (line.startsWith('EV ')) {
        try {
          events.push({ at: Date.now(), ...JSON.parse(line.slice(3)) });
        } catch {
          events.push({ at: Date.now(), raw: line.slice(3) });
        }
      }
    }
  });
  harness.stderr.on('data', (d) => (harnessErr += d));
}
const say = (cmd) => harness.stdin.write(cmd + '\n');
async function until(pred, ms = 8000, step = 50) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await pred();
    if (v) return v;
    await sleep(step);
  }
  return null;
}
const evSince = (t, pred) => events.find((e) => e.at >= t && pred(e));
async function askCode() {
  lastCode = null;
  say('code');
  return until(() => lastCode, 3000);
}
/** A command to the harness, and the line it answers with (or null). */
async function command(cmd, answer, ms = 5000) {
  const t = Date.now();
  say(cmd);
  const hit = await until(() => outLines.find((l) => l.at >= t && l.line.startsWith(answer)), ms, 20);
  return hit ? hit.line : null;
}
const within = (promise, ms) => Promise.race([promise, sleep(ms).then(() => 0)]);
/** What the server said about itself last, as the Settings page reads it. */
const lastState = () => (events.filter((e) => e.changed !== undefined).at(-1) || {}).changed;

await until(() => ready, 15000);
check('the harness listens, with a code and a certificate', !!ready, harnessErr.slice(-400));
if (!ready) process.exit(1);
const BASE = `https://${IP}:${PORT}`;

// ── the door, from Node ─────────────────────────────────────────────────

/** One request, the certificate taken as it is (the browser part asks a person). */
function request(method, urlPath, { host = IP, headers = {}, body = null, plain = false, localAddress } = {}) {
  return new Promise((resolve) => {
    const mod = plain ? http : https;
    // A browser's form says its length; Node would otherwise send chunks.
    const all = body != null && !headers['Transfer-Encoding'] ? { ...headers, 'Content-Length': Buffer.byteLength(body) } : { ...headers };
    const req = mod.request({
      host, port: PORT, method, path: urlPath, headers: all, rejectUnauthorized: false, localAddress,
      agent: false,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8'), socket: res.socket }));
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message }));
    if (body != null) req.write(body);
    req.end();
  });
}
const form = (o) => new URLSearchParams(o).toString();
const FORM = { 'Content-Type': 'application/x-www-form-urlencoded' };
const said = (r) => {
  try {
    return JSON.parse(r.body);
  } catch {
    return {};
  }
};
/** A code typed by the page: from this computer's address, as the page's own. */
const pairWith = (code) => request('POST', '/pair', { headers: { ...FORM, Origin: BASE }, body: form({ code }) });
/** Six digits that are not {@code code}. */
const notThe = (code) => String((Number(code) + 1) % 1e6).padStart(6, '0');

/** A connection that says nothing: open, and kept. */
function rawConnect() {
  return new Promise((resolve) => {
    const s = net.connect({ host: IP, port: PORT }, () => resolve(s));
    s.on('error', () => {});
  });
}

/** Bytes in, whatever comes back, and whether the far end closed, within {@code ms}. */
function rawRequest(bytes, ms) {
  return new Promise((resolve) => {
    let data = '';
    const s = net.connect({ host: IP, port: PORT }, () => s.write(bytes));
    s.on('data', (d) => (data += d));
    s.on('error', () => {});
    s.on('close', () => resolve({ closed: true, data }));
    setTimeout(() => {
      resolve({ closed: false, data });
      s.destroy();
    }, ms);
  });
}

/** A request's head one byte every 200 ms, over TLS: never idle long enough for a read to time out. */
function trickle(ms) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    let got = '';
    let timer = null;
    const s = tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, () => {
      s.write(`GET / HTTP/1.1\r\nHost: ${IP}:${PORT}\r\nX-Slow: `);
      timer = setInterval(() => {
        if (!s.destroyed) s.write('a');
      }, 200);
    });
    s.on('data', (d) => (got += d));
    s.on('error', () => {});
    s.on('close', () => {
      clearInterval(timer);
      resolve({ after: Date.now() - t0, got });
    });
    setTimeout(() => {
      clearInterval(timer);
      resolve({ after: -1, got });
      s.destroy();
    }, ms);
  });
}

/** A file asked for over TLS, the connection left open afterwards. */
function keepAlive() {
  return new Promise((resolve) => {
    let got = '';
    const c = { ok: false };
    const s = tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, () => {
      s.write(`GET /icon.svg HTTP/1.1\r\nHost: ${IP}:${PORT}\r\nConnection: keep-alive\r\n\r\n`);
    });
    c.closed = new Promise((r) => s.on('close', () => r(Date.now())));
    s.on('error', () => {});
    s.on('data', (d) => {
      got += d;
      const end = got.indexOf('\r\n\r\n');
      const len = /content-length: (\d+)/i.exec(got);
      if (end > 0 && len && got.length >= end + 4 + Number(len[1])) {
        c.ok = got.startsWith('HTTP/1.1 200') && /connection: keep-alive/i.test(got);
        resolve(c);
      }
    });
    setTimeout(() => resolve(c), 3000);
  });
}

/**
 * One connection kept for file after file, as a browser keeps one: a file
 * asked for {@code gap} ms after the last one came, {@code n} times. How many
 * came, and when the far end closed (0: it did not).
 */
function reuse(n, gap) {
  return new Promise((resolve) => {
    const t0 = Date.now();
    let got = '';
    let answered = 0;
    const ask = () => s.write(`GET /icon.svg HTTP/1.1\r\nHost: ${IP}:${PORT}\r\nConnection: keep-alive\r\n\r\n`);
    const s = tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, ask);
    s.on('error', () => {});
    s.on('close', () => resolve({ answered, closedAt: Date.now() - t0 }));
    s.on('data', (d) => {
      got += d;
      for (;;) {
        const end = got.indexOf('\r\n\r\n');
        if (end < 0) return;
        const len = /content-length: (\d+)/i.exec(got.slice(0, end));
        if (!len || got.length < end + 4 + Number(len[1])) return;
        if (got.startsWith('HTTP/1.1 200')) answered++;
        got = got.slice(end + 4 + Number(len[1]));
        if (answered >= n) {
          resolve({ answered, closedAt: 0, after: Date.now() - t0 });
          s.destroy();
          return;
        }
        setTimeout(() => {
          if (!s.destroyed) ask();
        }, gap);
      }
    });
    setTimeout(() => {
      resolve({ answered, closedAt: 0, after: Date.now() - t0 });
      s.destroy();
    }, n * gap + 5000);
  });
}


console.log('the door');
{
  const r = await request('GET', '/', { plain: true });
  check('plain http on the same port is served too, for a browser that refuses the certificate: the same page',
    r.status === 200 && r.body.includes('viewer.js') && r.body.includes('id="choose"'), `${r.status} ${r.body.slice(0, 60)}`);
  // Media from the page's own MediaSource, and from its own address (a video
  // or a song of Linux's in Files); frames only from there (a PDF of Linux's).
  check('…under the same policy, which lets the page play a MediaSource of its own and its own address\'s files, and nothing else',
    / media-src 'self' blob:;/.test(r.headers['content-security-policy'] || '') && / frame-src 'self';/.test(r.headers['content-security-policy'] || '')
      && (r.headers['content-security-policy'] || '').includes("script-src 'self'") && (r.headers['content-security-policy'] || '').includes("default-src 'none'"),
    r.headers['content-security-policy']);
  const mse = await request('GET', '/mse.js', { plain: true });
  check("…and the video player's part is served, as a script", mse.status === 200 && /javascript/.test(mse.headers['content-type']) && mse.body.includes('class MsePlayer'));

  const page = await request('GET', '/');
  check('the page over https', page.status === 200 && page.body.includes('viewer.js'));
  const csp = page.headers['content-security-policy'] || '';
  check("the page may load only its own files: no inline script, framed by nobody",
    csp.includes("script-src 'self'") && csp.includes("frame-ancestors 'none'") && page.headers['x-content-type-options'] === 'nosniff');
  const cert = page.socket.getPeerCertificate(true);
  const x = cert && cert.raw ? new crypto.X509Certificate(cert.raw) : null;
  check('the certificate names this address, and the fingerprint is the one the phone shows',
    x && x.subjectAltName.includes(`IP Address:${IP}`) && x.fingerprint256 === ready.fp, x && `${x.subjectAltName} ${x.fingerprint256}`);
  const days = x ? (new Date(x.validTo) - Date.now()) / 86400000 : 0;
  check("for 397 days, under the 398 Apple takes, and not a CA", days > 390 && days < 398 && !x.ca, `${days}`);
  check('signed by its own key', x && x.verify(x.publicKey));

  const js = await request('GET', '/viewer.js');
  check('the script is served as a script', js.status === 200 && /javascript/.test(js.headers['content-type']));
  check('a file the page does not have is not there', (await request('GET', '/../../etc/passwd')).status === 404
    && (await request('GET', '/tls-key.der')).status === 404 && (await request('GET', '/code')).status === 404);
  const anon = await request('GET', '/hello');
  check("the phone's name is not told to a browser it has not let in (it is often its owner's)",
    anon.status === 200 && said(anon).v === 1 && said(anon).name === undefined, anon.body);

  const loop = await request('GET', '/', { host: '127.0.0.1', headers: { Host: `127.0.0.1:${PORT}` } });
  const loopPlain = await request('GET', '/', { host: '127.0.0.1', plain: true, headers: { Host: `127.0.0.1:${PORT}` } });
  check('from loopback, which every app on the phone can reach, nothing is answered, over https or plain',
    loop.status === 0 && loopPlain.status === 0, `${loop.status} ${loopPlain.status}`);

  const rebind = await request('GET', '/', { headers: { Host: `evil.example:${PORT}` } });
  check('a name in Host, not an address, is not answered (DNS rebinding)', rebind.status === 421, `${rebind.status}`);
  const code = await askCode();
  const cross = await request('POST', '/pair', { headers: { ...FORM, Origin: 'https://evil.example' }, body: form({ code }) });
  check("another site's page cannot ask for a token, even with the right code", cross.status === 403, `${cross.status}`);
  const crossSite = await request('POST', '/pair', { headers: { ...FORM, 'Sec-Fetch-Site': 'cross-site' }, body: form({ code }) });
  check('nor one that says it is cross-site without an Origin', crossSite.status === 403, `${crossSite.status}`);
  const chunked = await request('POST', '/pair', { headers: { ...FORM, 'Transfer-Encoding': 'chunked', Origin: BASE }, body: form({ code: '123456' }) });
  check('a body in chunks is answered, not read: 411', chunked.status === 411, `${chunked.status}`);
}

console.log('the code, from this computer');
let T1 = null;
{
  const code = await askCode();
  await command('close', 'OK close');
  let r = await pairWith(code);
  check('the phone not showing its code: even the right one opens nothing', r.status === 403 && said(r).error === 'closed', `${r.status} ${r.body}`);
  await command('open', 'OK open');

  r = await pairWith(notThe(code));
  check('a wrong code is refused, and says so', r.status === 403 && said(r).error === 'wrong', `${r.status} ${r.body}`);
  for (let i = 0; i < 3; i++) await pairWith(notThe(code));
  r = await pairWith(notThe(code));
  check('the fifth wrong code from one address locks it out for 30 s', r.status === 429 && said(r).error === 'locked' && said(r).retryIn === 30, `${r.status} ${r.body}`);
  r = await pairWith(code);
  check('while locked out, even the right code waits', r.status === 429, `${r.status}`);
  // A fresh server: the address's count goes, the code's own five stay.
  await command('restart', 'OK restarted');

  r = await pairWith(code);
  T1 = said(r).token;
  check('the right code: a token', r.status === 200 && /^[A-Za-z0-9_-]{43}$/.test(T1 || ''), `${r.status} ${r.body}`);
  r = await pairWith(code);
  check('…once: the same six digits again are a wrong code', r.status === 403 && said(r).error === 'wrong', `${r.status} ${r.body}`);
  const next = await askCode();
  check('…and the phone shows a new one', next && next !== code, `${code} → ${next}`);

  const named = await request('GET', '/hello', { headers: { Authorization: `Bearer ${T1}` } });
  check("a browser let in is told the phone's name", said(named).name === 'Harness Phone', named.body);
  const madeUp = await request('GET', '/hello', { headers: { Authorization: `Bearer ${'B'.repeat(43)}` } });
  check('…and one with a made-up token is not', said(madeUp).name === undefined, madeUp.body);

  // Seven at once from one address, five allowed: one at a time, so no race past the limit.
  await command('restart', 'OK restarted');
  const seven = await Promise.all(Array.from({ length: 7 }, () => pairWith(notThe(next))));
  const st = seven.map((x) => x.status);
  check('seven wrong codes at once from one address: four are told wrong, the fifth on are locked out',
    st.filter((s) => s === 403).length === 4 && st.filter((s) => s === 429).length === 3, JSON.stringify(st));
  await command('restart', 'OK restarted');
}

console.log("the code's own budget");
{
  // One address is not the limit that matters: a phone's apps, or a
  // network's peers, can come from as many as they like. The code's own can.
  await command('lockafter 1000', 'OK lockafter');
  const code = (await command('newcode', 'CODE ')).slice(5);
  const answers = [];
  for (let wave = 0; wave < 5; wave++) {
    const rs = await Promise.all(Array.from({ length: 6 }, () => pairWith(notThe(code))));
    answers.push(...rs.map((x) => (x.status === 0 ? `none ${x.error}` : said(x).error)));
    await sleep(150);
  }
  const wrongs = answers.filter((a) => a === 'wrong').length;
  const used = answers.filter((a) => a === 'used').length;
  check('thirty wrong codes, six at a time: nine are told wrong, and from the tenth on the code is used up',
    wrongs === 9 && used === 21, JSON.stringify(answers));
  let r = await pairWith(code);
  check('…after which even the right code opens nothing', r.status === 403 && said(r).error === 'used', `${r.status} ${r.body}`);
  const fresh = (await command('newcode', 'CODE ')).slice(5);
  r = await pairWith(fresh);
  check('a new code made on the phone works', r.status === 200 && !!said(r).token, `${r.status} ${r.body}`);
  await command('lockafter 5', 'OK lockafter');
  await command('restart', 'OK restarted');
}

console.log('holding the door');
{
  await sleep(300);
  const held = await Promise.all(Array.from({ length: 16 }, () => rawConnect()));
  await sleep(300);
  const ninth = await rawRequest(`GET / HTTP/1.1\r\nHost: ${IP}:${PORT}\r\n\r\n`, 3000);
  check('sixteen connections from one address that say nothing (a browser\'s six for each scheme, and more): a seventeenth is closed unread',
    ninth.closed && !ninth.data, JSON.stringify(ninth));
  held.forEach((h) => h.destroy());
  await sleep(500);
  check('…and once they go, the address is answered again', (await request('GET', '/', { plain: true })).status === 200);

  await command('timeouts 1500 75000', 'OK timeouts');
  const slow = await trickle(8000);
  check('a request that comes a byte at a time is closed at its deadline, unanswered',
    slow.after >= 1000 && slow.after < 4000 && !slow.got, JSON.stringify(slow));
  // The deadline times each request, not the connection's life: a browser
  // keeps a connection for the page's next file, and one cut mid-file at the
  // deadline set when it came was a module of the page lost (Chrome:
  // ERR_CONNECTION_ABORTED).
  const reused = await reuse(8, 500);
  check('a connection kept for file after file is answered for each, past the time it had to be let in',
    reused.answered === 8 && reused.closedAt === 0, JSON.stringify(reused));
  await command('timeouts 30000 75000', 'OK timeouts');

  const kept = await keepAlive();
  check('a connection kept open after its answer, as a browser keeps one', kept.ok);
  let t = Date.now();
  await command('stop', 'OK stopped');
  const at = await within(kept.closed, 3000);
  check('switched off on the phone: it is let go at once', at && at - t < 1500, at ? `${at - t} ms` : 'still open');
  const none = await request('GET', '/', { plain: true });
  check('…and nothing listens', none.status === 0, `${none.status}`);
  await command('restart', 'OK restarted');
  check('…until it is on again', (await request('GET', '/', { plain: true })).status === 200);

  const rival = await command('rival', 'RIVAL ');
  check('another app holding the port: said ("failed port"), not moved to a port a bookmark does not know',
    rival === `RIVAL taken ${PORT} failed port ${PORT}`, String(rival));
}

/**
 * A WebSocket to the stream, by hand: the handshake's headers are the test.
 * Resolves once the server has answered the upgrade (or closed).
 */
/**
 * A WebSocket of this test's own, frame by frame. {@code ack}: each video
 * frame acknowledged the moment it arrives, as the desktop app does
 * (UsbDesktop.tsx): 'plain' with one byte (the app before 2026-11-21), 'timed'
 * with the time it came, in ms (the app from then, and every browser page),
 * or a function that makes the bytes (the app's own wire.ts).
 */
function wsConnect({ origin = BASE, ip = IP, port = PORT, host = `${ip}:${port}`, token = null, plain = false, ack = null } = {}) {
  return new Promise((resolve) => {
    const where = token == null ? '/stream' : `/stream?t=${encodeURIComponent(token)}`;
    const opened = () => {
      const key = crypto.randomBytes(16).toString('base64');
      s.write(`GET ${where} HTTP/1.1\r\nHost: ${host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n${origin ? `Origin: ${origin}\r\n` : ''}\r\n`);
    };
    const s = plain ? net.connect({ host: ip, port }, opened) : tls.connect({ host: ip, port, rejectUnauthorized: false }, opened);
    // frames: every binary one; sound: the 0x03 ones, when each came, its bytes, and whether it was silence
    const c = { status: 0, texts: [], frames: 0, closedAt: 0, sound: [] };
    c.closed = new Promise((r) => s.on('close', () => {
      c.closedAt = Date.now();
      r(c.closedAt);
      resolve(c);
    }));
    /** One masked frame, as a browser sends it. */
    c.frame = (opcode, payload, fin = true) => {
      if (s.destroyed) return;
      const p = Buffer.from(payload);
      const mask = crypto.randomBytes(4);
      const b0 = (fin ? 0x80 : 0) | opcode;
      const head = p.length < 126 ? Buffer.from([b0, 0x80 | p.length]) : Buffer.from([b0, 0x80 | 126, p.length >> 8, p.length & 0xff]);
      s.write(Buffer.concat([head, mask, Buffer.from(p.map((v, i) => v ^ mask[i & 3]))]));
    };
    c.send = (obj) => c.frame(1, JSON.stringify(obj));
    c.end = () => s.destroy();
    // Reading stopped and started again, as a page busy elsewhere reads late.
    c.pause = () => s.pause();
    c.resume = () => s.resume();
    let buf = Buffer.alloc(0);
    s.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!c.status) {
        const end = buf.indexOf('\r\n\r\n');
        if (end < 0) return;
        c.status = Number(buf.slice(0, end).toString().split(' ')[1]);
        buf = buf.slice(end + 4);
        resolve(c);
      }
      while (buf.length >= 2) {
        let len = buf[1] & 0x7f;
        let off = 2;
        if (len === 126) {
          if (buf.length < 4) break;
          len = buf.readUInt16BE(2);
          off = 4;
        } else if (len === 127) {
          if (buf.length < 10) break;
          len = Number(buf.readBigUInt64BE(2));
          off = 10;
        }
        if (buf.length < off + len) break;
        const op = buf[0] & 0x0f;
        if (op === 1) c.texts.push(buf.slice(off, off + len).toString());
        else if (op === 2) {
          c.frames++; // the picture, the pointer, the sound
          if (ack && buf[off] === 0x01 && len > 10 && !(buf[off + 1] & 2)) {
            if (typeof ack === 'function') {
              c.frame(2, ack());
            } else if (ack === 'timed') {
              const a = Buffer.alloc(9);
              a[0] = 0x12;
              a.writeDoubleBE(performance.timeOrigin + performance.now(), 1);
              c.frame(2, a);
            } else {
              c.frame(2, [0x12]);
            }
          }
          if (buf[off] === 0x03) {
            c.sound.push({ at: Date.now(), codec: buf[off + 1], bytes: len - 10, loud: buf.slice(off + 10, off + len).some((v) => v !== 0) });
          }
        } else if (op === 8) c.closeFrame = true;
        buf = buf.slice(off + len);
      }
    });
    s.on('error', () => {});
    setTimeout(() => resolve(c), 5000);
  });
}

console.log('the stream');
{
  const hello = { t: 'hello', v: 1, start: true, w: 1280, h: 720 };
  const evil = await wsConnect({ origin: 'https://evil.example', token: T1 });
  check("another site's page cannot open the stream, even with a browser's token", evil.status === 403, `${evil.status}`);
  evil.end();

  let t = Date.now();
  const none = await wsConnect();
  none.send(hello);
  await within(none.closed, 3000);
  check('a stream with no token is told it is not let in and closed, its hello never read',
    none.status === 101 && none.texts.length === 1 && JSON.parse(none.texts[0]).state === 'unauthorized' && none.closedAt > 0
    && none.frames === 0 && !evSince(t, (e) => e.hello), JSON.stringify({ ...none, closed: undefined }));

  t = Date.now();
  const forged = await wsConnect({ token: 'A'.repeat(43) });
  forged.send(hello);
  await within(forged.closed, 3000);
  check('nor with a token the phone never gave', forged.texts.length === 1 && JSON.parse(forged.texts[0]).state === 'unauthorized'
    && !evSince(t, (e) => e.hello), JSON.stringify(forged.texts));

  t = Date.now();
  const pieces = await wsConnect({ token: T1 });
  pieces.frame(1, '{', false);
  for (let i = 0; i < 1100; i++) pieces.frame(0, ' ', false);
  pieces.frame(0, '}', true);
  await within(pieces.closed, 3000);
  check('a message in more than 1,024 pieces closes the stream before it is taken', pieces.closedAt > 0 && !evSince(t, (e) => e.hello));

  t = Date.now();
  const bigPing = await wsConnect({ token: T1 });
  bigPing.frame(9, 'x'.repeat(126));
  await within(bigPing.closed, 3000);
  check('a ping longer than the 125 bytes a control frame may carry closes it too', bigPing.closedAt > 0 && !evSince(t, (e) => e.hello));

  // Let in, then silent: a laptop shut, gone with no FIN.
  await command('timeouts 30000 1500', 'OK timeouts');
  t = Date.now();
  const quiet = await wsConnect({ token: T1 });
  quiet.send(hello);
  const h = await until(() => evSince(t, (e) => e.hello), 3000);
  check('with its token in the request, a browser is let in: its hello reaches the desktop, no token in it',
    h && h.hello.t === 'hello' && !('token' in h.hello) && h.hello.start === true, JSON.stringify(h && h.hello));
  await until(() => quiet.frames > 0, 3000);
  check('…and the picture comes', quiet.frames > 0 && quiet.texts.some((x) => JSON.parse(x).t === 'video'));
  const at = await within(quiet.closed, 6000);
  // The socket closes a moment before the harness's line about it arrives.
  const gone = await until(() => evSince(t, (e) => e.ended), 2000);
  check('one that then says nothing at all for its time is let go by the phone', at && gone && h && at - h.at >= 1000 && at - h.at < 4500,
    at && h ? `${at - h.at} ms` : 'still open');
  await command('timeouts 30000 75000', 'OK timeouts');
  await until(() => /^listening /.test(lastState() || ''), 2000);
  check('…and the phone says nobody is watching', /^listening /.test(lastState() || ''), String(lastState()));
}

/**
 * A pairing sent but for its last byte, so the server waits on it; {@code go()}
 * sends that byte and resolves with the answer's status and body. Lets a test
 * put the code's check and something else at the same moment.
 */
function pairHeld(code) {
  return new Promise((resolve) => {
    const body = form({ code });
    const s = tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, () => {
      s.write(`POST /pair HTTP/1.1\r\nHost: ${IP}:${PORT}\r\nOrigin: ${BASE}\r\nContent-Type: application/x-www-form-urlencoded\r\n`
        + `Content-Length: ${body.length}\r\nConnection: close\r\n\r\n${body.slice(0, -1)}`);
      let data = '';
      const answer = new Promise((r) => {
        s.on('data', (d) => (data += d));
        s.on('close', () => {
          const end = data.indexOf('\r\n\r\n');
          r({ status: Number(data.split(' ')[1]) || 0, body: end >= 0 ? data.slice(end + 4) : '' });
        });
      });
      resolve({ go: () => (s.write(body.slice(-1)), answer) });
    });
    s.on('error', () => resolve(null));
  });
}

console.log('plain http, from Node');
let TP = null;
{
  const PLAIN_BASE = `http://${IP}:${PORT}`;
  const code = (await command('newcode', 'CODE ')).slice(5);
  const wrongDoor = await request('POST', '/pair', { headers: { ...FORM, Origin: PLAIN_BASE }, body: form({ code }) });
  check('the plain page cannot pair through the TLS door: its origin is not that one', wrongDoor.status === 403, `${wrongDoor.status}`);
  const wrongPlain = await request('POST', '/pair', { plain: true, headers: { ...FORM, Origin: BASE }, body: form({ code }) });
  check('…nor the secure page through the plain one', wrongPlain.status === 403, `${wrongPlain.status}`);
  const r = await request('POST', '/pair', { plain: true, headers: { ...FORM, Origin: PLAIN_BASE }, body: form({ code }) });
  TP = said(r).token;
  check('over plain http, the right code from its own page: a token, as over https', r.status === 200 && /^[A-Za-z0-9_-]{43}$/.test(TP || ''), `${r.status} ${r.body}`);
  const named = await request('GET', '/hello', { plain: true, headers: { Authorization: `Bearer ${TP}` } });
  check("…and with it the phone's name", said(named).name === 'Harness Phone', named.body);

  const evil = await wsConnect({ plain: true, origin: 'http://evil.example', token: TP });
  check("another site's page cannot open the plain stream either", evil.status === 403, `${evil.status}`);
  evil.end();
  const cross = await wsConnect({ plain: true, origin: BASE, token: TP });
  check('…nor the secure page through the plain door', cross.status === 403, `${cross.status}`);
  cross.end();
  const t = Date.now();
  const ws = await wsConnect({ plain: true, origin: PLAIN_BASE, token: TP });
  ws.send({ t: 'hello', v: 1, start: false, w: 1280, h: 720 });
  const h = await until(() => evSince(t, (e) => e.hello), 3000);
  await until(() => ws.frames > 5, 3000);
  check('over plain http, ws:// with a token: let in, and the picture comes', ws.status === 101 && !!h && ws.frames > 5
    && ws.texts.some((x) => JSON.parse(x).t === 'video'), `${ws.status} frames ${ws.frames}`);
  check("…said on the phone's log as not encrypted", h && /not encrypted/.test(h.who), h && h.who);
  ws.end();
  await until(() => /^listening /.test(lastState() || ''), 3000);
}

console.log('sound');
{
  const hello = { t: 'hello', v: 1, start: false, w: 1280, h: 720 };
  // Not asked: nothing read, nothing sent.
  let t = Date.now();
  const mute = await wsConnect({ token: T1 });
  mute.send(hello);
  await until(() => evSince(t, (e) => e.hello), 3000);
  await sleep(2500);
  check('a browser that does not ask for sound gets none, and the tap is not even opened',
    mute.sound.length === 0 && !evSince(t, (e) => e.tap === 'open'), `${mute.sound.length} runs`);
  mute.end();
  await until(() => evSince(t, (e) => e.ended), 3000);

  // Asked in the hello, at High: Linux's sound as the tap gives it.
  const said = (c, from) => c.texts.slice(from).map((x) => JSON.parse(x)).filter((m) => m.t === 'sound');
  t = Date.now();
  const ws = await wsConnect({ token: T1 });
  ws.send({ ...hello, audio: true, mode: 'high', codecs: ['pcm'] });
  await until(() => ws.sound.length > 20, 4000);
  const f0 = said(ws, 0)[0];
  check('asked in the hello at High: the form said first (PCM, 48 kHz, stereo), then the tap read for it and the sound, as whole sample frames',
    f0 && f0.codec === 'pcm' && f0.rate === 48000 && f0.channels === 2 && f0.mode === 'high' && !!evSince(t, (e) => e.tap === 'open')
    && ws.sound.length > 20 && ws.sound.every((x) => x.codec === 0 && x.bytes > 0 && x.bytes % 4 === 0),
    `${JSON.stringify(f0)} ${ws.sound.length} runs`);
  // Four seconds of the stand-in's tone-and-silence: what is sent, and the gaps.
  const from = Date.now();
  await sleep(4200);
  const runs = ws.sound.filter((x) => x.at >= from);
  const bytes = runs.reduce((a, x) => a + x.bytes, 0);
  let gap = 0;
  for (let i = 1; i < runs.length; i++) gap = Math.max(gap, runs[i].at - runs[i - 1].at);
  // A tone second sent whole, then half a second of its silence, then nothing until the tone is back: 1.5 s in every 2.
  const perSec = bytes / ((runs.at(-1).at - from) / 1000);
  check('silence longer than half a second is not sent: about three quarters of real time, in runs with a gap of about half a second between',
    perSec > 192000 * 0.55 && perSec < 192000 * 0.95 && gap >= 300 && gap <= 800, `${Math.round(perSec)} B/s, longest gap ${gap} ms`);
  check('…and what is sent is the sound, the silence before a gap only', runs.filter((x) => x.loud).length >= runs.length * 0.5,
    `${runs.filter((x) => x.loud).length} of ${runs.length} loud`);

  // The sound follows the picture's quality, live, at the next read.
  const rateOver = async (secs) => {
    const a = Date.now();
    await sleep(secs * 1000);
    const rs = ws.sound.filter((x) => x.at >= a);
    return { bytes: rs.reduce((n, x) => n + x.bytes, 0) / secs, runs: rs.length / secs, codecs: [...new Set(rs.map((x) => x.codec))] };
  };
  let before = ws.texts.length;
  ws.send({ t: 'quality', mode: 'medium', bitrate: 9000000, scale: 1, fps: 60 });
  await until(() => said(ws, before).length > 0, 3000);
  let f = said(ws, before)[0];
  await sleep(500);
  let r = await rateOver(4);
  check('Medium, for a page without Opus: 24 kHz stereo PCM, said, then half of High\'s bytes',
    f && f.codec === 'pcm' && f.rate === 24000 && f.channels === 2 && r.codecs.join() === '0'
    && r.bytes > 96000 * 0.55 && r.bytes < 96000 * 0.95, `${JSON.stringify(f)} ${Math.round(r.bytes)} B/s`);
  before = ws.texts.length;
  ws.send({ t: 'quality', mode: 'low', bitrate: 2000000, scale: 2 / 3, fps: 60 });
  await until(() => said(ws, before).length > 0, 3000);
  f = said(ws, before)[0];
  await sleep(500);
  r = await rateOver(4);
  check('Low, for a page without Opus: 16 kHz mono PCM, a sixth of High\'s bytes',
    f && f.codec === 'pcm' && f.rate === 16000 && f.channels === 1 && r.codecs.join() === '0'
    && r.bytes > 32000 * 0.55 && r.bytes < 32000 * 0.95, `${JSON.stringify(f)} ${Math.round(r.bytes)} B/s`);

  // A page that decodes Opus: Medium and Low are Opus, High stays PCM.
  before = ws.texts.length;
  t = Date.now();
  ws.send({ t: 'audio', on: true, codecs: ['opus', 'pcm'] });
  ws.send({ t: 'quality', mode: 'medium', bitrate: 9000000, scale: 1, fps: 60 });
  await until(() => said(ws, before).some((m) => m.codec === 'opus'), 3000);
  f = said(ws, before).find((m) => m.codec === 'opus');
  await sleep(500);
  r = await rateOver(4);
  check('Medium, for a page that decodes Opus: Opus 96 kb/s stereo, one packet each 20 ms of sound, a fraction of the bytes',
    f && f.rate === 48000 && f.channels === 2 && f.kbps === 96 && r.codecs.join() === '1' && !!evSince(t, (e) => e.opus === 2)
    && r.runs > 50 * 0.55 && r.runs < 50 * 0.95 && r.bytes < 20000, `${JSON.stringify(f)} ${r.runs} packets/s ${Math.round(r.bytes)} B/s`);
  before = ws.texts.length;
  t = Date.now();
  ws.send({ t: 'quality', mode: 'low', bitrate: 2000000, scale: 2 / 3, fps: 60 });
  await until(() => said(ws, before).length > 0, 3000);
  f = said(ws, before)[0];
  await sleep(500);
  r = await rateOver(4);
  check('Low, for a page that decodes Opus: Opus 32 kb/s mono, the stereo encoder closed for it',
    f && f.codec === 'opus' && f.channels === 1 && f.kbps === 32 && r.codecs.join() === '1' && !!evSince(t, (e) => e.opusClosed === 2)
    && !!evSince(t, (e) => e.opus === 1) && r.bytes < 8000, `${JSON.stringify(f)} ${Math.round(r.bytes)} B/s`);
  before = ws.texts.length;
  ws.send({ t: 'quality', mode: 'high', bitrate: 20000000, scale: 1, fps: 60 });
  await until(() => said(ws, before).length > 0, 3000);
  f = said(ws, before)[0];
  await sleep(500);
  r = await rateOver(3);
  check('High, even for a page that decodes Opus: the tap as it is, lossless, PCM 48 kHz stereo',
    f && f.codec === 'pcm' && f.rate === 48000 && f.channels === 2 && r.codecs.join() === '0' && r.bytes > 192000 * 0.5,
    `${JSON.stringify(f)} ${Math.round(r.bytes)} B/s`);

  // Off: the tap let go, nothing more.
  t = Date.now();
  ws.send({ t: 'audio', on: false });
  await until(() => evSince(t, (e) => e.tap === 'closed'), 3000);
  await sleep(300);
  const at = ws.sound.length;
  await sleep(2000);
  check('sound turned off: the tap is let go and nothing more is sent', !!evSince(t, (e) => e.tap === 'closed') && ws.sound.length === at,
    `${at} → ${ws.sound.length}`);
  t = Date.now();
  ws.send({ t: 'audio', on: true });
  await until(() => ws.sound.length > at + 10, 4000);
  check('…and on again: read and sent again', ws.sound.length > at + 10 && !!evSince(t, (e) => e.tap === 'open'), `${at} → ${ws.sound.length}`);
  t = Date.now();
  ws.end();
  await until(() => evSince(t, (e) => e.tap === 'closed'), 3000);
  check('the browser gone: the tap is let go with it', !!evSince(t, (e) => e.tap === 'closed'));
  await until(() => /^listening /.test(lastState() || ''), 3000);
}

console.log('while a browser is being let in');
{
  // Its token checked, its hello read, and in neither place a sign-out or the
  // switch looks: no longer a connection the server drops, not yet the stream
  // that watches. Held there (slowhandoff) while each happens.
  const hello = { t: 'hello', v: 1, start: true, w: 1280, h: 720 };
  const letIn = async () => said(await pairWith(await askCode())).token;
  await command('slowhandoff 1500', 'OK slowhandoff');
  let t = Date.now();
  let c = await wsConnect({ token: await letIn() });
  c.send(hello);
  let handoff = await until(() => outLines.find((l) => l.at >= t && l.line.startsWith('HANDOFF ')), 4000);
  c.send({ t: 'ping', n: 1 }); // as the page does every two seconds: unread, it must not cost the last word
  await command('signout', 'CODE ');
  await within(c.closed, 5000);
  // The harness says so a moment after the socket closes.
  const away = await until(() => evSince(t, (e) => e.turnedAway), 2000);
  check('signed out while a browser is being let in: told so as it would take the desktop, and never watches',
    !!handoff && c.texts.some((x) => JSON.parse(x).state === 'unauthorized') && c.frames === 0 && c.closedAt > 0
    && !evSince(t, (e) => e.hello) && !!away && away.why === 'unauthorized',
    JSON.stringify({ handoff: !!handoff, texts: c.texts, frames: c.frames, closedAt: c.closedAt, away }));
  await until(() => /^listening /.test(lastState() || ''), 2000);
  check('…and the phone does not say it is watched', /^listening /.test(lastState() || ''), String(lastState()));

  t = Date.now();
  c = await wsConnect({ token: await letIn() });
  c.send(hello);
  handoff = await until(() => outLines.find((l) => l.at >= t && l.line.startsWith('HANDOFF ')), 4000);
  c.send({ t: 'ping', n: 2 });
  await command('stop', 'OK stopped');
  await within(c.closed, 5000);
  check('switched off while a browser is being let in: told so, and never watches',
    !!handoff && c.texts.some((x) => JSON.parse(x).state === 'off') && c.frames === 0 && !evSince(t, (e) => e.hello),
    JSON.stringify({ handoff: !!handoff, texts: c.texts, frames: c.frames }));
  await command('slowhandoff 0', 'OK slowhandoff');
  await command('restart', 'OK restarted');

  // A sign-out on the phone right behind a right code: the store's lock held
  // by a process of its own (as the Settings page's process holds it), so the
  // pairing waits inside its code's check and the sign-out waits behind it.
  // Let go, the pairing's check runs first and the sign-out next; the browser
  // let in within the check is signed out with the rest, where one let in a
  // step later would outlive it.
  await command('lockafter 1000', 'OK lockafter');
  const locker = spawn(JAVA, [path.join(HERE, 'linux-browser/StoreLock.java'), state], { stdio: ['pipe', 'pipe', 'pipe'] });
  cleanups.push(() => locker.kill());
  const lockerSaid = [];
  locker.stdout.on('data', (d) => lockerSaid.push(...String(d).split(/\r?\n/).filter(Boolean).map((line) => ({ at: Date.now(), line }))));
  const lockerDo = async (cmd, answer) => {
    const t0 = Date.now();
    locker.stdin.write(`${cmd}\n`);
    return until(() => lockerSaid.find((l) => l.at >= t0 && l.line === answer), 8000, 10);
  };
  const ROUNDS = 8;
  const outcomes = [];
  for (let i = 0; i < ROUNDS; i++) {
    const code = await askCode();
    const held = await pairHeld(code);
    if (!held || !(await lockerDo('lock', 'LOCKED'))) {
      outcomes.push('no lock');
      continue;
    }
    const t0 = Date.now();
    const answer = held.go();
    await sleep(150); // the pairing's code check waits on the lock
    say('signout');
    await sleep(150); // the sign-out waits behind the pairing
    await lockerDo('unlock', 'UNLOCKED');
    const r = await answer;
    await until(() => outLines.find((l) => l.at >= t0 && l.line.startsWith('CODE ')), 4000, 10);
    // Answered or not (the sign-out also closes every connection not yet a
    // stream), what counts is what the phone still lets in afterwards.
    let o = r.status === 200 ? 'answered with a token' : `no answer (${r.status})`;
    if (r.status === 200) {
      const h = await request('GET', '/hello', { headers: { Authorization: `Bearer ${said(r).token}` } });
      if (said(h).name !== undefined) o += ' that still works';
    }
    const left = Number(((await command('tokens', 'TOKENS ')) || 'TOKENS -1').slice(7));
    if (left !== 0) o += `, the phone still letting ${left} in`;
    outcomes.push(o);
  }
  check(`a sign-out right behind a right code, ${ROUNDS} times: the browser it let in is signed out with the rest`,
    outcomes.every((o) => !/still/.test(o)), outcomes.join('; '));
  locker.stdin.end();
  await command('lockafter 5', 'OK lockafter');
  await command('restart', 'OK restarted');
}

// ── the page, in a real browser ─────────────────────────────────────────

function findBrowser() {
  const given = argOf('--browser');
  if (given) return given;
  const candidates = process.platform === 'win32'
    ? ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe']
    : process.platform === 'darwin'
      ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge']
      : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/microsoft-edge'];
  return candidates.find((c) => fs.existsSync(c));
}

/**
 * A headless browser of its own (its own profile, its own debugging port),
 * driven over the DevTools protocol: what the page does, read and pressed.
 */
async function launchBrowser(extra = []) {
  const exe = findBrowser();
  if (!exe) return null;
  const cdpPort = await freePort();
  const profile = path.join(tmp, `profile-${cdpPort}`);
  const proc = spawn(exe, [...extra,
    '--headless=new', `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${profile}`, '--no-first-run',
    '--no-default-browser-check', '--disable-extensions', '--window-size=1600,900',
    // A microphone that plays a tone, and the browser's question about it answered yes.
    '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', 'about:blank',
  ], { stdio: 'ignore' });
  const kill = () => {
    // Its helpers outlive the main process on Windows unless told to go, and
    // not all of them name the profile: the whole tree first, then anything
    // naming the profile, then a moment for them to let go of its files.
    if (process.platform === 'win32') {
      spawnSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' });
      spawnSync('powershell', ['-NoProfile', '-Command',
        `$p = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*profile-${cdpPort}*' -and $_.Name -match 'msedge|chrome' }; `
        + `$p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; `
        + `$p | ForEach-Object { Wait-Process -Id $_.ProcessId -Timeout 10 -ErrorAction SilentlyContinue }`]);
    } else {
      proc.kill();
    }
  };
  cleanups.push(kill);
  let target = null;
  await until(async () => {
    try {
      const list = await (await fetch(`http://127.0.0.1:${cdpPort}/json`)).json();
      target = list.find((t) => t.type === 'page');
      return target;
    } catch {
      return null;
    }
  }, 15000, 200);
  if (!target) return null;
  return attach(target.webSocketDebuggerUrl, cdpPort, kill);
}

/** The DevTools protocol on one tab: commands, JavaScript, input, pictures, and its errors. */
async function attach(wsUrl, cdpPort, kill) {
  const sock = new WebSocket(wsUrl);
  await new Promise((r) => (sock.onopen = r));
  let id = 0;
  const pending = new Map();
  const pageErrors = [];
  sock.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m);
      pending.delete(m.id);
    } else if (m.method === 'Runtime.exceptionThrown') {
      pageErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      pageErrors.push(m.params.args.map((a) => a.value ?? a.description).join(' '));
    } else if (m.method === 'Page.javascriptDialogOpening') {
      // The page asks before it is left while Linux is on screen. Answered as
      // the test says (dialogAnswer), Leave unless told otherwise, and kept.
      dialogs.push({ at: Date.now(), type: m.params.type, url: m.params.url });
      const i = ++id;
      sock.send(JSON.stringify({ id: i, method: 'Page.handleJavaScriptDialog', params: { accept: dialogAnswer.leave } }));
    }
  };
  /** The browser's own questions, as they came: {at, type, url}. */
  const dialogs = [];
  const dialogAnswer = { leave: true };
  const cdp = (method, params = {}) => new Promise((r) => {
    const i = ++id;
    pending.set(i, r);
    sock.send(JSON.stringify({ id: i, method, params }));
  });
  const js = async (expr) => {
    const m = await cdp('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (m.result && m.result.exceptionDetails) return { error: m.result.exceptionDetails.exception?.description || m.result.exceptionDetails.text };
    return m.result ? m.result.result.value : undefined;
  };
  const shot = async (name) => {
    if (!SHOTS) return;
    fs.mkdirSync(SHOTS, { recursive: true });
    const m = await cdp('Page.captureScreenshot', { format: 'png' });
    if (m.result) fs.writeFileSync(path.join(SHOTS, `${name}.png`), Buffer.from(m.result.data, 'base64'));
  };
  const click = async (x, y, button = 'left') => {
    const buttons = { left: 1, right: 2, middle: 4 }[button];
    await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
    await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, buttons, clickCount: 1 });
    await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, buttons: 0, clickCount: 1 });
  };
  const press = async (key, code, vk, text, modifiers = 0) => {
    await cdp('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', key, code, windowsVirtualKeyCode: vk, text, modifiers });
    await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: vk, modifiers });
  };
  /** Another tab in the same browser, the same storage: a second window on the same desktop. */
  const newTab = async (url) => {
    const r = await fetch(`http://127.0.0.1:${cdpPort}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
    const t = await r.json();
    return attach(t.webSocketDebuggerUrl, cdpPort, () => {});
  };
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  /** The browser asked to quit, so that it closes its profile itself before anything is killed. */
  const quit = async () => {
    await Promise.race([cdp('Browser.close'), sleep(3000)]);
    await sleep(1500);
  };
  return { cdp, js, shot, click, press, pageErrors, dialogs, dialogAnswer, newTab, close: () => sock.close(), quit, kill };
}

/** Advanced, then Continue, on the browser's certificate warning, as a person does. */
async function clickThrough(b, base) {
  await sleep(1200);
  await b.js(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
  await sleep(300);
  await b.js(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
  return until(() => b.js(`location.href.startsWith('${base}')`), 8000);
}

const B = await launchBrowser();
check('a Chromium browser starts, with a debugging port', !!B);
if (!B) process.exit(1);
const { cdp, js, shot, click, press, pageErrors } = B;
const visible = (id) => js(`!document.getElementById('${id}').hidden && !document.body.classList.contains('up')`);
const isUp = () => js(`document.body.classList.contains('up')`);
await cdp('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'], origin: BASE });
// A 1280x720 window: the stand-in's full-size stream is exactly the desktop
// it asks for, as the phone's is, and its small one two thirds of it.
const VW = 1280;
const VH = 720;
await cdp('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: 1, mobile: false });
const rate = (mode, w = VW, h = VH) => {
  const m = { low: [2 / 3, 0.05, 1.5e6, 8e6], medium: [1, 0.08, 4e6, 25e6], high: [1, 0.2, 10e6, 60e6] }[mode];
  return Math.round(Math.min(m[3], Math.max(m[2], w * h * m[0] * m[0] * 60 * m[1])));
};
const seg = (m) => `document.querySelector('#modes button[data-mode=${m}]')`;

console.log('the first look');
{
  await cdp('Page.navigate', { url: `http://${IP}:${PORT}/` });
  // Typed as the card shows it, the browser asks over plain http: the page
  // looks for the secure address, finds this browser does not take its
  // certificate yet, and offers it first.
  await until(() => visible('choose'), 8000);
  check('typed without https: the plain page offers the secure way in first', await visible('choose')
    && (await js(`document.getElementById('choose-secure').textContent`)) === 'Continue securely'
    && (await js(`document.activeElement && document.activeElement.id`)) === 'choose-secure', await js('location.href'));
  check('…and the way on when the browser refuses it, with what that costs',
    (await js(`document.getElementById('choose-plain').textContent`)) === 'Continue without encryption'
    && /others on this Wi-Fi could see the desktop/.test(await js(`document.getElementById('choose-fine').textContent`)));
  await shot('00-choose');
  await js(`document.getElementById('choose-secure').click()`);
  await sleep(1500);
  const where = await js('location.href');
  check('Continue securely: to https, which stops at the browser\'s warning', /^chrome-error:/.test(where) || where.startsWith('https://'), where);
  const warned = await js(`document.body ? document.body.innerText : ''`);
  check("the browser's own warning comes first: the phone signs its own connection", /not private|isn.t private/i.test(warned), warned.slice(0, 80));
  await shot('01-browser-warning');
  await js(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
  await sleep(300);
  await js(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
  await until(() => js(`location.href.startsWith('${BASE}')`), 8000);
  check('Advanced, then Continue: the page loads', await js(`location.href`) === `${BASE}/`);
  check('…as a secure context, with WebCodecs', await js(`window.isSecureContext && typeof VideoDecoder === 'function'`));
  await until(() => visible('pair'), 5000);
  check('a browser seen for the first time is asked for the code', await visible('pair'));
  check('…and told where the phone shows it', (await js(`document.getElementById('pair-lede').textContent`)) === 'Find the code on your phone: LinuxOnDroid › Settings › Open in a browser.');
  check("…without the phone's name, which is not told before the code", (await js('document.title')) === 'Linux');
  await shot('02-pair');
}

console.log('the code');
{
  const code = await askCode();
  await js(`document.getElementById('code').focus()`);
  await cdp('Input.insertText', { text: '12345' });
  await sleep(200);
  check('five digits drawn in five cells, the sixth waiting', await js(`[...document.querySelectorAll('.cells i.filled')].length === 5 && document.querySelectorAll('.cells i.next').length === 1`));
  const wrongDigit = code[5] === '9' ? '8' : '9';
  const t = Date.now();
  await cdp('Input.insertText', { text: wrongDigit });
  await until(() => js(`document.getElementById('pair-error').textContent.length > 0`), 5000);
  check('a wrong code shakes and says so, and the cells empty for another try',
    (await js(`document.getElementById('pair-error').textContent`)).startsWith('That code did not match') && (await js(`document.getElementById('code').value`)) === '');
  await shot('03-pair-wrong');
  await cdp('Input.insertText', { text: code });
  const up = await until(isUp, 10000);
  check('the right code: let in, and the desktop is on screen', !!up, await js(`document.getElementById('wait-title').textContent + ' / ' + document.getElementById('ended-title').textContent`));
  const hello = evSince(t, (e) => e.hello);
  check("the hello: start, the window's size, and Medium at a Wi-Fi bitrate; the token went with the request, not in it",
    hello && !('token' in hello.hello) && hello.hello.start === true && hello.hello.mode === 'medium' && hello.hello.scale === 1
    && hello.hello.w === VW && hello.hello.h === VH && hello.hello.bitrate === rate('medium'),
    JSON.stringify(hello && hello.hello));
  check('the token is kept for next time', !!(await js(`localStorage.getItem('lod.token')`)));
  await until(async () => (await js('document.title')) !== 'Linux', 3000);
  check('let in, the tab says which phone', (await js('document.title')) === 'Linux · Harness Phone');
}

console.log('the picture');
{
  // The bar is down for its first 3.2 s: looked at first, before anything slower.
  check('the bar comes down for a moment when the picture first shows', await js(`document.body.classList.contains('bar-on')`));
  await sleep(1500);
  const px = (await js('lodPixels()')) || {};
  check('decoded and drawn: the canvas is the desktop\'s size and the picture is on it', px.w === VW && px.h === VH && px.lit > px.n * 0.5, JSON.stringify(px));
  check("…decoded and drawn in a worker, off the page's own thread", (await js('lodVideo()')).decode === 'worker', JSON.stringify(await js('lodVideo()')));
  check('…one pixel for one, not smoothed', !(await js(`document.getElementById('screen').classList.contains('smooth')`)));
  await shot('04-desktop-bar');
  // Three seconds, not one: a computer busy for a second says nothing of the page.
  const seconds = [];
  for (let i = 0; i < 3; i++) {
    await sleep(1100);
    const s = await js(`document.getElementById('stats').textContent`);
    seconds.push(/(\d+) fps/.test(s) ? Number(s.match(/(\d+) fps/)[1]) : 0);
  }
  const fps = [...seconds].sort((a, b) => a - b)[1];
  check('frames are drawn as they come, about sixty a second (the middle of three seconds)', fps >= 45, seconds.join(', '));
  const acked = events.filter((e) => e.acks !== undefined).at(-1);
  check('every frame is acknowledged as it arrives (the phone paces to that)', acked && acked.acks >= acked.frames - 10, JSON.stringify(acked));
  check("the pointer is this computer's, in Xvnc's shape", (await js(`getComputedStyle(document.getElementById('screen')).cursor`)).startsWith('url("data:image/png'));
  await sleep(500); // past its 3.2 s, the three seconds above being most of them
  check('…and goes, leaving the desktop the whole window', !(await js(`document.body.classList.contains('bar-on')`)));
}

console.log('Low, Medium and High');
{
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 1 });
  await until(() => js(`document.body.classList.contains('bar-on')`), 2000);
  check('the pointer at the top edge brings the bar down', await js(`document.body.classList.contains('bar-on')`));
  check("…with the phone's whole name on it", await js(`(() => { const n = document.getElementById('bar-name'); return n.scrollWidth <= n.clientWidth && n.textContent === 'Harness Phone'; })()`));
  await sleep(400); // the bar's own slide down
  let t = Date.now();
  await js(`${seg('high')}.click()`);
  let q = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'quality'), 3000);
  check('High: the whole desktop at the high bitrate, sent at once, no reconnect',
    q && q.msg.mode === 'high' && q.msg.scale === 1 && q.msg.bitrate === rate('high') && !evSince(t, (e) => e.hello), JSON.stringify(q && q.msg));
  check('…and the switch shows it, its highlight on High', await js(`document.getElementById('modes').dataset.mode === 'high' && ${seg('high')}.getAttribute('aria-checked') === 'true' && ${seg('medium')}.getAttribute('aria-checked') === 'false'`));
  check('…and says what it means', (await js(`document.getElementById('toast').textContent`)).startsWith('High · Sharpest.'));
  await shot('05-high');
  t = Date.now();
  await js(`${seg('low')}.click()`);
  q = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'quality'), 3000);
  check('Low: two thirds the size, a low bitrate', q && q.msg.mode === 'low' && Math.abs(q.msg.scale - 2 / 3) < 1e-9
    && q.msg.bitrate === rate('low'), JSON.stringify(q && q.msg));
  const ordered = rate('low') < rate('medium') && rate('medium') < rate('high');
  check('the three bitrates climb, Low to High, at every common size', ordered
    && [[1280, 720], [1920, 1080], [2560, 1440], [3840, 2160]].every(([w, h]) => rate('low', w, h) < rate('medium', w, h) && rate('medium', w, h) < rate('high', w, h)));
  await until(() => js(`document.getElementById('screen').classList.contains('smooth')`), 3000);
  check('…the smaller picture is scaled back up smoothly, not in blocks', await js(`document.getElementById('screen').classList.contains('smooth')`));
  const before = await js(`document.getElementById('stats').textContent`);
  await sleep(1600);
  const lowFps = Number(((await js(`document.getElementById('stats').textContent`)).match(/(\d+) fps/) || [0, 0])[1]);
  check('…and the new encoder\'s frames decode straight away', lowFps >= 45, `${before} → ${lowFps}`);
  t = Date.now();
  await js(`${seg('medium')}.click()`);
  q = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'quality'), 3000);
  check('Medium again: the whole desktop', q && q.msg.scale === 1 && q.msg.mode === 'medium');
  await until(() => js(`!document.getElementById('screen').classList.contains('smooth')`), 3000);
  check('…drawn one pixel for one again', await js(`!document.getElementById('screen').classList.contains('smooth')`));
  check('the choice is remembered for next time', (await js(`localStorage.getItem('lod.mode')`)) === 'medium');
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 600 });
  await sleep(500);
}

console.log('the sound follows the quality');
{
  // An https page has WebCodecs' AudioDecoder: Medium and Low are Opus.
  await click(640, 400); // a person's action, which lets sound start if the code did not
  const ready = await until(async () => {
    const v = await js('lodSound()');
    return v && v.state === 'running' && v.runs > 5 && v.format === 'Opus 96 kb/s stereo' ? v : null;
  }, 6000);
  check("an https page decodes Opus, says so, and plays Medium's Opus 96 kb/s stereo through WebCodecs",
    !!ready && ready.opus === true && ready.decoder === 'configured', JSON.stringify(await js('lodSound()')));
  for (const [m, want, how] of [['high', '48 kHz stereo', 'lossless PCM'], ['low', 'Opus 32 kb/s mono', 'Opus, mono'],
    ['medium', 'Opus 96 kb/s stereo', 'Opus, stereo']]) {
    await js(`${seg(m)}.click()`);
    const got = await until(async () => {
      const v = await js('lodSound()');
      return v.format === want ? v : null;
    }, 4000);
    const r0 = got ? got.runs : 0;
    await sleep(1500);
    const r1 = (await js('lodSound()')).runs;
    check(`${m[0].toUpperCase() + m.slice(1)} on the bar: the sound changes with the picture, to ${how} (${want}), and plays`,
      !!got && r1 > r0 + 10, `${JSON.stringify(got)} runs ${r0} → ${r1}`);
  }
  check("the bar's numbers say the sound's form", /Sound Opus 96 kb\/s stereo, \d+ kb\/s\./.test(await js(`document.getElementById('stats').title`)),
    await js(`document.getElementById('stats').title`));
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 600 });
  await sleep(500);
}

console.log('what the phone reads, and a hidden tab');
{
  const hello = events.filter((e) => e.hello).at(-1);
  check('the page says it acknowledges frames with the time each arrived', hello && hello.hello.acks === true && hello.hello.ackAt === true,
    JSON.stringify(hello && hello.hello));
  const a = events.filter((e) => e.timedAcks !== undefined).at(-1);
  check('…and each acknowledgement carries that time, by this computer\'s clock',
    a && a.timedAcks > 0 && a.timedAcks >= a.acks - 2 && Math.abs(a.lastArrived - a.at) < 2000, JSON.stringify(a));
  // Twice, apart: the page's own ping comes every two seconds, and could land
  // by chance within a moment of one word, but hardly of both.
  const answered = [];
  let t = Date.now();
  for (let i = 0; i < 2; i++) {
    t = Date.now();
    await command('hb', 'OK hb');
    const pinged = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'ping'), 1500, 5);
    answered.push(pinged ? pinged.at - t : -1);
    await sleep(700);
  }
  check("the phone's word while a hidden tab has no picture is answered at once, not at the page's next timer",
    answered.every((ms) => ms >= 0 && ms < 150), `${answered.join(', ')} ms`);

  // Another tab in front: this one is hidden, as it is behind a tab, or in a
  // minimised window.
  const v0 = await js('lodVideo()');
  const s0 = await js('lodSound()');
  t = Date.now();
  const other = await cdp('Target.createTarget', { url: 'about:blank' });
  await until(() => js('document.visibilityState === "hidden"'), 3000);
  const off = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'picture' && e.msg.on === false), 3000);
  check('a tab out of sight tells the phone it wants no picture', (await js('document.visibilityState')) === 'hidden' && !!off,
    `${await js('document.visibilityState')} ${JSON.stringify(off)}`);
  // Frames already on their way when the word went land first.
  await sleep(500);
  const vA = await js('lodVideo()');
  await sleep(2500);
  const v1 = await js('lodVideo()');
  const s1 = await js('lodSound()');
  const quietSince = events.filter((e) => e.sentWhileOff !== undefined).at(-1);
  check('…then no frame comes, and none is drawn', v1.drawnTotal - vA.drawnTotal <= 1 && quietSince && quietSince.sentWhileOff === 0,
    `${v0.drawnTotal} → ${vA.drawnTotal} → ${v1.drawnTotal}, ${JSON.stringify(quietSince)}`);
  // A headless browser slows a hidden tab's page, sound or not (a real one
  // does not, for a tab playing sound): that it goes on is what counts here.
  check('…while the sound goes on', s1.runs > s0.runs + 15, `runs ${s0.runs} → ${s1.runs}`);
  t = Date.now();
  await cdp('Target.closeTarget', { targetId: other.result.targetId });
  // In front again for good: a headless browser can leave the tab's next page
  // (a navigation to another origin) believing it is still behind.
  await cdp('Page.bringToFront');
  const firstAt = await until(async () => ((await js('lodVideo()')).drawnTotal > v1.drawnTotal ? Date.now() : null), 4000, 5);
  const on = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'picture' && e.msg.on === true), 3000);
  const back = await until(async () => {
    const v = await js('lodVideo()');
    return v.drawnTotal > v1.drawnTotal + 10 ? v : null;
  }, 4000);
  check('the tab back: the phone is told, and the picture comes again, from a key frame',
    !!on && !!back && back.keysIn > v1.keysIn, JSON.stringify({ on: !!on, v1, back }));
  check('…soon: under half a second from the tab in front again to the first new frame drawn', !!firstAt && firstAt - t < 500,
    firstAt ? `${firstAt - t} ms` : 'no frame');

  // The sound in hand: grown to bridge a hidden tab's bursts, back down with
  // the tab, and as small as a steady link allows. The stand-in's tap hands
  // over 20 ms at a time, as PulseAudio does for many apps; at 10 ms a time,
  // as for an app that asks for less, PCM (High) comes 10 ms a message.
  await sleep(3000);
  const s2 = await js('lodSound()');
  // Down again if it grew; and a tab the browser did not slow (29 ms hidden,
  // 30 back, 2026-11-21) has nothing to come down from.
  check(`the sound in hand comes back down when the tab is back: ${s2.cushionMs} ms (${s1.cushionMs} ms while it was hidden)`,
    s2.cushionMs < 60 && (s1.cushionMs < 60 || s2.cushionMs < s1.cushionMs), JSON.stringify({ s1, s2 }));
  await command('tapchunk 10', 'OK tapchunk');
  await js(`${seg('high')}.click()`);
  await sleep(5000);
  const s3 = await js('lodSound()');
  // What the gaps ask for (1.2 times the longest, and 5 ms), not a floor of
  // 40 ms: how small that is depends on this computer's own timing.
  check(`…and a tap that hands over 10 ms at a time: ${s3.cushionMs} ms in hand at High's PCM, as the gaps ask (the longest ${s3.gapMs} ms; `
    + `before, never under 40), ${s3.underruns - s2.underruns} run dry`,
  s3.format === '48 kHz stereo' && s3.cushionMs <= Math.max(15, 1.2 * s3.gapMs + 5) + 2 && (s3.gapMs >= 25 || s3.cushionMs < 40)
    && s3.underruns <= s2.underruns + 2, JSON.stringify({ s2, s3 }));
  await command('tapchunk 20', 'OK tapchunk');
  await js(`${seg('medium')}.click()`);
  await sleep(1000);
}

console.log('the pointer and the keys');
{
  let t = Date.now();
  await click(400, 300);
  let p = await until(() => evSince(t, (e) => e.pointer && e.pointer[0] === 1), 2000);
  check('a click goes to the desktop where it was made', p && p.pointer[1] === 400 && p.pointer[2] === 300, JSON.stringify(p));
  check('…and the button comes back up', !!(await until(() => evSince(t, (e) => e.pointer && e.pointer[0] === 0 && e.at >= p.at), 2000)));
  t = Date.now();
  await click(500, 320, 'right');
  check('right is button 3 (RFB 4)', !!(await until(() => evSince(t, (e) => e.pointer && e.pointer[0] === 4), 2000)));
  t = Date.now();
  await cdp('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 500, y: 320, deltaX: 0, deltaY: 120 });
  check('the wheel scrolls down (button 5)', !!(await until(() => evSince(t, (e) => e.pointer && e.pointer[0] === 16), 2000)));
  t = Date.now();
  await press('a', 'KeyA', 65, 'a');
  const ka = await until(() => evSince(t, (e) => e.key && e.key[1] === 0x61 && e.key[0] === 1), 2000);
  check('a key types its character', !!ka && !!(await until(() => evSince(t, (e) => e.key && e.key[1] === 0x61 && e.key[0] === 0), 2000)));
  t = Date.now();
  await press('F5', 'F5', 116);
  check('F5 is Linux\'s, not the browser\'s reload', !!(await until(() => evSince(t, (e) => e.key && e.key[1] === 0xffc2), 2000)) && (await js('document.body.classList.contains("up")')));
  t = Date.now();
  await press('c', 'KeyC', 67, '', 2);
  check('Ctrl+C goes as Ctrl and c', !!(await until(() => evSince(t, (e) => e.key && e.key[1] === 0x63), 2000)));
}

// Every microphone the browser gives the page, to see none is left
// recording: getUserMedia wrapped, the page's own call unchanged, and an
// answer held back on request (__micDelay), as a person reading the
// browser's question holds it back.
const watchMics = () => js(`(() => {
  if (window.__mics) return;
  const md = navigator.mediaDevices;
  const real = md.getUserMedia.bind(md);
  window.__mics = [];
  window.__micDelay = 0;
  md.getUserMedia = async (c) => {
    const s = await real(c);
    window.__mics.push(s);
    if (window.__micDelay) await new Promise((r) => setTimeout(r, window.__micDelay));
    return s;
  };
})()`);
/**
 * Each check counts from nothing: the page's microphone off (an earlier one
 * may have left it on), whatever was left recording stopped. Wrapped again
 * first, for a page loaded again since.
 */
const freshMics = async (delay = 0) => {
  await watchMics();
  if ((await js('lodMic()')).on) {
    await js(`document.getElementById('mic').click()`);
    await sleep(200);
  }
  await js(`(() => { for (const s of window.__mics) for (const k of s.getTracks()) k.stop(); window.__mics = []; window.__micDelay = ${delay}; })()`);
};
const recordingMics = () => js(`window.__mics.filter((s) => s.getTracks().some((k) => k.readyState === 'live')).length`);
const micsSaid = async () => JSON.stringify({ page: await js('lodMic()'), given: await js('window.__mics.length'), recording: await recordingMics() });

console.log('touch, the screen\'s keyboard, and the microphone');
{
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  const tp = (x, y, id = 0) => ({ x, y, id, radiusX: 4, radiusY: 4, force: 1 });
  const touchAt = async (pts, type) => cdp('Input.dispatchTouchEvent', { type, touchPoints: pts });
  const tapAt = async (x, y) => {
    await touchAt([tp(x, y)], 'touchStart');
    await touchAt([], 'touchEnd');
  };
  const pointersSince = (t) => events.filter((e) => e.at >= t && e.pointer);
  // A press of these buttons, and all of them up again after it (the move to
  // the finger before the press has them up too: not the lift).
  const pressedAndUp = (t, mask) => {
    const ps = pointersSince(t);
    const i = ps.findIndex((e) => e.pointer[0] === mask);
    return i >= 0 && ps.slice(i + 1).some((e) => e.pointer[0] === 0);
  };
  // Touch, the finger the pointer: whichever this screen chose at load, Touch here.
  if (await js(`document.body.classList.contains('pad')`)) await js(`document.getElementById('tmode').click()`);
  let t = Date.now();
  await tapAt(400, 300);
  await until(() => pressedAndUp(t, 1), 2000);
  let ps = pointersSince(t);
  const touchMode = await js(`document.body.classList.contains('touch') && !document.body.classList.contains('pad')`);
  check('a finger on the picture: the touch buttons appear on the bar, and Touch is the way',
    touchMode && (await js(`getComputedStyle(document.getElementById('tmode')).display !== 'none'`)), `${touchMode}`);
  check('Touch: a tap clicks where the finger lands', ps.some((e) => e.pointer[0] === 1 && e.pointer[1] === 400 && e.pointer[2] === 300)
    && ps.at(-1).pointer[0] === 0, JSON.stringify(ps.map((e) => e.pointer)));
  t = Date.now();
  await touchAt([tp(500, 320)], 'touchStart');
  await sleep(650);
  const ring = await js(`!document.getElementById('armed').hidden`);
  await touchAt([], 'touchEnd');
  await until(() => pressedAndUp(t, 4), 2000);
  ps = pointersSince(t);
  check('…a long press shows its ring, and is a right-click on lifting', ring && ps.some((e) => e.pointer[0] === 4 && e.pointer[1] === 500)
    && ps.at(-1).pointer[0] === 0, JSON.stringify(ps.map((e) => e.pointer)));
  t = Date.now();
  await touchAt([tp(600, 300)], 'touchStart');
  for (let k = 1; k <= 10; k++) await touchAt([tp(600, 300 + k * 20)], 'touchMove');
  await touchAt([], 'touchEnd');
  await until(() => pointersSince(t).some((e) => e.pointer[0] === 8), 2000);
  ps = pointersSince(t);
  check('…a drag scrolls what is under the finger, the way the finger goes (button 4: up)',
    ps.some((e) => e.pointer[0] === 8) && !ps.some((e) => e.pointer[0] === 1 || e.pointer[0] === 16), JSON.stringify(ps.map((e) => e.pointer[0])));
  // A pinch about the window's middle, two times out.
  await touchAt([tp(540, 360, 1), tp(740, 360, 2)], 'touchStart');
  await sleep(80);
  for (let k = 1; k <= 10; k++) {
    await touchAt([tp(540 - k * 10, 360, 1), tp(740 + k * 10, 360, 2)], 'touchMove');
    await sleep(16);
  }
  await touchAt([], 'touchEnd');
  const z = await js(`document.getElementById('screen').style.transform`);
  const scale = Number((/scale\(([\d.]+)\)/.exec(z) || [])[1]);
  check('…two fingers apart zoom the picture, about where they are, and Fit appears', scale > 1.8 && scale < 2.2
    && (await js(`!document.getElementById('fit').hidden`)), z);
  t = Date.now();
  await tapAt(640 + 200, 360);
  await until(() => pointersSince(t).some((e) => e.pointer[0] === 1), 2000);
  const zt = pointersSince(t).find((e) => e.pointer[0] === 1);
  // At about twice the size, 200 px of window right of the middle is about 100 desktop pixels.
  check('…a tap on the zoomed picture lands on the desktop point under the finger', zt && Math.abs(zt.pointer[1] - (640 + 200 / scale)) <= 2
    && Math.abs(zt.pointer[2] - 360) <= 2, `${JSON.stringify(zt && zt.pointer)} at ${scale}`);
  await js(`document.getElementById('fit').click()`);
  check('…and Fit shows the whole desktop again', (await js(`document.getElementById('screen').style.transform`)) === ''
    && (await js(`document.getElementById('fit').hidden`)));

  // Trackpad: the screen as a laptop's trackpad.
  await js(`document.getElementById('tmode').click()`);
  check('the Touch/Trackpad button: Trackpad, said', (await js(`document.body.classList.contains('pad')`))
    && /^Trackpad\. A finger moves the pointer/.test(await js(`document.getElementById('toast').textContent`)),
    await js(`document.getElementById('toast').textContent`));
  check("…and the trackpad's pointer is drawn, in Linux's shape", (await js(`!document.getElementById('tcur').hidden`))
    && (await js(`document.getElementById('tcur-img').src.startsWith('data:image/png')`)));
  t = Date.now();
  await touchAt([tp(300, 500)], 'touchStart');
  for (let k = 1; k <= 12; k++) {
    await touchAt([tp(300 + k * 10, 500)], 'touchMove');
    await sleep(16);
  }
  await touchAt([], 'touchEnd');
  await sleep(200);
  ps = pointersSince(t);
  const moved = ps.filter((e) => e.pointer[0] === 0);
  check('Trackpad: a finger moving right moves the pointer right, from where it was, with no button',
    moved.length > 3 && moved.at(-1).pointer[1] > moved[0].pointer[1] && !ps.some((e) => e.pointer[0] !== 0),
    JSON.stringify(ps.slice(0, 3).map((e) => e.pointer)) + ' … ' + JSON.stringify(ps.slice(-2).map((e) => e.pointer)));
  const there = moved.at(-1) ? moved.at(-1).pointer : [0, 0, 0];
  t = Date.now();
  await tapAt(100, 100);
  await until(() => pointersSince(t).some((e) => e.pointer[0] === 1), 2000);
  const pc = pointersSince(t).find((e) => e.pointer[0] === 1);
  check('…a tap anywhere clicks where the pointer is, not where the finger is',
    pc && Math.abs(pc.pointer[1] - there[1]) <= 1 && Math.abs(pc.pointer[2] - there[2]) <= 1, `${JSON.stringify(pc && pc.pointer)} vs ${JSON.stringify(there)}`);
  await sleep(400); // past a double tap
  t = Date.now();
  await touchAt([tp(600, 400, 1), tp(700, 400, 2)], 'touchStart');
  await sleep(40);
  await touchAt([], 'touchEnd');
  await until(() => pointersSince(t).some((e) => e.pointer[0] === 4), 2000);
  check('…two fingers tapped together are a right-click', pointersSince(t).some((e) => e.pointer[0] === 4),
    JSON.stringify(pointersSince(t).map((e) => e.pointer)));
  t = Date.now();
  await touchAt([tp(600, 300, 1), tp(700, 300, 2)], 'touchStart');
  await sleep(80);
  for (let k = 1; k <= 10; k++) {
    await touchAt([tp(600, 300 - k * 15, 1), tp(700, 300 - k * 15, 2)], 'touchMove');
    await sleep(16);
  }
  await touchAt([], 'touchEnd');
  await until(() => pointersSince(t).some((e) => e.pointer[0] === 16), 2000);
  check('…and two fingers up scroll down (button 5), at the pointer', pointersSince(t).some((e) => e.pointer[0] === 16
    && Math.abs(e.pointer[1] - there[1]) <= 1), JSON.stringify(pointersSince(t).slice(0, 4).map((e) => e.pointer)));
  await js(`document.getElementById('tmode').click()`);

  // The screen's keyboard: the button, a field out of sight, the strip.
  await js(`document.getElementById('kbd').click()`);
  check("the keyboard button: the field the screen's keyboard types into has the focus, and the strip of keys shows",
    (await js(`document.activeElement && document.activeElement.id`)) === 'kbd-in' && (await js(`document.body.classList.contains('kbd-on')`))
    && (await js(`getComputedStyle(document.getElementById('keys')).display`)) === 'flex');
  const keysSince = (t0) => events.filter((e) => e.at > t0 && e.key).map((e) => e.key);
  // Each step's own keys only: the last step's, all come, are behind it.
  const settled = async () => {
    await sleep(250);
    return Date.now();
  };
  t = await settled();
  await cdp('Input.insertText', { text: 'hi' });
  await until(() => keysSince(t).length >= 4, 2000);
  check('…typed text goes to Linux as its keys', JSON.stringify(keysSince(t)) === JSON.stringify([[1, 0x68], [0, 0x68], [1, 0x69], [0, 0x69]]),
    JSON.stringify(keysSince(t)));
  t = await settled();
  await js(`(() => { const f = document.getElementById('kbd-in'); f.value = f.value.slice(0, -1);
    f.dispatchEvent(new InputEvent('input', { inputType: 'deleteContentBackward' })); return true; })()`);
  await until(() => keysSince(t).length >= 2, 2000);
  check("…a screen keyboard's delete is a BackSpace", JSON.stringify(keysSince(t)) === JSON.stringify([[1, 0xff08], [0, 0xff08]]),
    JSON.stringify(keysSince(t)));
  t = await settled();
  await js(`document.querySelector('#keys [data-ks="0xffe3"]').click()`);
  const held = await js(`document.querySelector('#keys [data-ks="0xffe3"]').getAttribute('aria-pressed')`);
  await cdp('Input.insertText', { text: 'c' });
  await until(() => keysSince(t).length >= 4, 2000);
  check('…Ctrl on the strip holds for the next key: Ctrl+C', held === 'true'
    && JSON.stringify(keysSince(t)) === JSON.stringify([[1, 0xffe3], [1, 0x63], [0, 0x63], [0, 0xffe3]])
    && (await js(`document.querySelector('#keys [data-ks="0xffe3"]').getAttribute('aria-pressed')`)) === 'false', JSON.stringify(keysSince(t)));
  t = await settled();
  await js(`document.querySelector('#keys [data-ks="0xff1b"]').click()`);
  await until(() => keysSince(t).length >= 2, 2000);
  check('…Esc on the strip is Esc', JSON.stringify(keysSince(t)) === JSON.stringify([[1, 0xff1b], [0, 0xff1b]]), JSON.stringify(keysSince(t)));
  t = await settled();
  await cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await until(() => keysSince(t).length >= 2, 2000);
  check('…and a real keyboard typing into the field is Linux\'s too: Enter is Return, once',
    JSON.stringify(keysSince(t)) === JSON.stringify([[1, 0xff0d], [0, 0xff0d]]), JSON.stringify(keysSince(t)));
  await js(`document.getElementById('keys-hide').click()`);
  check('…and the strip\'s last button hides the keyboard', (await js(`document.activeElement && document.activeElement.id`)) !== 'kbd-in'
    && !(await js(`document.body.classList.contains('kbd-on')`)));
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: false });

  // The microphone: this browser's is a tone, and its question is answered yes.
  t = Date.now();
  await js(`document.getElementById('mic').click()`);
  const micOn = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'mic' && e.msg.on === true), 4000);
  await until(async () => (await js('lodMic()')).reached === true, 3000);
  await sleep(2200);
  const m1 = events.filter((e) => e.micMsgs !== undefined).at(-1);
  await sleep(2000);
  const m2 = events.filter((e) => e.micMsgs !== undefined).at(-1);
  const perSecond = m1 && m2 ? ((m2.micMsgs - m1.micMsgs) * 1000) / (m2.at - m1.at) : 0;
  check('the microphone button: the phone is told, and says the microphone reaches Linux', !!micOn
    && (await js('lodMic()')).reached === true && (await js(`document.getElementById('mic').getAttribute('aria-pressed')`)) === 'true',
    JSON.stringify(await js('lodMic()')));
  check(`…then 10 ms of sound a message, 100 a second (${Math.round(perSecond)}), 48 kHz mono s16: 960 bytes each, and a tone in it`,
    perSecond > 85 && perSecond < 115 && m2.micOdd === 0 && m2.micRms > 300, JSON.stringify({ m1, m2 }));
  await command('mic unavailable', 'OK mic');
  t = Date.now();
  await js(`document.getElementById('mic').click()`);
  await js(`document.getElementById('mic').click()`);
  const warned = await until(async () => (await js(`document.getElementById('mic').classList.contains('warn')`)) || null, 3000);
  check('…a phone whose Linux cannot hear it: the button turns amber, and the page says what to do',
    !!warned && /^Linux cannot hear the microphone yet\. Restart Linux on the phone/.test(await js(`document.getElementById('toast').textContent`)),
    await js(`document.getElementById('toast').textContent`));
  await command('mic on', 'OK mic');
  t = Date.now();
  await js(`document.getElementById('mic').click()`);
  const offSaid = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'mic' && e.msg.on === false), 3000);
  await sleep(1200);
  const m3 = events.filter((e) => e.micMsgs !== undefined).at(-1);
  await sleep(1500);
  const m4 = events.filter((e) => e.micMsgs !== undefined).at(-1);
  check('…off: the phone is told, and nothing more is sent', !!offSaid && m3 && m4 && m4.micMsgs === m3.micMsgs
    && (await js(`document.getElementById('mic').getAttribute('aria-pressed')`)) === 'false', JSON.stringify({ m3, m4 }));

  // Two presses before the browser answers: one microphone, and off is off.
  // Each press asked for one of its own until 2026-11-21, and off stopped
  // only the last: the first went on recording.
  await watchMics();
  await freshMics(500);
  await js(`(() => { const b = document.getElementById('mic'); b.click(); b.click(); })()`);
  await until(async () => (await js('lodMic()')).on === true, 4000);
  await sleep(900);
  const givenTwice = await js('window.__mics.length');
  await js(`document.getElementById('mic').click()`);
  await sleep(300);
  check('two presses while the browser asks: one microphone, and off leaves nothing recording',
    givenTwice === 1 && (await recordingMics()) === 0 && (await js('lodMic()')).on === false, await micsSaid());

  // On, then Files: the browser stops recording, and Desktop does not start
  // it again by itself. Until 2026-11-21 it recorded on through Files, and
  // the desktop's next stream sent the microphone again unasked.
  await freshMics();
  await js(`document.getElementById('mic').click()`);
  await until(async () => (await js('lodMic()')).reached === true, 4000);
  const recordingBefore = await recordingMics();
  await js(`document.getElementById('files-btn').click()`);
  await until(() => js(`document.body.classList.contains('files-on')`), 4000);
  await sleep(400);
  check('the microphone on, then Files: the browser stops recording', recordingBefore === 1 && (await recordingMics()) === 0
    && (await js('lodMic()')).on === false, `before: ${recordingBefore} ${await micsSaid()}`);
  t = Date.now();
  await js(`document.querySelector('#files .fx-desk').click()`);
  await until(isUp, 10000);
  await sleep(1500);
  const startedAgain = evSince(t, (e) => e.msg && e.msg.t === 'mic' && e.msg.on === true);
  check('…and Desktop again: the microphone stays off until pressed, nothing of it goes to Linux',
    !startedAgain && (await recordingMics()) === 0 && (await js(`document.getElementById('mic').getAttribute('aria-pressed')`)) === 'false',
    `${JSON.stringify(startedAgain)} ${await micsSaid()}`);

  // Pressed, then Files before the browser answers: the answer that comes
  // records nothing.
  await freshMics(800);
  await js(`document.getElementById('mic').click()`);
  await sleep(150);
  await js(`document.getElementById('files-btn').click()`);
  await until(() => js(`document.body.classList.contains('files-on')`), 4000);
  await sleep(1300);
  check('…pressed, then Files before the browser answers: the answer records nothing',
    (await js('window.__mics.length')) === 1 && (await recordingMics()) === 0 && (await js('lodMic()')).on === false, await micsSaid());
  await freshMics();
  await js(`document.querySelector('#files .fx-desk').click()`);
  await until(isUp, 10000);

  // The browser has answered, and the sound path is still being made (the
  // worklet slow to come: 1.5 s here): Files then stops the browser recording
  // at once. Until 2026-11-21 (the audit's second round) a stop could not see
  // that microphone until the path was made.
  await js(`(() => {
    if (!window.__slowWorklet) {
      const real = AudioWorklet.prototype.addModule;
      AudioWorklet.prototype.addModule = function (u, o) {
        const d = window.__workletDelay || 0;
        return d ? new Promise((r) => setTimeout(r, d)).then(() => real.call(this, u, o)) : real.call(this, u, o);
      };
      window.__slowWorklet = true;
    }
    window.__workletDelay = 1500;
  })()`);
  await freshMics();
  await js(`document.getElementById('mic').click()`);
  await until(async () => (await js('window.__mics.length')) === 1, 2000);
  await sleep(200);
  const liveWhileMade = await recordingMics();
  await js(`document.getElementById('files-btn').click()`);
  await until(() => js(`document.body.classList.contains('files-on')`), 4000);
  await sleep(250);
  const afterFiles = await recordingMics();
  await sleep(1800);
  check('…the browser answered, its sound path still being made, then Files: the browser stops recording at once',
    liveWhileMade === 1 && afterFiles === 0 && (await recordingMics()) === 0 && (await js('lodMic()')).on === false,
    `${JSON.stringify({ liveWhileMade, afterFiles })} ${await micsSaid()}`);
  await js('window.__workletDelay = 0');
  await freshMics();
  await js(`document.querySelector('#files .fx-desk').click()`);
  await until(isUp, 10000);
}

console.log('the clipboard');
{
  const text = 'Linux → here: déjà vu, 日本語, 🙂';
  say(`clip ${Buffer.from(text).toString('base64')}`);
  const got = await until(async () => {
    const r = await js(`navigator.clipboard.readText().catch(e => 'ERR ' + e.message)`);
    return r === text ? r : null;
  }, 4000);
  check("Linux's copy lands on this computer's clipboard, every character whole", got === text, String(got));
  const mine = 'here → Linux: ½ € 中文\nsecond line';
  await js(`navigator.clipboard.writeText(${JSON.stringify(mine)})`);
  await js(`document.getElementById('screen').focus()`);
  const t = Date.now();
  await cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Control', code: 'ControlLeft', windowsVirtualKeyCode: 17, modifiers: 2 });
  // A plain key press, no editing command forced: what a person's Ctrl+V does.
  await cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, modifiers: 2 });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, modifiers: 2 });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Control', code: 'ControlLeft', windowsVirtualKeyCode: 17 });
  const clip = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'clip'), 2000);
  check("Ctrl+V: this computer's clipboard goes to Linux first, no permission asked", clip && clip.msg.text === mine, JSON.stringify(clip && clip.msg));
  const v = await until(() => evSince(t, (e) => e.key && e.key[1] === 0x76 && e.key[0] === 1), 2000);
  check('…then the keys that paste it there', v && clip && v.at >= clip.at);
  await js(`document.getElementById('clip').click()`);
  const off = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'clip' && e.msg.text === ''), 2000);
  check('sharing off takes back what this computer gave Linux', !!off);
  check('the switch says it is off', (await js(`document.getElementById('clip').getAttribute('aria-pressed')`)) === 'false');
  say(`clip ${Buffer.from('not for you').toString('base64')}`);
  await sleep(600);
  // Windows keeps CRLF on its clipboard; the words are what matter.
  const kept = String(await js(`navigator.clipboard.readText()`)).replace(/\r\n/g, '\n');
  check("…and Linux's copies stay in Linux", kept === mine, JSON.stringify(kept));
  await js(`document.getElementById('clip').click()`);
  check('…and on again', (await js(`document.getElementById('clip').getAttribute('aria-pressed')`)) === 'true');
}

console.log('the window');
{
  let t = Date.now();
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
  const r = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'resize'), 3000);
  check('the window resized: the desktop is asked to be its size, once it settles', r && r.msg.w === 1920 && r.msg.h === 1080, JSON.stringify(r && r.msg));
  await until(async () => ((await js('lodPixels()')) || {}).w === 1920, 3000);
  const px1920 = (await js('lodPixels()')) || {};
  check('…and the picture follows', `${px1920.w}x${px1920.h}` === '1920x1080', JSON.stringify(px1920));
  const q = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'quality'), 2000);
  check("…with the bitrate for the new size's pixels", q && q.msg.bitrate === rate('medium', 1920, 1080), JSON.stringify(q && q.msg));
  await sleep(600);
  const asks = events.filter((e) => e.at >= t && e.msg && e.msg.t === 'resize').length;
  check('…asked once, not over and over', asks === 1, `${asks}`);
  t = Date.now();
  await cdp('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: 1, mobile: false });
  await until(() => evSince(t, (e) => e.msg && e.msg.t === 'resize'), 3000);
  await sleep(600);
}

console.log('a reload');
{
  // First, staying: the question answered Stay, the desktop still there.
  B.dialogAnswer.leave = false;
  let asked = B.dialogs.length;
  await Promise.race([cdp('Page.reload'), sleep(3000)]);
  await until(() => B.dialogs.length > asked, 3000);
  check('a page showing Linux asks before it is left: a reload is the browser\'s question first',
    B.dialogs.length > asked && B.dialogs.at(-1).type === 'beforeunload', JSON.stringify(B.dialogs.slice(asked)));
  await sleep(800);
  check('…and Stay keeps the desktop on screen, the same page', await isUp());
  B.dialogAnswer.leave = true;
  asked = B.dialogs.length;
  const t = Date.now();
  await cdp('Page.reload');
  const h = await until(() => evSince(t, (e) => e.hello), 8000);
  check('Leave: the page loaded again, back without the code, and without starting a desktop someone may have shut',
    h && h.hello.start === false && !('token' in h.hello) && B.dialogs.length > asked, JSON.stringify(h && h.hello));
  check('…the desktop on screen', !!(await until(isUp, 8000)));
  check("…and the phone's name on the tab", (await js('document.title')) === 'Linux · Harness Phone');
  await sleep(4500); // the bar's first showing, over
}

console.log('the plain address, once this browser takes the certificate');
{
  const t = Date.now();
  await cdp('Page.navigate', { url: `http://${IP}:${PORT}/` });
  const back = await until(async () => (await js(`location.protocol === 'https:' && document.body.classList.contains('up')`)) || null, 10000);
  const connsNow = back ? '' : await command('conns', 'CONNS ');
  check('typed without https again: the plain page finds the secure one works now, and goes there by itself, to the desktop',
    !!back, `${connsNow} ${await js('location.href')} ${JSON.stringify(await js(`({ vis: document.visibilityState, body: document.body.className,
      cards: [...document.querySelectorAll('.card')].filter((c) => !c.hidden).map((c) => c.id + ': ' + c.textContent.trim().slice(0, 80)),
      video: typeof lodVideo === 'function' ? lodVideo() : null })`))} ${JSON.stringify(events.filter((e) => e.at >= t).slice(0, 12))}`);
  const plainHello = events.filter((e) => e.at >= t && e.hello && /not encrypted/.test(e.who || '')).length;
  check('…with no stream ever opened in the clear on the way', plainHello === 0, `${plainHello}`);
  await sleep(4500);
}

console.log('every way it ends');
{
  // A slow computer: the decoder holds frames that come out after the phone's
  // word. Seen once by chance under load; here on purpose, every time.
  await cdp('Emulation.setCPUThrottlingRate', { rate: 8 });
  await sleep(600);
  say('burst stopped');
  await until(() => visible('ended'), 3000);
  check('Linux shut down on the phone: said, with Start', (await js(`document.getElementById('ended-title').textContent`)) === 'Linux is not running'
    && (await js(`document.getElementById('ended-go').textContent`)) === 'Start Linux');
  // The stand-in keeps sending after its word, and the decoder still has
  // frames in it, as a phone's last frames may still be on their way: the
  // page must have let go of both.
  let stayed = true;
  for (let i = 0; i < 30; i++) {
    if (!(await visible('ended'))) stayed = false;
    await sleep(50);
  }
  await cdp('Emulation.setCPUThrottlingRate', { rate: 1 });
  check('…and stays said: frames still in the decoder or on their way draw nothing over it', stayed && (await visible('ended')));
  check('…over the last picture, dimmed, not a black page', await js(`document.getElementById('cover').classList.contains('over')`));
  await shot('06-stopped');
  let t = Date.now();
  await js(`document.getElementById('ended-go').click()`);
  let h = await until(() => evSince(t, (e) => e.hello), 4000);
  check('Start: a new stream that asks for the desktop to start', h && h.hello.start === true);
  await until(isUp, 6000);

  say('state replaced');
  await until(() => visible('ended'), 3000);
  check('opened in another window: said, with Show the desktop here, and no taking the desktop back by itself',
    (await js(`document.getElementById('ended-title').textContent`)) === 'Linux opened somewhere else');
  t = Date.now();
  await sleep(2500);
  check('…nothing reconnects behind the person\'s back, and the card stays', !evSince(t, (e) => e.hello) && (await visible('ended')));
  await js(`document.getElementById('ended-go').click()`);
  await until(isUp, 6000);
  check('Show the desktop here: the desktop is back', await isUp());

  t = Date.now();
  say('drop');
  await until(() => visible('wait'), 3000);
  check('the connection dropped with no word: Reconnecting, by itself', (await js(`document.getElementById('wait-title').textContent`)) === 'Reconnecting');
  check('…and the bar has gone with the picture', !(await js(`document.body.classList.contains('bar-on')`)));
  await shot('07-reconnecting');
  h = await until(() => evSince(t, (e) => e.hello), 6000);
  check('…without starting a desktop someone may have logged out of', h && h.hello.start === false, JSON.stringify(h && h.hello));
  await until(isUp, 6000);
  check('…and the picture is back', await isUp());

  // The microphone on as the phone signs this browser out.
  await freshMics();
  await js(`document.getElementById('mic').click()`);
  await until(async () => (await js('lodMic()')).reached === true, 4000);
  const recordingAtSignOut = await recordingMics();
  t = Date.now();
  say('signout');
  await until(() => visible('pair'), 4000);
  check('signed out on the phone: the page is told at once, asks for the new code, and says why',
    (await js(`document.getElementById('pair-error').textContent`)).startsWith('This browser was signed out'));
  check('…and forgets the old token', !(await js(`localStorage.getItem('lod.token')`)));
  await sleep(300);
  check('…and the microphone that was on stops recording', recordingAtSignOut === 1 && (await recordingMics()) === 0
    && (await js('lodMic()')).on === false, `before: ${recordingAtSignOut} ${await micsSaid()}`);
  await shot('08-signed-out');
  let code = await askCode();
  await js(`document.getElementById('code').focus()`);
  await cdp('Input.insertText', { text: code });
  await until(isUp, 10000);
  check('the new code lets it back in', await isUp());

  // Switched off in Settings while watching.
  await command('stop', 'OK stopped');
  await until(() => visible('ended'), 3000);
  check('switched off on the phone: said so, with Try again',
    (await js(`document.getElementById('ended-title').textContent`)) === 'Turned off on the phone'
    && (await js(`document.getElementById('ended-go').textContent`)) === 'Try again');
  await shot('09-off');
  await command('restart', 'OK restarted');
  t = Date.now();
  await js(`document.getElementById('ended-go').click()`);
  h = await until(() => evSince(t, (e) => e.hello), 6000);
  check('on again, Try again: back, with the same token, not starting Linux', h && h.hello.start === false, JSON.stringify(h && h.hello));
  await until(isUp, 6000);

  // A phone that takes every stream and ends it at once, with no word: a
  // new tab of the same browser, which has never seen the desktop.
  await until(() => /^watching /.test(lastState() || ''), 3000);
  const watching = lastState();
  await command('refuse on', 'OK refuse');
  t = Date.now();
  const fresh = await B.newTab(`${BASE}/`);
  // Asked again until the page is there to answer: before its DOM is, the question throws.
  const card = await until(async () => {
    const v = await fresh.js(`(() => { const e = document.getElementById('ended'); return e && !e.hidden ? document.getElementById('ended-title').textContent : null; })()`);
    return typeof v === 'string' && v ? v : null;
  }, 15000);
  check('a phone a new tab never reaches: after three tries, Cannot reach the phone',
    card === 'Cannot reach the phone', String(card));
  check('…with Try again, and Reload the page for a certificate the browser forgot',
    (await fresh.js(`document.getElementById('ended-go').textContent + ' / ' + (document.getElementById('ended-alt').hidden ? '' : document.getElementById('ended-alt').textContent)`)) === 'Try again / Reload the page');
  const tries = events.filter((e) => e.at >= t && e.refused).length;
  check('…three tries, and no more', tries === 3, `${tries}`);
  await fresh.shot('10-unreachable');
  check('streams that ended before they began leave the phone saying who is still watching', lastState() === watching, `${watching} → ${lastState()}`);
  await command('refuse off', 'OK refuse');
  t = Date.now();
  await fresh.js(`document.getElementById('ended-go').click()`);
  check('Try again, once it answers: the desktop', !!(await until(() => fresh.js(`document.body.classList.contains('up')`), 8000)));
  const otherTab = await until(() => visible('ended'), 4000);
  check('…and the other tab is told it opened here', !!otherTab
    && (await js(`document.getElementById('ended-title').textContent`)) === 'Linux opened somewhere else',
    `${await js(`document.visibilityState + ' / ' + document.body.className + ' / ' + JSON.stringify([...document.querySelectorAll('.card')]
      .filter((c) => !c.hidden).map((c) => c.id + ': ' + c.textContent.trim().slice(0, 60)))`)} ${JSON.stringify(events.filter((e) => e.at >= t
      && (e.hello || e.refused || e.changed || (e.msg && e.msg.t !== 'ping'))).map((e) => ({ at: e.at - t, ...(e.hello ? { hello: e.who } : e) })).slice(0, 14))}`);
  check('no script errors in the new tab', fresh.pageErrors.length === 0, fresh.pageErrors.slice(0, 3).join(' | '));
  await fresh.cdp('Page.navigate', { url: 'about:blank' });
  fresh.close();
  // In front again: a headless browser can leave this tab believing it is
  // still behind the other, and a tab out of sight asks for no picture.
  await cdp('Page.bringToFront');
  await js(`document.getElementById('ended-go').click()`);
  await until(isUp, 6000);
  check('Show the desktop here: the first tab has the desktop back', await isUp());

  await freshMics();
  await js(`document.getElementById('mic').click()`);
  await until(async () => (await js('lodMic()')).reached === true, 4000);
  const recordingAtForget = await recordingMics();
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 1 });
  await until(() => js(`document.body.classList.contains('bar-on')`), 2000);
  await js(`document.getElementById('more').click()`);
  await shot('11-menu');
  lastTokens = null;
  say('tokens');
  const before = await until(() => lastTokens, 2000);
  // The phone slow to answer the forget (2 s here): until 2026-11-21 (the
  // audit's second round) the microphone stopped only once it had.
  await js(`(() => {
    if (!window.__slowForget) {
      const real = window.fetch;
      window.fetch = (u, o) => String(u).includes('forget') && window.__forgetDelay
        ? new Promise((r) => setTimeout(r, window.__forgetDelay)).then(() => real(u, o)) : real(u, o);
      window.__slowForget = true;
    }
    window.__forgetDelay = 2000;
  })()`);
  await js(`document.getElementById('m-forget').click()`);
  await sleep(300);
  const recordingAfterPress = await recordingMics();
  const answeredYet = await visible('pair');
  await until(() => visible('pair'), 5000);
  await js('window.__forgetDelay = 0');
  lastTokens = null;
  say('tokens');
  const after = await until(() => lastTokens !== null ? { n: lastTokens } : null, 2000);
  check('Forget this browser: the phone lets go of its token, and the page asks for the code', after && after.n === before - 1 && (await visible('pair')), `${before} → ${after && after.n}`);
  check('…and the microphone that was on stops at the press, before the phone has answered', recordingAtForget === 1
    && recordingAfterPress === 0 && !answeredYet && (await recordingMics()) === 0 && (await js('lodMic()')).on === false,
    `before: ${recordingAtForget}, 300 ms after the press: ${recordingAfterPress} (answered: ${answeredYet}) ${await micsSaid()}`);
}

console.log("the code card's other answers");
{
  const typed = async (digits) => {
    await js(`document.getElementById('pair-error').textContent = ''`);
    await js(`document.getElementById('code').focus()`);
    await cdp('Input.insertText', { text: digits });
    return until(async () => {
      const e = await js(`document.getElementById('pair-error').textContent`);
      return e ? e : null;
    }, 5000);
  };
  await command('close', 'OK close');
  let said1 = await typed(await askCode());
  check("the phone's card not open: the right code is not taken, and the page says where to open it",
    String(said1).startsWith('Open Settings › Open in a browser on the phone'), String(said1));
  await shot('12-pair-closed');
  await command('open', 'OK open');

  await command('lockafter 1000', 'OK lockafter');
  const code = (await command('newcode', 'CODE ')).slice(5);
  for (let i = 0; i < 10; i++) await pairWith(notThe(code));
  said1 = await typed(code);
  check('ten wrong codes tried against it, from anywhere: the page says the phone can make a new one',
    String(said1).startsWith('Too many wrong codes were tried'), String(said1));

  await command('lockafter 1', 'OK lockafter');
  await command('restart', 'OK restarted');
  const next = (await command('newcode', 'CODE ')).slice(5);
  said1 = await typed(notThe(next));
  check('locked out: the page counts down, and takes no digits meanwhile',
    /^Too many tries\. Try again in 0:(30|29)\.$/.test(String(said1)) && (await js(`document.getElementById('code').disabled`)), String(said1));
  await shot('13-pair-locked');
  await command('lockafter 5', 'OK lockafter');
  await command('restart', 'OK restarted');
}

console.log('plain http, in a browser that refuses the certificate');
{
  const P = await launchBrowser();
  check('a second browser, one that has never taken the certificate', !!P);
  if (P) {
    await P.cdp('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: 1, mobile: false });
    const PLAIN_BASE = `http://${IP}:${PORT}`;
    const pvis = (id) => P.js(`!document.getElementById('${id}').hidden && !document.body.classList.contains('up')`);
    const playerState = `(() => { const v = document.getElementById('video'); const b = [];
      for (let k = 0; k < v.buffered.length; k++) b.push(v.buffered.start(k).toFixed(2) + '-' + v.buffered.end(k).toFixed(2));
      return { ct: v.currentTime.toFixed(2), buf: b.join(','), rs: v.readyState, paused: v.paused, rate: v.playbackRate, sound: lodSound() }; })()`;
    // Navigations that cannot hang the run: a page that never answers is a failure, said.
    const pnav = (url) => Promise.race([P.cdp('Page.navigate', { url }), sleep(10000).then(() => ({ timeout: url }))]);
    const pUp = () => P.js(`document.body.classList.contains('up')`);
    await pnav(`${PLAIN_BASE}/`);
    await until(() => pvis('choose'), 8000);
    check('the plain page, in a browser that will not take the certificate: the choice', await pvis('choose'));
    check('…and no WebCodecs: browsers keep it to secure pages', await P.js(`!window.isSecureContext && typeof VideoDecoder === 'undefined'`));
    await P.js(`document.getElementById('choose-plain').click()`);
    await until(() => pvis('pair'), 5000);
    check('Continue without encryption: the code is asked for, on this page', await pvis('pair'));
    check('…and the choice is kept for next time', (await P.js(`localStorage.getItem('lod.plain')`)) === '1');
    const code = (await command('newcode', 'CODE ')).slice(5);
    let t = Date.now();
    const t0 = t;
    await P.js(`document.getElementById('code').focus()`);
    await P.cdp('Input.insertText', { text: code });
    const up = await until(pUp, 12000);
    const h = evSince(t, (e) => e.hello);
    check("the code over plain http: let in, the desktop on screen through the browser's video player, said as not encrypted",
      !!up && (await P.js(`document.body.classList.contains('player')`)) && h && /not encrypted/.test(h.who), JSON.stringify(h && h.who));
    await P.shot('20-plain-desktop');
    const pic = await P.js(`(() => { const v = document.getElementById('video'); return { w: v.videoWidth, h: v.videoHeight, rs: v.readyState, paused: v.paused }; })()`);
    check('…the player has the picture, at its size, and plays', pic.w === 1280 && pic.h === 720 && pic.rs >= 2 && !pic.paused, JSON.stringify(pic));
    // The first second's count is a part second: the one after it is whole.
    await sleep(2200);
    const stats = await P.js(`document.getElementById('stats').textContent`);
    const fps = /(\d+) fps/.test(stats) ? Number(stats.match(/(\d+) fps/)[1]) : 0;
    // Headless, a browser draws about 34 times a second (measured), so the
    // player cannot show 60 here; it only has to be showing frames.
    check('…frames shown as they come', fps >= 15, `${stats} ${JSON.stringify(await P.js(playerState))}`);
    check('the bar says it is not encrypted, and the menu offers the secure connection',
      (await P.js(`!document.getElementById('bar-plain').hidden && document.getElementById('bar-plain').textContent`)) === 'Not encrypted'
      && (await P.js(`!document.getElementById('m-secure').hidden && document.getElementById('m-secure').textContent`)) === 'Use the secure connection');

    t = Date.now();
    await P.click(640, 360);
    const ptr = await until(() => evSince(t, (e) => e.pointer && e.pointer[0] === 1), 2000);
    check('a click goes through the picture to the desktop, where it was made',
      ptr && Math.abs(ptr.pointer[1] - 640) <= 2 && Math.abs(ptr.pointer[2] - 360) <= 2, JSON.stringify(ptr && ptr.pointer));
    const snd = await until(async () => {
      const v = await P.js('lodSound()');
      return v && v.state === 'running' && v.runs > 5 ? v : null;
    }, 6000);
    // Typing the code was a person's action already, so sound may have
    // started with the stream (its hello asks); else the click starts it.
    const askedSound = evSince(t0, (e) => (e.hello && e.hello.audio === true) || (e.msg && e.msg.t === 'audio' && e.msg.on === true));
    check('sound starts with a person\'s action: the page asks the phone for it and plays what comes',
      !!snd && !!askedSound, JSON.stringify(await P.js('lodSound()')));

    t = Date.now();
    await P.cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 1 });
    await until(() => P.js(`document.body.classList.contains('bar-on')`), 2000);
    await P.shot('21-plain-bar');
    await P.js(`document.getElementById('sound').click()`);
    const offMsg = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'audio' && e.msg.on === false), 2000);
    const tapClosed = await until(() => evSince(t, (e) => e.tap === 'closed'), 3000);
    await sleep(400);
    const runs1 = (await P.js('lodSound()')).runs;
    await sleep(1500);
    const runs2 = (await P.js('lodSound()')).runs;
    check('Sound off: the page tells the phone, the phone lets go of the tap, and nothing more plays',
      !!offMsg && !!tapClosed && runs2 === runs1, `${runs1} → ${runs2}`);
    check('…the switch says so, and it is kept',
      (await P.js(`document.getElementById('sound').getAttribute('aria-pressed')`)) === 'false' && (await P.js(`localStorage.getItem('lod.sound')`)) === '0');
    t = Date.now();
    await P.js(`document.getElementById('sound').click()`);
    const onMsg = await until(() => evSince(t, (e) => e.msg && e.msg.t === 'audio' && e.msg.on === true), 3000);
    const more = await until(async () => ((await P.js('lodSound()')).runs > runs2 + 5) || null, 6000);
    check('…and on again: asked for, and playing', !!onMsg && !!more);
    // Plain http has no AudioDecoder: the smaller qualities are smaller PCM.
    check('no Opus on plain http: Medium is 24 kHz stereo PCM', (await P.js('lodSound()')).opus === false
      && (await P.js('lodSound()')).format === '24 kHz stereo', JSON.stringify(await P.js('lodSound()')));
    for (const [m, want] of [['low', '16 kHz mono'], ['high', '48 kHz stereo'], ['medium', '24 kHz stereo']]) {
      await P.js(`${seg(m)}.click()`);
      const got = await until(async () => {
        const v = await P.js('lodSound()');
        return v.format === want ? v : null;
      }, 4000);
      const r0 = got ? got.runs : 0;
      await sleep(1500);
      const r1 = (await P.js('lodSound()')).runs;
      check(`plain http, ${m}: ${want}, and it plays`, !!got && r1 > r0 + 10, `${JSON.stringify(got)} runs ${r0} → ${r1}`);
    }

    await P.js(`window.__copied = null; document.addEventListener('copy', (e) => { window.__copied = e.clipboardData.getData('text/plain'); })`);
    const text = 'Linux → here, over plain http: déjà vu';
    say(`clip ${Buffer.from(text).toString('base64')}`);
    await sleep(600);
    await P.click(400, 300);
    const copied = await until(() => P.js('window.__copied'), 2000);
    check("Linux's copy over plain http: no clipboard API there, so the browser's own copy carries it at the next click", copied === text, String(copied));

    let asked = P.dialogs.length;
    await pnav('about:blank');
    await until(() => P.dialogs.length > asked, 3000);
    check('leaving the tab while Linux is on screen: the browser asks first', P.dialogs.length > asked && P.dialogs.at(-1).type === 'beforeunload',
      JSON.stringify(P.dialogs.slice(asked)));
    const connsBefore = await command('conns', 'CONNS ');
    const backNav = await pnav(`${PLAIN_BASE}/`);
    const again = await until(pUp, 12000);
    let why = JSON.stringify(backNav).slice(0, 120);
    if (!again || backNav.timeout) {
      // What the server holds, and where its threads are, while the page waits.
      const connsNow = await command('conns', 'CONNS ');
      const dump = spawnSync(path.join(JDK, 'jstack'), [String(harness.pid)], { encoding: 'utf8' });
      const file = path.join(SHOTS || os.tmpdir(), `harness-threads-${Date.now()}.txt`);
      fs.writeFileSync(file, dump.stdout || dump.stderr || '');
      const acceptor = (dump.stdout || '').split('\n\n').find((t) => t.startsWith('"linux-browser"')) || 'no acceptor thread';
      why += ` | before: ${connsBefore} | during: ${connsNow} | page: ${await P.js('location.href + " " + document.readyState')}`
        + ` | threads: ${file} | acceptor: ${acceptor.replace(/\s+/g, ' ').slice(0, 400)}`;
    }
    check('back on the plain address: the choice and the token kept, straight to the desktop', !!again && !backNav.timeout, why);
    await P.cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 1 });
    await until(() => P.js(`document.body.classList.contains('bar-on')`), 2000);
    await P.js(`document.getElementById('more').click()`);
    asked = P.dialogs.length;
    await P.js(`document.getElementById('m-secure').click()`);
    await sleep(1500);
    const went = await P.js('location.href');
    check('Use the secure connection: to https without being asked first (this browser then meets its warning)',
      (/^chrome-error:/.test(went) || went.startsWith('https://')) && P.dialogs.length === asked, went);
    check('no script errors on the plain page', P.pageErrors.length === 0, P.pageErrors.slice(0, 3).join(' | '));
    await P.quit();
    P.close();
  }
}

console.log('the page itself');
{
  check('no script errors along the way', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
}

console.log(`\n${passed} passed, ${failed} failed`);
await B.quit();
B.close();
cleanup();
process.exit(failed ? 1 : 0);

// ── on a real phone ─────────────────────────────────────────────────────

/**
 * A SOCKS5 proxy here that lets the far end's bytes through to the browser at
 * {@code mbps}, and the browser's to the far end as they come: a slow link
 * that only the stream's own pace can keep up with. What waits for the link
 * waits in the phone's socket, as it would on slow Wi-Fi.
 */
/**
 * A link of {@code mbps} from the phone to here, as slowLink's, without the
 * SOCKS: every connection to it goes on to 127.0.0.1:{@code port} (an adb
 * forward, for the desktop app's stream).
 */
function slowRelay(mbps, port) {
  const rate = (mbps * 1e6) / 8;
  const server = net.createServer((client) => {
    client.on('error', () => {});
    client.pause();
    const far = net.connect(port, '127.0.0.1', () => {
      client.pipe(far);
      client.resume();
      const queue = [];
      let queued = 0;
      let tokens = 0;
      let last = Date.now();
      far.on('data', (c) => {
        queue.push(c);
        queued += c.length;
        if (queued > rate / 20) far.pause();
      });
      const tick = setInterval(() => {
        const now = Date.now();
        tokens = Math.min(rate / 50, tokens + (rate * (now - last)) / 1000);
        last = now;
        while (queue.length && tokens >= 1) {
          const c = queue[0];
          const n = Math.min(c.length, Math.floor(tokens));
          client.write(c.subarray(0, n));
          tokens -= n;
          queued -= n;
          if (n === c.length) queue.shift();
          else queue[0] = c.subarray(n);
        }
        if (queued < rate / 40) far.resume();
      }, 4);
      far.on('close', () => {
        clearInterval(tick);
        client.end();
      });
      client.on('close', () => {
        clearInterval(tick);
        far.destroy();
      });
    });
    far.on('error', () => client.destroy());
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

function slowLink(mbps) {
  const rate = (mbps * 1e6) / 8;
  const server = net.createServer((client) => {
    client.on('error', () => {});
    client.once('data', () => {
      client.write(Buffer.from([5, 0]));
      client.once('data', (req) => {
        let host;
        let at;
        if (req[3] === 1) {
          host = `${req[4]}.${req[5]}.${req[6]}.${req[7]}`;
          at = 8;
        } else if (req[3] === 3) {
          host = req.subarray(5, 5 + req[4]).toString();
          at = 5 + req[4];
        } else {
          client.destroy();
          return;
        }
        const far = net.connect(req.readUInt16BE(at), host, () => {
          client.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
          client.pipe(far);
          const queue = [];
          let queued = 0;
          let tokens = 0;
          let last = Date.now();
          far.on('data', (c) => {
            queue.push(c);
            queued += c.length;
            if (queued > rate / 20) far.pause();
          });
          const tick = setInterval(() => {
            const now = Date.now();
            tokens = Math.min(rate / 50, tokens + (rate * (now - last)) / 1000);
            last = now;
            while (queue.length && tokens >= 1) {
              const c = queue[0];
              const n = Math.min(c.length, Math.floor(tokens));
              client.write(c.subarray(0, n));
              tokens -= n;
              queued -= n;
              if (n === c.length) queue.shift();
              else queue[0] = c.subarray(n);
            }
            if (queued < rate / 40) far.resume();
          }, 4);
          far.on('close', () => {
            clearInterval(tick);
            client.end();
          });
          client.on('close', () => {
            clearInterval(tick);
            far.destroy();
          });
        });
        far.on('error', () => client.destroy());
      });
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

async function onDevice() {
  const SDK = process.env.ANDROID_HOME || path.join(os.homedir(), process.platform === 'win32' ? 'AppData/Local/Android/Sdk' : 'Library/Android/sdk');
  const win = process.platform === 'win32';
  const ADB = path.join(SDK, 'platform-tools', win ? 'adb.exe' : 'adb');
  const at = args.indexOf('--device');
  const serial = args[at + 1] && !args[at + 1].startsWith('--') ? args[at + 1] : null;
  if (!serial) {
    check('a phone named: --device <serial>', false);
    return;
  }
  const adb = (...a) => spawnSync(ADB, ['-s', serial, ...a], { encoding: 'utf8' });
  const model = adb('shell', 'getprop', 'ro.product.model').stdout.trim();
  console.log(`on ${serial} (${model})`);
  // The phone's own clock: its log is read from here on.
  const phoneStart = adb('shell', 'date', '+%s').stdout.trim();

  // The harness: the launcher's classes and a main, as one dex.
  const platform = fs.readdirSync(path.join(SDK, 'platforms')).filter((p) => /^android-3[5-9]$/.test(p)).sort().pop();
  const tools = fs.readdirSync(path.join(SDK, 'build-tools')).sort().pop();
  const hc = path.join(tmp, 'harness');
  fs.mkdirSync(hc, { recursive: true });
  const built = spawnSync(JAVAC, [
    '-nowarn', '-source', '17', '-target', '17', '-cp', path.join(SDK, 'platforms', platform, 'android.jar'), '-d', hc,
    ...['LinuxStream', 'LinuxStreamRfb', 'LinuxStreamEncoder', 'LinuxStreamSocket', 'LinuxStreamAudio', 'LinuxStreamOpus',
      'LinuxStreamRate', 'LinuxStreamMic', 'LinuxAudio', 'LinuxAudioLag', 'DexLog', 'LinuxBrowser', 'LinuxBrowserCert',
      'LinuxBrowserServer', 'LinuxBrowserPeek', 'LinuxFiles']
      .map((c) => path.join(LAUNCHER, `${c}.java`)),
    path.join(HERE, 'linux-browser/PhoneHarness.java'),
    path.join(HERE, 'linux-browser/CloseCheck.java'),
    path.join(HERE, 'usb-desktop/StandinXvnc.java'),
  ], { encoding: 'utf8' });
  check('the harness compiles against Android', built.status === 0, built.stderr);
  if (built.status !== 0) return;
  // One jar for d8: a list of class files is past Windows' command line.
  const jar = path.join(tmp, 'harness.jar');
  spawnSync(path.join(JDK, 'jar'), ['cf', jar, '-C', hc, '.'], { encoding: 'utf8' });
  const dexDir = path.join(tmp, 'dex');
  fs.mkdirSync(dexDir, { recursive: true });
  const d8 = path.join(SDK, 'build-tools', tools, win ? 'd8.bat' : 'd8');
  const dexed = win ? spawnSync('cmd.exe', ['/c', d8, '--min-api', '26', '--output', dexDir, jar], { encoding: 'utf8' })
    : spawnSync(d8, ['--min-api', '26', '--output', dexDir, jar], { encoding: 'utf8' });
  check('…and dexes', dexed.status === 0, dexed.stderr);
  if (dexed.status !== 0) return;
  const DEX = '/data/local/tmp/oadx-browser-test.dex';
  const PAGE = '/data/local/tmp/oadx-browser-page';
  const STATE = '/data/local/tmp/oadx-browser-state';
  cleanups.push(() => adb('shell', 'rm', '-rf', DEX, PAGE, STATE));
  check('the harness and the page go onto the phone', adb('push', path.join(dexDir, 'classes.dex'), DEX).status === 0
    && adb('shell', 'mkdir', '-p', PAGE).status === 0
    && fs.readdirSync(ASSETS).every((f) => adb('push', path.join(ASSETS, f), `${PAGE}/${f}`).status === 0));

  // test/linux-browser/CloseCheck.java, on the phone's own sockets: a close
  // on a link whose far end stopped reading, the send buffer full and nobody
  // writing. It waited there for as long as the link stayed up (until
  // 2026-11-21), and with it whoever ended the session.
  {
    const cc = spawnSync(ADB, ['-s', serial, 'shell', `CLASSPATH=${DEX} app_process / com.ccrstech.openandroiddex.launcher.CloseCheck`],
      { encoding: 'utf8', timeout: 30000 });
    const said = (name) => {
      const m = new RegExp(`${name} returned=(\\w+) ms=(\\d+) woke=(\\w+) filled=(\\d+)`).exec(cc.stdout || '');
      return m ? { returned: m[1] === 'true', ms: Number(m[2]), woke: m[3] === 'true', filled: Number(m[4]) } : null;
    };
    const c1 = said('CLOSE');
    const c2 = said('SAYCLOSE');
    check(`a close on a link the far end stopped reading, nothing writing: back in ${c1 ? c1.ms : '?'} ms, its goodbye given up, the writer behind it woken`,
      !!c1 && c1.returned && c1.ms < 1500 && c1.woke && c1.filled > 0, (cc.stdout + cc.stderr).slice(-600));
    check(`…and with the goodbye stuck in its write: the close that the deadline brings is back in ${c2 ? c2.ms : '?'} ms, and frees it`,
      !!c2 && c2.returned && c2.ms < 500 && c2.woke, (cc.stdout + cc.stderr).slice(-600));
  }

  const sizeArg = argOf('--size') || '1920x1080';
  const [W, H] = sizeArg.split('x').map(Number);
  const port = 17443;
  const harness = spawn(ADB, ['-s', serial, 'shell',
    `CLASSPATH=${DEX} app_process / com.ccrstech.openandroiddex.launcher.PhoneHarness ${STATE} ${port} ${W}x${H} standin1 ${PAGE}`],
  { stdio: ['pipe', 'pipe', 'pipe'] });
  cleanups.push(() => {
    try {
      harness.stdin.end();
    } catch {
      // gone
    }
    harness.kill();
  });
  const ev = [];
  const said = [];
  let out = '';
  let ready = null;
  let rest = '';
  let exited = null;
  harness.on('exit', (code) => (exited = { at: Date.now(), code }));
  harness.stdout.on('data', (d) => {
    out += d;
    rest += d;
    let i;
    while ((i = rest.indexOf('\n')) >= 0) {
      const line = rest.slice(0, i).replace(/\r$/, '').trim();
      rest = rest.slice(i + 1);
      if (!line.startsWith('EV ')) said.push({ at: Date.now(), line });
      if (line.startsWith('READY ')) {
        const [, p, code, fp, ip] = line.split(' ');
        ready = { port: Number(p), code, fp, ip };
      } else if (line.startsWith('EV ')) {
        try {
          ev.push({ at: Date.now(), ...JSON.parse(line.slice(3)) });
        } catch {
          // not one of ours
        }
      }
    }
  });
  harness.stderr.on('data', (d) => (out += d));
  await until(() => ready, 20000);
  check('the harness listens on the phone, with a code and a certificate', !!ready, out.slice(-500));
  if (!ready) return;
  const base = `https://${ready.ip}:${ready.port}`;
  console.log(`  ${base}`);

  // The door, over the phone's own TLS (Conscrypt) and its look-without-taking.
  const get = (mod, urlPath) => new Promise((resolve) => {
    const req = mod.request({ host: ready.ip, port: ready.port, path: urlPath, rejectUnauthorized: false, agent: false }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString(), socket: res.socket }));
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message }));
    req.end();
  });
  const plain = await get(http, '/');
  check("plain http to the phone is served too (the first byte looked at, not taken)", plain.status === 200 && plain.body.includes('id="choose"'), `${plain.status} ${plain.error || ''}`);
  const page = await get(https, '/');
  check("the page over the phone's TLS", page.status === 200 && page.body.includes('viewer.js'), `${page.status} ${page.error || ''}`);
  const raw = page.socket && page.socket.getPeerCertificate(true).raw;
  const x = raw ? new crypto.X509Certificate(raw) : null;
  check('the certificate the phone made: its address in it, signed by its own key, the fingerprint it says',
    x && x.subjectAltName.includes(`IP Address:${ready.ip}`) && x.verify(x.publicKey) && x.fingerprint256 === ready.fp, x && x.subjectAltName);

  const b = await launchBrowser();
  check('a browser for the phone', !!b);
  if (!b) return;
  await b.cdp('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'], origin: base });
  await b.cdp('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await b.cdp('Page.navigate', { url: `http://${ready.ip}:${ready.port}/` });
  await until(() => b.js(`!document.getElementById('choose').hidden`), 8000);
  check('typed without https: the plain page from the phone offers the secure way in first',
    await b.js(`!document.getElementById('choose').hidden`));
  await b.js(`document.getElementById('choose-secure').click()`);
  check('Continue securely, the warning, then Advanced and Continue: the page from the phone', !!(await clickThrough(b, base)));
  check('…a secure context, with WebCodecs and H.264', await b.js(`(async () => window.isSecureContext && (await VideoDecoder.isConfigSupported({ codec: 'avc1.640028' })).supported)()`));
  await until(() => b.js(`!document.getElementById('pair').hidden`), 8000);
  await b.js(`document.getElementById('code').focus()`);
  await b.cdp('Input.insertText', { text: ready.code });
  const up = await until(() => b.js(`document.body.classList.contains('up')`), 20000);
  check("the code typed: the phone's hardware-encoded desktop on screen", !!up, await b.js(`document.getElementById('wait-title').textContent + ' / ' + document.getElementById('ended-title').textContent + ' / ' + document.getElementById('ended-text').textContent`));
  if (!up) return;
  await b.shot('device-01-up');

  const read = async () => {
    const t = await b.js(`document.getElementById('stats').textContent`);
    const title = await b.js(`document.getElementById('stats').title`);
    const n = (re, s) => {
      const m = re.exec(s);
      return m ? Number(m[1]) : NaN;
    };
    return {
      fps: n(/(\d+) fps/, t), mbps: n(/([\d.]+) Mb\/s/, t), rtt: n(/· (\d+) ms/, t),
      encodeMs: n(/Encoded on the phone in ([\d.]+) ms/, title), decodeMs: n(/decoded here in ([\d.]+) ms/, title),
      encoder: (/ by ([^;(]+)/.exec(title) || [])[1]?.trim(), size: (/Picture (.+)\./.exec(title) || [])[1],
    };
  };
  const results = {};
  for (const mode of ['high', 'low', 'medium']) {
    await b.js(`document.querySelector('#modes button[data-mode=${mode}]').click()`);
    await sleep(6000); // the bitrate settles in a few seconds of motion
    const samples = [];
    for (let i = 0; i < 4; i++) {
      samples.push(await read());
      await sleep(1000);
    }
    const med = (k) => samples.map((s) => s[k]).filter((v) => !Number.isNaN(v)).sort((p, q) => p - q)[Math.floor(samples.length / 2)];
    results[mode] = { fps: med('fps'), mbps: med('mbps'), rtt: med('rtt'), encodeMs: med('encodeMs'), decodeMs: med('decodeMs'), size: samples.at(-1).size, encoder: samples.at(-1).encoder,
      smooth: await b.js(`document.getElementById('screen').classList.contains('smooth')`) };
    console.log(`  ${mode.padEnd(6)} ${JSON.stringify(results[mode])}`);
    await b.shot(`device-02-${mode}`);
  }
  check(`the phone's hardware encoder makes the picture (${results.medium.encoder})`, !!results.medium.encoder && !/android|google|\.sw\./i.test(results.medium.encoder));
  check('every pixel changing every frame, Medium keeps up: 30 fps or more', results.medium.fps >= 30, JSON.stringify(results.medium));
  // What a mode sets is a ceiling; what flows under it is the link's to say
  // (LinuxStream.adapt). So the ceilings are read where they land, in the
  // phone's own log, and the bitrates above are what the link carried.
  const log = adb('logcat', '-d', '-v', 'time', '-T', `${phoneStart}.000`, '-s', 'OpenDeX:I').stdout;
  const chose = {};
  for (const m of log.matchAll(/the browser chose (low|medium|high): ([\d.]+) Mb\/s/g)) if (!chose[m[1]]) chose[m[1]] = Number(m[2]);
  const want = (mode) => {
    const k = { low: [2 / 3, 0.05, 1.5e6, 8e6], medium: [1, 0.08, 4e6, 25e6], high: [1, 0.2, 10e6, 60e6] }[mode];
    return Math.round(Math.min(k[3], Math.max(k[2], W * H * k[0] * k[0] * 60 * k[1])) / 1e5) / 10;
  };
  check('each mode sets its own ceiling on the phone: Low < Medium < High',
    chose.low === want('low') && chose.medium === want('medium') && chose.high === want('high') && chose.low < chose.medium && chose.medium < chose.high,
    `${JSON.stringify(chose)} want ${want('low')}/${want('medium')}/${want('high')}`);
  const cuts = (log.match(/the link is behind/g) || []).length;
  console.log(`  the phone cut the bitrate for a link behind ${cuts} times; the bitrates above are what the link carried`);
  check('Low draws two thirds of the desktop and scales it up, smoothly; the others one pixel for one',
    /^\d+×\d+ drawn at /.test(results.low.size || '') && results.low.smooth && !results.high.smooth && !results.medium.smooth, results.low.size);
  check('Low is at least as fast as High', results.low.fps >= results.high.fps - 2, `${results.low.fps} vs ${results.high.fps}`);
  await b.js(`document.querySelector('#modes button[data-mode=medium]').click()`);
  await sleep(1500);

  // Key frames only when asked: fifteen seconds of every pixel moving, at
  // one quality, and no key frame the page did not ask for.
  check("a browser's encoder makes key frames only when asked (the phone's log says so)",
    /encoding \d+x\d+ as .*key frames when asked/.test(log), (log.match(/encoding .*$/m) || [''])[0]);
  const k0 = await b.js('lodVideo()');
  await sleep(15000);
  const k1 = await b.js('lodVideo()');
  check('…and in fifteen seconds of motion, no key frame comes that the page did not ask for',
    k1.keysIn - k0.keysIn <= k1.keysAsked - k0.keysAsked, JSON.stringify({ k0, k1 }));
  const st = await b.js('lodStats()');
  check("the bitrate follows how late frames arrive: the phone reports the queue it reads", st && typeof st.queueMs === 'number' && st.queueMs >= 0,
    JSON.stringify(st));

  // A hidden tab: no picture made or sent, the sound going on; back, from a key frame.
  const h0 = await b.js('lodVideo()');
  // Every change of the tab's visibility from here, as the page saw it: a
  // headless browser has been seen to show a tab behind another for a moment.
  await b.js(`window.__vis = []; document.addEventListener('visibilitychange',
    () => window.__vis.push(document.visibilityState + '@' + Math.round(performance.now()))); true`);
  const other = await b.cdp('Target.createTarget', { url: 'about:blank' });
  await until(() => b.js('document.visibilityState === "hidden"'), 3000);
  // Frames already on their way when the tab went land first, and those the
  // phone sent before the page's word reached it: until nothing has been
  // drawn for a second. On a congested Wi-Fi that was seconds (the phone's
  // own stats: 0.8 s and 4.3 s queued, 2026-11-20), past a fixed wait.
  const hiddenAt = Date.now();
  let lastDrawn = (await b.js('lodVideo()')).drawnTotal;
  let stillSince = Date.now();
  while (Date.now() - hiddenAt < 8000 && Date.now() - stillSince < 1000) {
    await sleep(100);
    const d = (await b.js('lodVideo()')).drawnTotal;
    if (d !== lastDrawn) {
      lastDrawn = d;
      stillSince = Date.now();
    }
  }
  const settledMs = stillSince - hiddenAt;
  const h1 = await b.js('lodVideo()');
  const st1 = JSON.stringify(await b.js('lodStats()'));
  await sleep(3000);
  const h2 = await b.js('lodVideo()');
  const st2 = JSON.stringify(await b.js('lodStats()'));
  const hiddenLog = adb('logcat', '-d', '-v', 'time', '-T', `${phoneStart}.000`, '-s', 'OpenDeX:I').stdout;
  // The phone sends its stats only with a frame: the same stats, no frame sent.
  const vis = await b.js('window.__vis');
  check('a hidden tab: the phone stops the picture (its log says so), sends no frame (its stats stand still), and nothing is drawn',
    /the browser's tab is hidden/.test(hiddenLog) && st1 === st2 && h2.drawnTotal - h1.drawnTotal <= 1,
    JSON.stringify({ settledMs, vis, h0, h1, h2, st1, st2, phone: hiddenLog.split('\n').filter((l) => /tab is (hidden|back)/.test(l)).slice(-4) }));
  console.log(`  the last frame on its way drawn ${settledMs} ms after the tab was hidden`);
  const backAt = Date.now();
  await b.cdp('Target.closeTarget', { targetId: other.result.targetId });
  await b.cdp('Page.bringToFront');
  const firstBack = await until(async () => ((await b.js('lodVideo()')).drawnTotal > h2.drawnTotal ? Date.now() : null), 5000, 5);
  const h3 = await b.js('lodVideo()');
  check('…the tab back: the picture again, from a key frame the phone made at once',
    !!firstBack && h3.keysIn > h2.keysIn, `${firstBack ? firstBack - backAt : '-'} ms, ${JSON.stringify(h3)}`);
  console.log(`  the tab back to the first frame drawn: ${firstBack ? firstBack - backAt : '-'} ms`);

  // The microphone: this browser's is a tone, into a pipe on the phone read as
  // Linux's PulseAudio reads one.
  const micLines = () => said.filter((l) => l.line.startsWith('MIC ')).map((l) => ({ at: l.at, bytes: Number(l.line.split(' ')[1]), rms: Number(l.line.split(' ')[2]) }));
  await b.js(`document.getElementById('mic').click()`);
  await until(async () => (await b.js('lodMic()')).reached === true, 5000);
  await sleep(3000);
  const ml = micLines();
  const ma = ml.at(-3);
  const mb = ml.at(-1);
  const rate = ma && mb ? ((mb.bytes - ma.bytes) * 1000) / (mb.at - ma.at) : 0;
  check(`the microphone reaches Linux's pipe on the phone: ${Math.round(rate / 1000)} kB/s (96 is 48 kHz mono), the tone in it`,
    (await b.js('lodMic()')).reached === true && rate > 80000 && rate < 115000 && mb.rms > 300, JSON.stringify({ mic: await b.js('lodMic()'), ma, mb }));
  await b.js(`document.getElementById('mic').click()`);
  const micT = Date.now();
  harness.stdin.write('micpipe none\n');
  await until(() => said.find((l) => l.at >= micT && l.line.startsWith('OK micpipe')), 3000);
  await b.js(`document.getElementById('mic').click()`);
  const warned = await until(async () => (await b.js(`document.getElementById('mic').classList.contains('warn')`)) || null, 5000);
  check('…a Linux with no pipe for it (one started by an older app): the page says so, in amber', !!warned
    && /^Linux cannot hear the microphone yet/.test(await b.js(`document.getElementById('toast').textContent`)));
  await b.js(`document.getElementById('mic').click()`);
  harness.stdin.write('micpipe fifo\n');

  // The same phone through a slow link, each way of reading it in turn.
  const throttled = async (mbps) => {
    const proxy = await slowLink(mbps);
    const pb = await launchBrowser([`--proxy-server=socks5://127.0.0.1:${proxy.address().port}`, '--proxy-bypass-list=<-loopback>']);
    if (!pb) {
      check('a second browser, through the slow link', false);
      return;
    }
    await pb.cdp('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
    await pb.cdp('Page.navigate', { url: `${base}/` });
    await clickThrough(pb, base);
    await until(() => pb.js(`!document.getElementById('pair').hidden`), 8000);
    const t0 = Date.now();
    harness.stdin.write('code\n');
    const codeLine = await until(() => said.find((l) => l.at >= t0 && l.line.startsWith('CODE ')), 3000);
    await pb.js(`document.getElementById('code').focus()`);
    await pb.cdp('Input.insertText', { text: codeLine ? codeLine.line.slice(5) : '' });
    const pbUp = await until(() => pb.js(`document.body.classList.contains('up')`), 20000);
    check(`a second browser, through a link of ${mbps} Mb/s, shows the desktop`, !!pbUp);
    const rows = {};
    for (const round of [0, 1]) {
      for (const rule of round ? ['old', 'new'] : ['new', 'old']) {
        await pb.js(`localStorage.setItem('lod.ackAt', '${rule === 'new' ? '1' : '0'}'); localStorage.setItem('lod.mode', 'high'); true`);
        await pb.cdp('Page.reload', {});
        await until(() => pb.js(`document.body.classList.contains('up')`), 20000);
        await sleep(8000); // the bitrate finds the link
        const samples = [];
        let d0 = (await pb.js('lodVideo()')).drawnTotal;
        for (let i = 0; i < 10; i++) {
          await sleep(1000);
          const v = await pb.js('lodVideo()');
          const s = (await pb.js('lodStats()')) || {};
          samples.push({ rtt: s.rttMs, kbps: s.kbps, queue: s.queueMs, fps: v.drawnTotal - d0, target: s.targetKbps });
          d0 = v.drawnTotal;
        }
        const med = (k) => samples.map((x) => x[k]).filter((x) => typeof x === 'number').sort((p, q) => p - q)[5];
        const p90 = (k) => samples.map((x) => x[k]).filter((x) => typeof x === 'number').sort((p, q) => p - q)[8];
        const row = { rtt: med('rtt'), rtt90: p90('rtt'), mbps: Math.round(med('kbps') / 100) / 10, fps: med('fps'), queue: med('queue') };
        (rows[rule] ||= []).push(row);
        console.log(`  ${mbps} Mb/s, ${rule === 'new' ? 'new rule (arrival times)' : 'old rule (a full link)'}: round trip ${row.rtt} ms median, `
          + `${row.rtt90} ms at 90%, ${row.mbps} Mb/s, ${row.fps} fps${rule === 'new' ? `, queue ${row.queue} ms` : ''}`);
      }
    }
    // Real Wi-Fi swings from one round to the next: the rounds' average, of
    // the median and of the 90th percentile, each rule against the other.
    const avg = (rule, k) => Math.round(rows[rule].reduce((s, r) => s + r[k], 0) / rows[rule].length);
    check(`on a link of ${mbps} Mb/s, frames come back sooner under the new rule than the old: ${avg('new', 'rtt')} against ${avg('old', 'rtt')} ms `
      + `at the median, ${avg('new', 'rtt90')} against ${avg('old', 'rtt90')} at 90% (the rounds' average)`,
    avg('new', 'rtt') < avg('old', 'rtt') && avg('new', 'rtt90') < avg('old', 'rtt90'), JSON.stringify(rows));
    await pb.quit();
    pb.close();
    proxy.close();
  };

  let t = Date.now();
  await b.click(Math.round(W / 3), Math.round(H / 3));
  const p = await until(() => ev.find((e) => e.at >= t && e.type === 'pointer' && e.buttons === 1), 3000);
  check("a click reaches the phone's Xvnc where it was made", p && Math.abs(p.x - Math.round(W / 3)) <= 1 && Math.abs(p.y - Math.round(H / 3)) <= 1, JSON.stringify(p));
  t = Date.now();
  await b.press('a', 'KeyA', 65, 'a');
  check('a key reaches it', !!(await until(() => ev.find((e) => e.at >= t && e.type === 'key' && e.keysym === 0x61 && e.down), 3000)));
  const linuxText = 'from the phone — 日本語 🙂';
  harness.stdin.write(`clip ${Buffer.from(linuxText).toString('base64')}\n`);
  const copied = await until(async () => ((await b.js(`navigator.clipboard.readText()`)) === linuxText ? true : null), 5000);
  check("Linux's clipboard reaches this computer's, through the phone's Xvnc client", !!copied);
  await b.js(`navigator.clipboard.writeText('to the phone — ½ €')`);
  t = Date.now();
  await b.js(`document.getElementById('screen').focus()`);
  await b.cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Control', code: 'ControlLeft', windowsVirtualKeyCode: 17, modifiers: 2 });
  await b.cdp('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, modifiers: 2 });
  await b.cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'v', code: 'KeyV', windowsVirtualKeyCode: 86, modifiers: 2 });
  await b.cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Control', code: 'ControlLeft', windowsVirtualKeyCode: 17 });
  const notified = await until(() => ev.find((e) => e.at >= t && (e.type === 'clip-notify' || e.type === 'clipboard' || e.type === 'clip-provide')), 4000);
  check("Ctrl+V: this computer's clipboard reaches Xvnc on the phone", !!notified, JSON.stringify(ev.filter((e) => e.at >= t).slice(0, 4)));

  t = Date.now();
  await b.cdp('Emulation.setDeviceMetricsOverride', { width: 1600, height: 900, deviceScaleFactor: 1, mobile: false });
  const sized = await until(() => ev.find((e) => e.at >= t && e.type === 'set-desktop-size'), 4000);
  check('the window resized: the desktop on the phone is asked to match', sized && sized.w === 1600 && sized.h === 900, JSON.stringify(sized));
  const follows = await until(async () => {
    const px = (await b.js('lodPixels()')) || {};
    return px.w === 1600 && px.h === 900;
  }, 6000);
  check('…a new encoder at the new size, and the picture follows', !!follows);

  // A second window on the same desktop: the first is told, and does not take it back.
  // What the phone said meanwhile: LinuxStream logs to logcat (pid, then the line).
  const secondAt = Date.now();
  // Since then only (-T, in seconds since 1970): the whole buffer is past
  // spawnSync's 1 MB. Each line ends in CRLF on Windows: split on that, or no
  // line matches (until 2026-11-21 this read nothing at all here).
  const phoneSince = (sinceMs) => (spawnSync(ADB, ['-s', serial, 'logcat', '-d', '-v', 'epoch', '-T', ((sinceMs - 1000) / 1000).toFixed(3),
    '-s', 'OpenDeX:*'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).stdout || '').split(/\r?\n/)
    .map((l) => /^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+\w\s+OpenDeX\s*:\s*(.*)$/.exec(l))
    .filter((m) => m && Number(m[1]) * 1000 >= sinceMs - 1000 && /\[(stream|browser)\]/.test(m[3]))
    .map((m) => `${Math.round(Number(m[1]) * 1000 - sinceMs)} ${m[2]} ${m[3].slice(0, 140)}`).slice(0, 30).join(' | ');
  const firstTab = async () => b.js(`document.visibilityState + ' ' + document.body.className + ' ' + JSON.stringify([...document.querySelectorAll('.card')]
    .filter((c) => !c.hidden).map((c) => c.id + ': ' + c.textContent.trim().slice(0, 50)))`);
  const second = await b.newTab(`${base}/`);
  await until(() => second.js(`document.body && document.body.classList.contains('up')`), 20000);
  check('a second tab opens the same desktop with the same browser\'s token', await second.js(`document.body.classList.contains('up')`));
  const told = await until(() => b.js(`!document.getElementById('ended').hidden && document.getElementById('ended-title').textContent`), 6000);
  check('the first is told the desktop opened somewhere else (LinuxStream says so)', told === 'Linux opened somewhere else',
    `${told} / the first tab: ${await firstTab()} / the phone: ${phoneSince(secondAt)}`);
  await sleep(4000);
  check('…and neither takes it back from the other', (await second.js(`document.body.classList.contains('up')`)) && !(await b.js(`document.body.classList.contains('up')`)),
    `the first tab: ${await firstTab()} / the phone: ${phoneSince(secondAt)}`);
  await b.shot('device-03-replaced');

  // The last word to a page not reading at that moment, still sending, as a
  // busy page's acknowledgements and pings keep coming: closed with them
  // unread, Linux answers with a reset, which throws away what the phone had
  // not sent yet and what the browser had not read, the word with it. A page
  // never told it was replaced comes back by itself and takes the desktop.
  {
    const token = await second.js(`localStorage.getItem('lod.token')`);
    const at = { ip: ready.ip, port: ready.port, origin: base, token };
    const hello = { t: 'hello', v: 1, start: false, w: 640, h: 360, acks: true, mode: 'low', bitrate: 1000000, fps: 30 };
    const t0 = Date.now();
    const A = await wsConnect(at);
    A.send(hello);
    const going = await until(() => A.texts.some((x) => x.includes('"video"')) && A.frames >= 2, 10000);
    A.pause();
    const move = Buffer.from([0x10, 0, 0, 20, 0, 20]);
    const flood = setInterval(() => {
      for (let i = 0; i < 20; i++) A.frame(2, move);
    }, 2);
    const B = await wsConnect(at);
    B.send(hello);
    await sleep(700);
    A.resume();
    const word = await until(() => A.texts.find((x) => x.includes('"replaced"')), 4000);
    clearInterval(flood);
    A.end();
    check('told it was replaced while it was not reading and still sending: the word still comes',
      !!going && !!word, `${!!going} / ${A.texts.slice(-3).join(' ')} / closed ${A.closedAt ? A.closedAt - t0 : 'no'} / the phone: ${phoneSince(t0)}`);
    B.end();
    // The second tab, told it was replaced too, takes the desktop back for what follows.
    await until(() => second.js(`!document.getElementById('ended').hidden`), 6000);
    await second.js(`document.getElementById('ended-go').click()`);
    await until(() => second.js(`document.body.classList.contains('up')`), 15000);
  }

  // Signed out, then switched off, as LinuxService does both: from the main
  // thread, where Android allows no network. Both crashed LinuxOnDroid on
  // the S25 (NetworkOnMainThreadException), taking Linux with it.
  const ask = async (cmd, answer, ms = 6000) => {
    const t0 = Date.now();
    harness.stdin.write(`${cmd}\n`);
    const hit = await until(() => said.find((l) => l.at >= t0 && l.line.startsWith(answer)), ms);
    return hit ? hit.line : null;
  };

  // The same last word to a page whose session still waited for Linux to
  // start: its own thread was not reading then, and until 2026-11-21 it
  // closed the moment it saw the word, the page's sends unread: a reset.
  {
    const token = await second.js(`localStorage.getItem('lod.token')`);
    const at = { ip: ready.ip, port: ready.port, origin: base, token };
    const hello = { t: 'hello', v: 1, start: false, w: 640, h: 360, acks: true, mode: 'low', bitrate: 1000000, fps: 30 };
    const held = await ask('desktop starting', 'OK desktop');
    const t0 = Date.now();
    const A = await wsConnect(at);
    A.send(hello);
    const waiting = await until(() => A.texts.some((x) => x.includes('"starting"')), 8000);
    A.pause();
    const move = Buffer.from([0x10, 0, 0, 20, 0, 20]);
    const flood = setInterval(() => {
      for (let i = 0; i < 20; i++) A.frame(2, move);
    }, 2);
    const B = await wsConnect(at);
    B.send(hello);
    await sleep(700);
    A.resume();
    const word = await until(() => A.texts.find((x) => x.includes('"replaced"')), 4000);
    clearInterval(flood);
    A.end();
    check('told it was replaced while Linux was still starting, not reading and still sending: the word still comes',
      !!held && !!waiting && !!word, `${held} / ${!!waiting} / ${A.texts.slice(-3).join(' ')} / closed ${A.closedAt ? A.closedAt - t0 : 'no'} / the phone: ${phoneSince(t0)}`);
    await ask('desktop running', 'OK desktop');
    B.end();
    await until(() => second.js(`!document.getElementById('ended').hidden`), 6000);
    await second.js(`document.getElementById('ended-go').click()`);
    await until(() => second.js(`document.body.classList.contains('up')`), 15000);
  }

  // A sign-out to a page not reading at that moment and still sending: the
  // same ending as being replaced, reached from the main thread.
  {
    const token = await second.js(`localStorage.getItem('lod.token')`);
    const at = { ip: ready.ip, port: ready.port, origin: base, token };
    const hello = { t: 'hello', v: 1, start: false, w: 640, h: 360, acks: true, mode: 'low', bitrate: 1000000, fps: 30 };
    const t0 = Date.now();
    const A = await wsConnect(at);
    A.send(hello);
    const going = await until(() => A.texts.some((x) => x.includes('"video"')) && A.frames >= 2, 10000);
    A.pause();
    const move = Buffer.from([0x10, 0, 0, 20, 0, 20]);
    const flood = setInterval(() => {
      for (let i = 0; i < 20; i++) A.frame(2, move);
    }, 2);
    const out = await ask('signout', 'OK signout');
    await sleep(500);
    A.resume();
    const word = await until(() => A.texts.find((x) => x.includes('"unauthorized"')), 4000);
    clearInterval(flood);
    A.end();
    check('told it was signed out while it was not reading and still sending: the word still comes',
      !!going && !!out && !!word, `${!!going} / ${out} / ${A.texts.slice(-3).join(' ')} / closed ${A.closedAt ? A.closedAt - t0 : 'no'} / the phone: ${phoneSince(t0)}`);
    // The second tab, replaced by that stream and signed out with it: the new code lets it back in.
    const code = (await ask('code', 'CODE ')).slice(5);
    await until(() => second.js(`!document.getElementById('ended').hidden`), 6000);
    await second.js(`document.getElementById('ended-go').click()`);
    await until(() => second.js(`!document.getElementById('pair').hidden`), 8000);
    await second.js(`document.getElementById('code').focus()`);
    await second.cdp('Input.insertText', { text: code });
    await until(() => second.js(`document.body.classList.contains('up')`), 20000);
  }

  // A takeover from a stream whose link has stopped: its page not reading,
  // and acknowledging all the same, so that the phone sends on until its
  // buffers are full and its writes wait. The session taking the desktop goes
  // on at once (the phone's own line: "took the desktop from … in N ms"), and
  // the stalled one, whose word can find no room, closes soon after. Until
  // 2026-11-21 (an audit's second round) the takeover waited up to 300 ms on
  // the old session's word, and then on its microphone. Then a page that
  // reads the word but never closes its end: the phone's own deadline ends
  // it, a second after its last word began.
  {
    const phoneLog = (sinceMs) => (spawnSync(ADB, ['-s', serial, 'logcat', '-d', '-v', 'epoch', '-T', ((sinceMs - 1000) / 1000).toFixed(3),
      '-s', 'OpenDeX:*'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).stdout || '').split(/\r?\n/)
      .map((l) => /^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+OpenDeX\s*:\s*(.*)$/.exec(l))
      // A second's margin: the phone's clock is its own (the S25's ran half a second ahead of this computer's).
      .filter((m) => m && Number(m[1]) * 1000 >= sinceMs - 1000 && /\[stream\]/.test(m[2]))
      .map((m) => ({ ms: Math.round(Number(m[1]) * 1000 - sinceMs), text: m[2] }));
    const token = await second.js(`localStorage.getItem('lod.token')`);
    const at = { ip: ready.ip, port: ready.port, origin: base, token };
    const A = await wsConnect(at);
    A.send({ t: 'hello', v: 1, start: false, w: W, h: H, acks: true, mode: 'high', bitrate: 20000000, fps: 60 });
    const going = await until(() => A.texts.some((x) => x.includes('"video"')) && A.frames >= 2, 10000);
    A.pause();
    const ackAll = setInterval(() => A.frame(2, [0x12]), 8);
    await sleep(7000);
    const tB = Date.now();
    const B = await wsConnect(at);
    B.send({ t: 'hello', v: 1, start: false, w: 640, h: 360, acks: true, mode: 'low', bitrate: 1000000, fps: 30 });
    const bUp = await until(() => B.texts.some((x) => x.includes('"video"')), 10000);
    await sleep(2500);
    clearInterval(ackAll);
    A.resume();
    await sleep(800);
    const lines = phoneLog(tB);
    const took = lines.map((l) => /took the desktop from .* in (\d+) ms/.exec(l.text)).find(Boolean);
    const word = lines.find((l) => /ending the session after its last word: replaced/.test(l.text));
    const ended = word && lines.find((l) => l.ms >= word.ms && /the browser's session ended/.test(l.text));
    // The premise: the link full, so that no word could go. A word that did
    // go says the buffers never filled, and then the takeover had nothing to
    // wait on: said, not counted.
    const wordGot = A.texts.some((x) => x.includes('"replaced"'));
    A.end();
    if (wordGot) {
      console.log(`  NOT DECIDED the stalled takeover: the old stream's link never filled (its page got the word); took ${took ? took[1] : '?'} ms`);
    } else {
      check(`a takeover from a stream stalled on its link goes on at once: took the desktop in ${took ? took[1] : '?'} ms`,
        !!going && !!bUp && !!took && Number(took[1]) < 100, `${!!going} / ${!!bUp} / ${JSON.stringify(lines.slice(0, 12))}`);
    }
    check(`…and the stalled stream closes soon after: ${word && ended ? ended.ms - word.ms : '?'} ms after its last word began`,
      !!word && !!ended && ended.ms - word.ms <= 1500, JSON.stringify(lines.slice(0, 12)));
    // B reads on, and never closes its end, as a page stuck in a long task
    // might not: a third stream takes the desktop, and B's session must end
    // by the phone's own deadline (LinuxBrowserServer.LAST_WORD_MS).
    const tC = Date.now();
    const C = await wsConnect(at);
    C.send({ t: 'hello', v: 1, start: false, w: 640, h: 360, acks: true, mode: 'low', bitrate: 1000000, fps: 30 });
    const bTold = await until(() => B.texts.some((x) => x.includes('"replaced"')) && B.closeFrame, 6000);
    await sleep(2500);
    const linesC = phoneLog(tC);
    const wordB = linesC.find((l) => /ending the session after its last word: replaced/.test(l.text));
    const endedB = wordB && linesC.find((l) => l.ms >= wordB.ms && /the browser's session ended/.test(l.text));
    check(`a page that read the word and the close and never closed its end: ended by the phone's deadline, ${wordB && endedB ? endedB.ms - wordB.ms : '?'} ms after its last word began`,
      !!bTold && !!wordB && !!endedB && endedB.ms - wordB.ms >= 900 && endedB.ms - wordB.ms <= 1600, `${!!bTold} / ${JSON.stringify(linesC.slice(0, 12))}`);
    B.end();
    C.end();
    await until(() => second.js(`!document.getElementById('ended').hidden`), 6000);
    await second.js(`document.getElementById('ended-go').click()`);
    await until(() => second.js(`document.body.classList.contains('up')`), 15000);
  }

  const okOut = await ask('signout', 'OK signout');
  const signedOut = await until(() => second.js(`!document.getElementById('pair').hidden && document.getElementById('pair-error').textContent`), 8000);
  check('signed out from the main thread: the watching tab is told at once, and asks for the new code',
    !!okOut && String(signedOut).startsWith('This browser was signed out'), `${okOut} / ${signedOut} ${exited ? `exited ${exited.code}` : ''}`);
  check('…and the phone process lives on', !!(await ask('alive', 'ALIVE')) && !exited, out.slice(-600));
  if (exited) {
    check('the phone process died, so nothing after it can be checked', false, `exit ${exited.code}`);
    second.close();
    await b.quit();
    b.close();
    return;
  }
  const newCode = (await ask('code', 'CODE ')).slice(5);
  await second.js(`document.getElementById('code').focus()`);
  await second.cdp('Input.insertText', { text: newCode });
  check('the new code lets it back in', !!(await until(() => second.js(`document.body.classList.contains('up')`), 20000)));

  // A browser being let in: its token checked, no longer a connection the
  // server drops, not yet the session LinuxStream ends. Held there
  // (slowhandoff) while the phone signs every browser out; LinuxStream asks
  // its gate as it takes the desktop.
  const handedOff = (t0) => said.find((l) => l.at >= t0 && l.line.startsWith('HANDOFF '));
  const streamedSince = (t0) => said.find((l) => l.at >= t0 && l.line === 'STREAMING true');
  await ask('slowhandoff 2500', 'OK slowhandoff');
  let t0 = Date.now();
  await second.cdp('Page.reload', {});
  let handoff = await until(() => handedOff(t0), 15000);
  const okRace = await ask('signout', 'OK signout');
  const raceCard = await until(() => second.js(`!document.getElementById('pair').hidden && document.getElementById('pair-error').textContent`), 10000);
  await sleep(1500);
  check('signed out while a browser is being let in: turned away as it would take the desktop, and told so',
    !!handoff && !!okRace && String(raceCard).startsWith('This browser was signed out') && !streamedSince(handoff.at)
    && !(await second.js(`document.body.classList.contains('up')`)),
    `${handoff && handoff.line} / ${okRace} / ${raceCard} / streamed: ${!!(handoff && streamedSince(handoff.at))}`);
  check('…and the phone process lives on', !!(await ask('alive', 'ALIVE')) && !exited, out.slice(-600));
  await ask('slowhandoff 0', 'OK slowhandoff');
  const thirdCode = (await ask('code', 'CODE ')).slice(5);
  await second.js(`document.getElementById('code').focus()`);
  await second.cdp('Input.insertText', { text: thirdCode });
  check('…and a new code lets it back in', !!(await until(() => second.js(`document.body.classList.contains('up')`), 20000)));

  const offOut = await ask('off', 'OK off');
  const offCard = await until(() => second.js(`!document.getElementById('ended').hidden && document.getElementById('ended-title').textContent`), 8000);
  check('switched off from the main thread: the tab says so', !!offOut && offCard === 'Turned off on the phone', `${offOut} / ${offCard} ${exited ? `exited ${exited.code}` : ''}`);
  check('…the phone process lives on', !!(await ask('alive', 'ALIVE')) && !exited, out.slice(-600));
  const gone = await new Promise((resolve) => {
    const s = net.connect({ host: ready.ip, port: ready.port }, () => {
      s.destroy();
      resolve(false);
    });
    s.on('error', () => resolve(true));
    setTimeout(() => {
      s.destroy();
      resolve(false);
    }, 4000);
  });
  check('…and nothing listens on its port', gone);
  await second.shot('device-04-off');

  // On again, then off while a browser is being let in.
  check('switched on again', !!(await ask('on', 'OK on')));
  await second.js(`document.getElementById('ended-go').click()`);
  check('…Try again brings the picture back', !!(await until(() => second.js(`document.body.classList.contains('up')`), 20000)));
  await ask('slowhandoff 2500', 'OK slowhandoff');
  t0 = Date.now();
  await second.cdp('Page.reload', {});
  handoff = await until(() => handedOff(t0), 15000);
  const offRace = await ask('off', 'OK off');
  const offRaceCard = await until(() => second.js(`!document.getElementById('ended').hidden && document.getElementById('ended-title').textContent`), 10000);
  await sleep(1500);
  check('switched off while a browser is being let in: turned away as it would take the desktop, and told so',
    !!handoff && !!offRace && offRaceCard === 'Turned off on the phone' && !streamedSince(handoff.at)
    && !(await second.js(`document.body.classList.contains('up')`)),
    `${handoff && handoff.line} / ${offRace} / ${offRaceCard} / streamed: ${!!(handoff && streamedSince(handoff.at))}`);
  check('…and the phone process lives on', !!(await ask('alive', 'ALIVE')) && !exited, out.slice(-600));
  await second.shot('device-05-off-while-let-in');

  // The desktop app's stream: a computer's session over adb, as
  // open-android-dex-tauri's UsbDesktop.tsx opens it, every frame acknowledged
  // on arrival; with the time it came (the app from 2026-11-21, its own
  // wire.ts: the bitrate follows how late frames arrive, as a browser's does),
  // or without (the app before: the old rule, which an app not yet updated
  // keeps). Over this phone's adb as it is (a cable, or wireless debugging),
  // then through a link of 1.5 Mb/s under a ceiling of Wi-Fi's: the round
  // trips are the phone's own (a frame ready to go, to its acknowledgement),
  // the median of each second's, then their median and 90th percentile over
  // ten seconds.
  {
    const wire = await import(pathToFileURL(path.join(ROOT, 'open-android-dex-tauri/src/features/remote/usb/wire.ts')).href);
    const wirelessAdb = serial.includes(':') || serial.includes('adb-tls-connect');
    const fwd = adb('forward', 'tcp:0', 'localabstract:oadx-browser-test.linux-stream');
    const fwdPort = Number((fwd.stdout || '').trim());
    cleanups.push(() => adb('forward', '--remove', `tcp:${fwdPort}`));
    check(`the desktop app's way in: an adb forward to the stream's socket (${wirelessAdb ? 'wireless debugging' : 'the cable'})`, fwdPort > 0, fwd.stdout + fwd.stderr);
    // A desktop of 640×400: the stand-in's every pixel changing every frame
    // is past what an encoder can make small, and at 1920×1080 its least
    // (the 0.5 Mb/s floor asked for, about 1 Mb/s made) was more than wireless
    // debugging carried on the S25 (2026-11-21, runs 10 and 11): both rules sat
    // at the floor through every second measured, with nothing to decide.
    const DW = 640;
    const DH = 400;
    const tierRate = (bpp, lo, hi) => Math.round(Math.min(hi, Math.max(lo, DW * DH * 60 * bpp)));
    const runRule = async (rule, viaPort, bitrate) => {
      const c = await wsConnect({ ip: '127.0.0.1', port: viaPort, origin: 'http://tauri.localhost', plain: true,
        ack: rule === 'new' ? () => wire.ackOf(wire.clock()) : 'plain' });
      c.send(rule === 'new' ? wire.helloOf(false, DW, DH, bitrate, true) : { t: 'hello', v: 1, start: false, w: DW, h: DH, bitrate, fps: 60, acks: true });
      const up = await until(() => c.texts.some((x) => x.includes('"video"')), 15000);
      await sleep(8000); // the bitrate finds the link
      const from = c.texts.length;
      await sleep(10000);
      const statsOf = (texts) => texts.map((x) => {
        try {
          return JSON.parse(x);
        } catch {
          return null;
        }
      }).filter((m) => m && m.t === 'stats');
      const stats = statsOf(c.texts.slice(from));
      // Each cut by what made it, the whole round (the counts start at none
      // with each stream) and the ten seconds measured.
      const all = statsOf(c.texts);
      const causes = ['cutsQueue', 'cutsFull', 'cutsHeld', 'cutsOld'];
      const last = all.at(-1) || {};
      const atStart = all[all.length - stats.length - 1] || {};
      const cutsRound = Object.fromEntries(causes.map((k) => [k.slice(4).toLowerCase(), last[k] || 0]));
      const cutsMeasured = Object.fromEntries(causes.map((k) => [k.slice(4).toLowerCase(), (last[k] || 0) - (atStart[k] || 0)]));
      c.end();
      await Promise.race([c.closed, sleep(2000)]);
      await sleep(500);
      const sorted = (k) => stats.map((s) => s[k]).filter((v) => typeof v === 'number').sort((p, q) => p - q);
      const at = (k, q) => {
        const a = sorted(k);
        return a.length ? a[Math.min(a.length - 1, Math.floor(a.length * q))] : NaN;
      };
      return {
        up: !!up, seconds: stats.length, rtt: at('rttMs', 0.5), rtt90: at('rttMs', 0.9), mbps: Math.round(at('kbps', 0.5) / 100) / 10,
        aimed: Math.round(at('targetKbps', 0.5) / 100) / 10, aimedMost: Math.round(at('targetKbps', 1) / 100) / 10,
        fps: at('fps', 0.5), queue: at('queueMs', 0.5), readsQueue: stats.some((s) => typeof s.queueMs === 'number'),
        cutsRound, cutsMeasured,
      };
    };
    const cutsSaid = (r) => Object.entries(r.cutsRound).filter(([, n]) => n).map(([k, n]) => `${k} ${n}${r.cutsMeasured[k] ? ` (${r.cutsMeasured[k]} measured)` : ''}`).join(', ') || 'none';
    /**
     * Two rules held at the floor through every second measured made the same
     * choice every time: their round trips then say how the link moved, not
     * which rule is the better. Said, and not counted either way.
     */
    const decided = (cmp) => [...cmp.rows.new, ...cmp.rows.old].some((r) => r.aimedMost > 0.5);
    const compare = async (label, viaPort, bitrate) => {
      const rows = { old: [], new: [] };
      for (const round of [0, 1]) {
        for (const rule of round ? ['old', 'new'] : ['new', 'old']) {
          const row = await runRule(rule, viaPort, bitrate);
          rows[rule].push(row);
          console.log(`  the desktop app, ${label}, ${rule === 'new' ? 'new rule (arrival times)' : 'old rule (a full link)'}: round trip ${row.rtt} ms median, `
            + `${row.rtt90} ms at 90%, ${row.mbps} Mb/s (aimed at ${row.aimed}), ${row.fps} fps${rule === 'new' ? `, queue ${row.queue} ms` : ''}; cuts: ${cutsSaid(row)}`);
        }
      }
      const avg = (rule, k) => Math.round(rows[rule].reduce((s, r) => s + r[k], 0) / rows[rule].length * 10) / 10;
      return { rows, avg };
    };
    // This phone's adb as it is, at its route's ceiling (UsbDesktop.tsx: the
    // cable 0.18 bits a pixel, 10 to 100 Mb/s; Wi-Fi 0.07, 4 to 30).
    const asIsCeiling = wirelessAdb ? tierRate(0.07, 4e6, 30e6) : tierRate(0.18, 10e6, 100e6);
    const way = wirelessAdb ? 'over wireless debugging as it is' : 'over the cable';
    const asIs = await compare(way, fwdPort, asIsCeiling);
    check('the desktop app saying arrival times gets the new rule (the phone reports the queue it reads), and one that does not keeps the old',
      asIs.rows.new.every((r) => r.up && r.readsQueue) && asIs.rows.old.every((r) => r.up && !r.readsQueue), JSON.stringify(asIs.rows));
    const noLater = asIs.avg('new', 'rtt') <= Math.max(asIs.avg('old', 'rtt') + 5, asIs.avg('old', 'rtt') * 1.2);
    if (!decided(asIs)) {
      console.log(`  NOT DECIDED ${way}: both rules at the 0.5 Mb/s floor through every second measured (the link carried less than the floor makes); `
        + `round trips ${asIs.avg('new', 'rtt')} against ${asIs.avg('old', 'rtt')} ms at the median`);
    } else if (wirelessAdb) {
      // Measured, not counted: over wireless debugging the desktop app keeps
      // the old rule (wire.ts readsTimes), since in six runs on the S25
      // (2026-11-21) the new one's median round trip was the longer in five.
      console.log(`  MEASURED ${way} (the desktop app keeps the old rule here): ${asIs.avg('new', 'rtt')} against ${asIs.avg('old', 'rtt')} ms at the median, `
        + `${asIs.avg('new', 'rtt90')} against ${asIs.avg('old', 'rtt90')} at 90%, aimed at ${asIs.avg('new', 'aimed')} against ${asIs.avg('old', 'aimed')} Mb/s (the rounds' average)`);
    } else {
      check(`${way} the new rule costs nothing: aimed at ${asIs.avg('new', 'aimed')} Mb/s against ${asIs.avg('old', 'aimed')} (the ceiling ${Math.round(asIsCeiling / 1e5) / 10}), `
        + `round trips ${asIs.avg('new', 'rtt')} against ${asIs.avg('old', 'rtt')} ms at the median (the rounds' average)`,
      asIs.avg('new', 'aimed') >= asIs.avg('old', 'aimed') * 0.95 && noLater, JSON.stringify(asIs.rows));
    }
    // A link of 1.5 Mb/s under Wi-Fi's ceiling (0.07 bits a pixel, 4 to 30 Mb/s): Wi-Fi that got worse.
    const relay = await slowRelay(1.5, fwdPort);
    const slow = await compare('through 1.5 Mb/s', relay.address().port, tierRate(0.07, 4e6, 30e6));
    relay.close();
    if (!decided(slow)) {
      console.log(`  NOT DECIDED through 1.5 Mb/s: both rules at the 0.5 Mb/s floor through every second measured; `
        + `round trips ${slow.avg('new', 'rtt')} against ${slow.avg('old', 'rtt')} ms at the median`);
    } else if (wirelessAdb) {
      // Over wireless debugging too, under the relay: measured, not counted (above).
      console.log(`  MEASURED through 1.5 Mb/s over wireless debugging (the desktop app keeps the old rule here): ${slow.avg('new', 'rtt')} against ${slow.avg('old', 'rtt')} ms `
        + `at the median, ${slow.avg('new', 'rtt90')} against ${slow.avg('old', 'rtt90')} at 90% (the rounds' average)`);
    } else {
      check(`the desktop app through a link of 1.5 Mb/s: frames come back sooner under the new rule than the old: ${slow.avg('new', 'rtt')} against ${slow.avg('old', 'rtt')} ms `
        + `at the median, ${slow.avg('new', 'rtt90')} against ${slow.avg('old', 'rtt90')} at 90% (the rounds' average)`,
      slow.avg('new', 'rtt') < slow.avg('old', 'rtt') && slow.avg('new', 'rtt90') < slow.avg('old', 'rtt90'), JSON.stringify(slow.rows));
    }

    // The route leaves the local network under a live stream, and comes back
    // (wire.ts routeChangedOf): the phone stops reading the times, then reads
    // them again, and the app's acknowledgements change with it. The new
    // rule takes a round trip over 400 ms for a queue, which on the internet
    // or a relay it need not be.
    {
      let timedNow = true;
      const c = await wsConnect({ ip: '127.0.0.1', port: fwdPort, origin: 'http://tauri.localhost', plain: true,
        ack: () => (timedNow ? wire.ackOf(wire.clock()) : wire.PLAIN_ACK) });
      const ceiling = tierRate(0.07, 4e6, 30e6);
      c.send(wire.helloOf(false, DW, DH, ceiling, true));
      await until(() => c.texts.some((x) => x.includes('"video"')), 15000);
      const statsSince = (n) => c.texts.slice(n).map((x) => {
        try {
          return JSON.parse(x);
        } catch {
          return null;
        }
      }).filter((m) => m && m.t === 'stats');
      await sleep(3000);
      const onLan = statsSince(0).slice(-2);
      let mark = c.texts.length;
      c.send(wire.routeChangedOf(tierRate(0.012, 1e6, 4e6), false));
      timedNow = false;
      await sleep(3500);
      const offLan = statsSince(mark).slice(1);
      mark = c.texts.length;
      c.send(wire.routeChangedOf(ceiling, true));
      timedNow = true;
      await sleep(3500);
      const backOnLan = statsSince(mark).slice(1);
      c.end();
      const reads = (s) => s.length > 0 && s.every((m) => typeof m.queueMs === 'number');
      const readsNot = (s) => s.length > 0 && s.every((m) => m.queueMs === undefined);
      check('the desktop app\'s route off the local network under a live stream: the phone stops reading the times, and reads them again once it is back',
        reads(onLan) && readsNot(offLan) && reads(backOnLan), JSON.stringify({ onLan, offLan, backOnLan }).slice(0, 900));
    }
  }

  // --throttle 1.5,1000: each in turn (1000, the Wi-Fi as it is). Last, since
  // its second browser takes the desktop: the switch on again first.
  if (argOf('--throttle')) {
    check('switched on again, for the slow links', !!(await ask('on', 'OK on')));
    for (const r of argOf('--throttle').split(',')) await throttled(Number(r));
  }

  check('no script errors in either tab', b.pageErrors.length === 0 && second.pageErrors.length === 0, [...b.pageErrors, ...second.pageErrors].slice(0, 3).join(' | '));
  second.close();
  await b.quit();
  b.close();
}
