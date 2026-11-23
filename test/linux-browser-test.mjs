#!/usr/bin/env node
// "Open in a browser" (doc/linux-browser.md), checked as far as a computer
// can without a phone.
//
//   node test/linux-browser-test.mjs [--browser <chrome or edge exe>] [--shots <dir>] [--node-only]
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
import zlib from 'node:zlib';
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

console.log('the page from anywhere (openandroiddex-remote/web/www)');
{
  // Its viewer is this phone.js, copied there by web/build.sh: the copies the
  // site serves must be these, and its own page must have every element the
  // viewer reaches for (a Files button, hidden there, and Picture size), or
  // the viewer stops at the first one missing.
  const WWW = path.join(ROOT, 'openandroiddex-remote/web/www');
  if (fs.existsSync(WWW) && !process.env.OADX_PAGE_DIR) {
    const stale = ['phone.js', 'phone.css', 'sound.js', 'h264.js', 'keysym.js', 'icon.svg']
      .filter((f) => !fs.existsSync(path.join(WWW, f)) || !fs.readFileSync(path.join(WWW, f)).equals(fs.readFileSync(path.join(ASSETS, f))));
    check("the site's copies of the viewer are the phone's own (web/build.sh copies them)", stale.length === 0, stale.join(', '));
    const used = [...new Set([...fs.readFileSync(path.join(ASSETS, 'phone.js'), 'utf8').matchAll(/\$\('([\w-]+)'\)/g)].map((m) => m[1]))];
    const page = fs.readFileSync(path.join(WWW, 'index.html'), 'utf8');
    const missing = used.filter((id) => !page.includes(`id="${id}"`));
    check(`its page has every element the viewer reaches for (${used.length})`, missing.length === 0, missing.join(', '));
  }
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

console.log("the phone's own screen (Remote desktop), through the door and the tunnel");
{
  // The harness stands in for PhoneStream on a loopback port of its own
  // (phoneLine), and says what each line was asked (phoneHead) and when it
  // closed (phoneClosed). The door is LinuxBrowserServer itself.
  const letIn = async () => said(await pairWith(await askCode())).token;
  /** An upgrade on /phone, frame by frame, as the page makes it; {@code extra}: more headers to send. */
  const phoneConnect = ({ token, ip = IP, host = `${IP}:${PORT}`, origin = BASE, extra = {} }) => new Promise((resolve) => {
    const s = tls.connect({ host: ip, port: PORT, rejectUnauthorized: false, servername: undefined }, () => {
      const key = crypto.randomBytes(16).toString('base64');
      const more = Object.entries(extra).map(([k, v]) => `${k}: ${v}\r\n`).join('');
      s.write(`GET /phone?t=${encodeURIComponent(token)} HTTP/1.1\r\nHost: ${host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n`
        + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n${origin ? `Origin: ${origin}\r\n` : ''}${more}\r\n`);
    });
    const c = { status: 0, texts: [], closedAt: 0, raw: '' };
    c.closed = new Promise((r) => s.on('close', () => { c.closedAt = Date.now(); r(c.closedAt); resolve(c); }));
    c.end = () => s.destroy();
    let buf = Buffer.alloc(0);
    s.on('error', () => {});
    s.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!c.status) {
        const end = buf.indexOf('\r\n\r\n');
        if (end < 0) return;
        c.raw = buf.slice(0, end).toString();
        c.status = Number(c.raw.split(' ')[1]);
        buf = buf.slice(end + 4);
        resolve(c);
      }
      while (buf.length >= 2) {
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) break; len = buf.readUInt16BE(2); off = 4; }
        if (buf.length < off + len) break;
        if ((buf[0] & 0x0f) === 1) c.texts.push(buf.slice(off, off + len).toString());
        buf = buf.slice(off + len);
      }
    });
  });
  const phones = async () => Number(((await command('phones?', 'PHONES ')) || 'PHONES -1').slice(7));
  const headOf = (t) => until(() => evSince(t, (e) => e.phoneHead), 3000);

  await command('phone off', 'OK phone');
  let c = await phoneConnect({ token: await letIn() });
  await within(c.closed, 3000);
  check('Remote desktop off: /phone is told so, and closed', c.texts.some((x) => /turned off/.test(x)) && c.closedAt > 0,
    JSON.stringify(c.texts));
  await command('phone dead', 'OK phone');
  c = await phoneConnect({ token: await letIn() });
  await within(c.closed, 3000);
  check('Remote desktop on and its stream not running: told how to start it', c.texts.some((x) => /not running/.test(x)), JSON.stringify(c.texts));
  await command('phone on', 'OK phone');

  // The way it came is the door's to say: a browser's own x-dex-* never gets through.
  let t = Date.now();
  const tokA = await letIn();
  const a = await phoneConnect({ token: tokA, extra: { 'X-Dex-Via': 'tunnel', 'x-dex-peer': 'someone else' } });
  let head = await headOf(t);
  const h = head ? head.phoneHead.headers : {};
  check("the browser's upgrade reaches the phone's screen, said to be from the Wi-Fi by the door alone",
    a.status === 101 && h['x-dex-via'] === 'wifi' && !('x-dex-peer' in h) && h.host === '127.0.0.1' && !('origin' in h),
    JSON.stringify({ status: a.status, head: h }));
  await until(() => a.texts.length > 0, 2000);
  check("…and the phone's own words come back through it", a.texts.some((x) => JSON.parse(x).t === 'video'), JSON.stringify(a.texts));
  check('…counted as a phone stream open', (await phones()) === 1);

  // Signed out on the phone: the stream outlives its token's check, and must end now.
  t = Date.now();
  await command('signout', 'CODE ');
  await within(a.closed, 3000);
  check('Sign them out ends a phone stream already running, at once',
    a.closedAt > 0 && a.closedAt - t < 2500 && !!(await until(() => evSince(t, (e) => e.phoneClosed), 2000)),
    JSON.stringify({ closedAfter: a.closedAt ? a.closedAt - t : null }));
  check('…and none is counted after', (await until(async () => (await phones()) === 0, 3000)) === true);

  // A browser's own Forget ends its stream, and only its own.
  const tokB = await letIn(), tokC = await letIn();
  const b = await phoneConnect({ token: tokB });
  const c2 = await phoneConnect({ token: tokC });
  await until(async () => (await phones()) === 2, 3000);
  t = Date.now();
  const f = await request('POST', '/forget', { headers: { ...FORM, Origin: BASE }, body: form({ token: tokB }) });
  await within(b.closed, 3000);
  await sleep(800);
  check("a browser's Forget ends its own phone stream and not another's",
    f.status === 200 && b.closedAt > 0 && c2.closedAt === 0 && (await phones()) === 1,
    JSON.stringify({ forget: f.status, b: b.closedAt ? b.closedAt - t : null, c: c2.closedAt }));
  t = Date.now();
  await command('stop', 'OK stopped');
  await within(c2.closed, 3000);
  check('the door stopping ends the phone stream too', c2.closedAt > 0, JSON.stringify({ c: c2.closedAt }));
  await command('restart', 'OK restarted');

  // Through the Cloudflare tunnel: cloudflared on the phone, over loopback, with the tunnel's name.
  const TUN = 'quiet-test-words.trycloudflare.com';
  const TBASE = `https://${TUN}`;
  await command(`tunnel ${TUN}`, 'OK tunnel');
  const viaTun = (method, p, extra = {}) => request(method, p, { host: '127.0.0.1', headers: { Host: TUN, 'cf-connecting-ip': '203.0.113.7', ...extra } });
  const root = await viaTun('GET', '/');
  const pages = {};
  for (const p of ['/index.html', '/viewer.js', '/mse.js', '/touch.js', '/phone.js', '/phone.css', '/sound.js', '/h264.js', '/keysym.js', '/icon.svg']) pages[p] = (await viaTun('GET', p)).status;
  // The phone's page has Files of its own since 2026-11-21 (phone-files.js, on
  // files.js's code): those files are checked with the phone's files below.
  check("through the tunnel: the phone's page at /, its files, and nothing of Linux's page",
    root.status === 200 && /phone\.js/.test(root.body) && pages['/index.html'] === 404 && pages['/viewer.js'] === 404
    && pages['/mse.js'] === 404 && pages['/touch.js'] === 404 && pages['/phone.js'] === 200 && pages['/phone.css'] === 200
    && pages['/sound.js'] === 200 && pages['/h264.js'] === 200 && pages['/keysym.js'] === 200 && pages['/icon.svg'] === 200,
    JSON.stringify({ root: root.status, pages }));
  const paired = said(await request('POST', '/pair', { host: '127.0.0.1', headers: { ...FORM, Host: TUN, Origin: TBASE, 'cf-connecting-ip': '203.0.113.7' }, body: form({ code: await askCode() }) })).token;
  const fs1 = await viaTun('GET', '/fs/list?path=%2F', { Authorization: `Bearer ${paired}` });
  const ws1 = await wsConnect({ ip: '127.0.0.1', host: TUN, origin: TBASE, token: paired });
  await within(ws1.closed, 2000);
  check("…Linux's files and Linux's stream refused there, with a token and Linux on",
    !!paired && fs1.status === 403 && (ws1.status === 403 || ws1.status === 0),
    JSON.stringify({ token: !!paired, fs: fs1.status, stream: ws1.status }));
  t = Date.now();
  const tp = await phoneConnect({ token: paired, ip: '127.0.0.1', host: TUN, origin: TBASE, extra: { 'cf-connecting-ip': '203.0.113.7', 'X-Dex-Via': 'wifi' } });
  head = await headOf(t);
  check("…the phone's screen through it, said to be the tunnel's",
    tp.status === 101 && head && head.phoneHead.headers['x-dex-via'] === 'tunnel', JSON.stringify({ status: tp.status, head: head && head.phoneHead }));
  tp.end();
  const byAddress = await request('GET', '/', { host: '127.0.0.1', headers: { Host: `127.0.0.1:${PORT}` } });
  check("loopback by any other name gets nothing, while the tunnel is up (the phone's other apps)", byAddress.status === 421, String(byAddress.status));
  await command('tunnel none', 'OK tunnel');
  const closedDoor = await request('GET', '/', { host: '127.0.0.1', headers: { Host: TUN } });
  check('with the tunnel down, loopback is not let in at all', closedDoor.status === 0, String(closedDoor.status));

  // Linux off, the door up for Remote desktop alone.
  await command('linux off', 'OK linux');
  const tokL = await letIn();
  const rootL = await request('GET', '/');
  const fsL = await request('GET', '/fs/list?path=%2F', { headers: { Authorization: `Bearer ${tokL}` } });
  const wsL = await wsConnect({ token: tokL });
  await within(wsL.closed, 2000);
  const pL = await phoneConnect({ token: tokL });
  check("Linux off with the door kept for Remote desktop: the phone's page at /, Linux's files and stream refused, the phone's screen given",
    rootL.status === 200 && /phone\.js/.test(rootL.body) && fsL.status === 403 && wsL.status === 403 && pL.status === 101,
    JSON.stringify({ root: rootL.status, fs: fsL.status, stream: wsL.status, phone: pL.status }));
  pL.end();
  await command('linux on', 'OK linux');
}

// ── the phone's own files (Remote desktop's Files) ──────────────────────
//
// The harness gives the door a folder standing in for the phone's shared
// storage (phonefs), as LinuxService gives it /storage/emulated/0: the
// launcher's own LinuxFiles, of the phone's kind, behind /pfs/.

/** Where the phone's storage is stood in for, and what lies beside it (never to be reached). */
const PHONE_STORAGE = path.join(tmp, 'phone-storage');
const PHONE_OUTSIDE = path.join(tmp, 'phone-outside');
const phonePut = (rel, data) => {
  const p = path.join(PHONE_STORAGE, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
  return p;
};
const PHONE_BIG = crypto.randomBytes(8 * 1024 * 1024 + 11);
{
  for (const d of ['Download', 'DCIM/Camera', 'Pictures', 'Documents', 'Movies', 'Android/data/com.example', 'WhatsApp/Media']) {
    fs.mkdirSync(path.join(PHONE_STORAGE, d), { recursive: true });
  }
  phonePut('Download/notes.txt', 'notes from the phone\n');
  phonePut('Download/big.bin', PHONE_BIG);
  phonePut('Documents/report.txt', 'a report\n');
  phonePut('Documents/Trip/day one.txt', 'day one\n');
  phonePut('Documents/Trip/day two.txt', 'day two\n');
  phonePut('Android/data/com.example/cache.txt', "an app's own\n");
  phonePut('WhatsApp/Media/hello.txt', 'hello\n');
  fs.mkdirSync(PHONE_OUTSIDE, { recursive: true });
  fs.writeFileSync(path.join(PHONE_OUTSIDE, 'secret.txt'), "outside the phone's storage\n");
  // Links, made by the harness with these very targets (Node on Windows would rewrite them).
  fs.writeFileSync(path.join(PHONE_STORAGE, '.links'), [
    'Documents/to-download\t/Download', // absolute: the storage's own Download, never the computer's
    'Documents/up\t../../../..', // climbs past the top: stops there
    `Documents/secret\t${path.join(PHONE_OUTSIDE, 'secret.txt')}`, // a path of the computer's
    '',
  ].join('\n'));
}

/** A request whose body and answer are bytes; the phone's certificate taken as it is. */
function phoneRequest(method, urlPath, { host = IP, headers = {}, body = null } = {}) {
  return new Promise((resolve) => {
    const all = { ...headers };
    if (body != null && all['Content-Length'] == null) all['Content-Length'] = Buffer.byteLength(body);
    const req = https.request({ host, port: PORT, method, path: urlPath, headers: all, rejectUnauthorized: false, agent: false }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        let json = null;
        try {
          json = JSON.parse(buf.toString('utf8'));
        } catch {
          // not JSON
        }
        resolve({ status: res.statusCode, headers: res.headers, buf, json });
      });
      res.on('error', (e) => resolve({ status: res.statusCode, error: e.message, buf: Buffer.concat(chunks) }));
    });
    req.on('error', (e) => resolve({ status: 0, error: e.message, buf: Buffer.alloc(0) }));
    if (body != null) req.write(body);
    req.end();
  });
}

/**
 * Two requests on one connection, the first with its body, sent at once: how
 * many answers came within {@code ms}. Two means the phone read the first
 * body to its end, which is what lets a browser see the first answer at all:
 * one that hangs up on a body it has not read sends a reset, and a browser,
 * still sending, loses the answer to it.
 */
function twoOnOne(head1, body, head2, ms) {
  return new Promise((resolve) => {
    let got = '';
    let done = false;
    const count = () => (got.match(/HTTP\/1\.1 \d{3} /g) || []).length;
    const end = (closed) => {
      if (done) return;
      done = true;
      resolve({ answers: count(), closed, got });
      s.destroy();
    };
    const s = tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, () => {
      s.write(head1);
      s.write(body);
      s.write(head2);
    });
    s.on('data', (d) => {
      got += d;
      if (count() >= 2) setTimeout(() => end(false), 100);
    });
    s.on('error', () => {});
    s.on('close', () => end(true));
    setTimeout(() => end(false), ms);
  });
}

/**
 * A download read slowly, so that it is still going when something on the
 * phone ends it: how much came, and how it ended. {@code drain()} reads the
 * rest as fast as it comes (what this computer had buffered), {@code end()}
 * lets it go from this end.
 */
function slowDownload(urlPath, { host = IP, headers = {} } = {}) {
  const c = { got: 0, ended: false, how: null, status: 0, fast: false };
  c.done = new Promise((resolve) => {
    const fin = (how) => {
      if (c.ended) return;
      c.ended = true;
      c.how = how;
      resolve(how);
    };
    const req = https.request({ host, port: PORT, path: urlPath, headers, rejectUnauthorized: false, agent: false }, (res) => {
      c.status = res.statusCode;
      res.on('data', (d) => {
        c.got += d.length;
        if (c.fast) return;
        res.pause();
        setTimeout(() => res.resume(), 200);
      });
      res.on('end', () => fin('end'));
      res.on('aborted', () => fin('aborted'));
      res.on('error', () => fin('error'));
      res.on('close', () => fin('close'));
    });
    req.on('error', () => fin('error'));
    c.end = () => req.destroy();
    req.end();
  });
  c.drain = () => {
    c.fast = true;
  };
  return c;
}

/** A ZIP's entries read back from its central directory, each CRC checked against its data. */
function readZipEntries(buf) {
  try {
    let eocd = -1;
    for (let i = buf.length - 22; i >= 0 && i >= buf.length - 65557; i--) {
      if (buf.readUInt32LE(i) === 0x06054b50) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) return { ok: false, error: 'no end record' };
    const count = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);
    const entries = {};
    for (let i = 0; i < count; i++) {
      if (buf.readUInt32LE(p) !== 0x02014b50) return { ok: false, error: `central entry ${i}` };
      const crc = buf.readUInt32LE(p + 16);
      const size = buf.readUInt32LE(p + 24);
      const nameLen = buf.readUInt16LE(p + 28);
      const extraLen = buf.readUInt16LE(p + 30);
      const commentLen = buf.readUInt16LE(p + 32);
      const local = buf.readUInt32LE(p + 42);
      const name = buf.subarray(p + 46, p + 46 + nameLen).toString('utf8');
      const dataAt = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const data = buf.subarray(dataAt, dataAt + size);
      if (!name.endsWith('/') && zlib.crc32(data) !== crc) return { ok: false, error: `crc of ${name}` };
      entries[name] = data;
      p += 46 + nameLen + extraLen + commentLen;
    }
    return { ok: true, entries, names: Object.keys(entries) };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

console.log("the phone's own files (Remote desktop's Files), through the door and the tunnel");
{
  const letIn = async () => said(await pairWith(await askCode())).token;
  await command(`phonefs ${PHONE_STORAGE}`, 'OK phonefs');
  const linkTrouble = events.filter((e) => e.linkFailed);
  const TOKEN = await letIn();
  const AUTH = { Authorization: `Bearer ${TOKEN}` };
  const CHANGE = { ...AUTH, Origin: BASE };
  const enc = encodeURIComponent;
  const q = (o) => {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(o)) for (const x of [].concat(v)) p.append(k, String(x));
    return p.toString();
  };
  const pGet = (op, o = {}, extra = {}) => phoneRequest('GET', `/pfs/${op}?${q(o)}`, { headers: { ...AUTH, ...extra } });
  const pPost = (op, form = {}, o = {}, headers = CHANGE) => phoneRequest('POST', `/pfs/${op}?${q(o)}`,
    { headers: { ...headers, ...FORM }, body: q(form) });
  const names = (r) => (r.json && r.json.entries ? r.json.entries.map((e) => e.n).sort() : []);
  const onPhone = (rel) => path.join(PHONE_STORAGE, rel);
  const transfers = async () => Number((((await command('phonefiles?', 'PHONEFILES ')) || 'PHONEFILES -1').split(' '))[1]);

  // Who gets in.
  const none = await phoneRequest('GET', `/pfs/list?path=${enc('/Download')}`);
  check("the phone's files with no token: 401, and not one name", none.status === 401 && !/notes/.test(none.buf.toString()), String(none.status));
  const madeUp = await phoneRequest('GET', `/pfs/list?path=${enc('/Download')}`, { headers: { Authorization: `Bearer ${'x'.repeat(43)}` } });
  check('…a made-up token: 401', madeUp.status === 401, String(madeUp.status));
  const noOrigin = await phoneRequest('POST', '/pfs/mkdir', { headers: { ...AUTH, ...FORM }, body: q({ path: '/Download/by-someone' }) });
  const otherSite = await phoneRequest('POST', '/pfs/mkdir', { headers: { ...AUTH, ...FORM, Origin: 'https://evil.example' }, body: q({ path: '/Download/by-someone' }) });
  check('…a change from anywhere but the page itself (no Origin, or another site\'s) is refused, token or not',
    noOrigin.status === 403 && otherSite.status === 403 && !fs.existsSync(onPhone('Download/by-someone')), `${noOrigin.status} ${otherSite.status}`);
  const byGet = await pGet('mkdir', { path: '/Download/by-get' });
  check('…and never by GET', byGet.status === 405 && !fs.existsSync(onPhone('Download/by-get')), String(byGet.status));
  const named = await phoneRequest('GET', `/pfs/list?path=${enc('/')}`, { headers: { ...AUTH, Host: 'phone.example:7443' } });
  check('…nor by a name rather than the address (DNS rebinding)', named.status === 421, String(named.status));

  // Looking.
  const info = await pGet('info');
  const ids = info.json && info.json.places ? info.json.places.map((p) => p.id) : [];
  check("places: the phone, and Android's own folders that are there (no Music here), no Trash",
    JSON.stringify(ids) === JSON.stringify(['phone', 'downloads', 'camera', 'pictures', 'documents', 'videos'])
      && info.json.home === '/' && info.json.free > 0 && info.json.total >= info.json.free, JSON.stringify(info.json));
  const top = await pGet('list', { path: '/' });
  check("the storage's top: every folder there, Android's too, nothing hidden",
    ['Android', 'DCIM', 'Documents', 'Download', 'Movies', 'Pictures', 'WhatsApp'].every((n) => names(top).includes(n)) && top.json.w === 1,
    names(top).join());
  const climbed = await pGet('list', { path: '/Download/../../../..' });
  check('.. stops at the top of the phone\'s storage', climbed.json && climbed.json.path === '/' && names(climbed).includes('Download'),
    JSON.stringify(climbed.json && climbed.json.path));
  const out1 = await pGet('get', { path: '/../phone-outside/secret.txt' });
  const out2 = await pGet('get', { path: '/Download/../../phone-outside/secret.txt' });
  const out3 = await pGet('get', { path: '/..\\phone-outside\\secret.txt' });
  check('a path that climbs out of the storage reaches nothing beside it (/.., and \\ on this computer)',
    [out1, out2, out3].every((r) => [400, 404].includes(r.status) && !r.buf.toString().includes('outside')), [out1, out2, out3].map((r) => r.status).join());
  const toDl = await pGet('list', { path: '/Documents/to-download' });
  const upLink = await pGet('list', { path: '/Documents/up' });
  const secret = await pGet('get', { path: '/Documents/secret' });
  check('links: an absolute one is the storage\'s own folder, one that climbs stops at the top, one to the computer leads nowhere',
    !linkTrouble.length && toDl.json && toDl.json.path === '/Download' && names(toDl).includes('notes.txt')
      && upLink.json && upLink.json.path === '/' && [400, 404].includes(secret.status) && !secret.buf.toString().includes('outside'),
    JSON.stringify({ linkTrouble, dl: toDl.json && toDl.json.path, up: upLink.json && upLink.json.path, secret: secret.status }));
  const androidDir = await pGet('list', { path: '/Android/data/com.example' });
  check("apps' folders under Android: seen, read only", androidDir.status === 200 && names(androidDir).includes('cache.txt') && androidDir.json.w === 0,
    JSON.stringify(androidDir.json));

  // Bringing back.
  const whole = await pGet('get', { path: '/Download/big.bin' });
  const part = await pGet('get', { path: '/Download/big.bin' }, { Range: 'bytes=100-199' });
  check('a file, whole and as a range', whole.status === 200 && whole.buf.equals(PHONE_BIG)
    && part.status === 206 && part.buf.equals(PHONE_BIG.subarray(100, 200)), `${whole.status} ${part.status}`);
  const tk = await pPost('ticket', { kind: 'get', p: '/Download/notes.txt' });
  const byTicket = await phoneRequest('GET', `/pfs/get?k=${tk.json && tk.json.k}&dl=1`);
  check('a ticket: the browser downloads the file by itself, as a download, with no token',
    byTicket.status === 200 && byTicket.buf.toString() === 'notes from the phone\n' && /^attachment; filename="notes.txt"/.test(byTicket.headers['content-disposition'] || ''),
    `${byTicket.status} ${byTicket.headers['content-disposition']}`);
  const zt = await pPost('ticket', { kind: 'zip', p: ['/Documents/Trip', '/Download/notes.txt'], name: 'From the phone.zip' });
  const zr = await phoneRequest('GET', `/pfs/zip?k=${zt.json && zt.json.k}`);
  const zip = zr.status === 200 ? readZipEntries(zr.buf) : { ok: false, error: String(zr.status) };
  check('a folder and a file as one ZIP, its length known first, read back byte for byte',
    zip.ok && Number(zr.headers['content-length']) === zr.buf.length && zip.entries['Trip/day one.txt']?.toString() === 'day one\n'
      && zip.entries['notes.txt']?.toString() === 'notes from the phone\n' && /From%20the%20phone\.zip/.test(zr.headers['content-disposition'] || ''),
    zip.error || JSON.stringify(zip.names));

  // Sending.
  const one = Buffer.from('sent from the computer\n');
  const mtime = Date.UTC(2025, 1, 2, 3, 4, 6);
  const p1 = await phoneRequest('PUT', `/pfs/put?${q({ path: '/Download/sent.txt', size: one.length, mtime })}`, { headers: CHANGE, body: one });
  check('a file sent into the open folder lands there, with its own time', p1.status === 200 && p1.json.done
    && fs.readFileSync(onPhone('Download/sent.txt')).equals(one) && Math.abs(fs.statSync(onPhone('Download/sent.txt')).mtimeMs - mtime) < 2000,
    JSON.stringify(p1.json));
  const big = crypto.randomBytes(5 * 1024 * 1024 + 3);
  const bq = { path: '/Movies/film.bin', size: big.length, mtime };
  const half = 2 * 1024 * 1024 + 1;
  const a1 = await phoneRequest('PUT', `/pfs/put?${q({ ...bq, off: 0 })}`, { headers: { ...CHANGE, 'Content-Length': half }, body: big.subarray(0, half) });
  const have = await pGet('part', bq);
  const a2 = await phoneRequest('PUT', `/pfs/put?${q({ ...bq, off: half })}`, { headers: CHANGE, body: big.subarray(half) });
  check('…a big one in two goes, as a cut and a resume: what arrived kept out of sight, then whole in its place',
    a1.json && a1.json.done === false && have.json && have.json.have === half && !names(await pGet('list', { path: '/Movies' })).some((n) => n.startsWith('.lodpart'))
      && a2.json && a2.json.done && fs.readFileSync(onPhone('Movies/film.bin')).equals(big), JSON.stringify([a1.json, have.json, a2.json]));
  // The same big file again (dropped twice, then Replace): the page asks
  // first and sends nothing. One sent anyway is read to its end, so that its
  // answer arrives: a browser reads no answer while it is still sending, and
  // a phone that hung up on the rest left it a reset (Edge: "Failed to fetch").
  const pre = await pGet('part', { ...bq, on: 'replace', fresh: 1 });
  const again = await phoneRequest('PUT', `/pfs/put?${q({ ...bq, off: 0, on: 'replace' })}`, { headers: CHANGE, body: big });
  check('…the same big file again: asked first, found there; sent anyway, its answer arrives',
    pre.status === 200 && pre.json && pre.json.same === true && again.status === 200 && again.json && again.json.same === true
      && fs.readFileSync(onPhone('Movies/film.bin')).equals(big), JSON.stringify([pre.status, pre.json, again.status, again.json || again.error]));
  const head = (line, extra = '') => `${line} HTTP/1.1\r\nHost: ${IP}:${PORT}\r\nAuthorization: Bearer ${TOKEN}\r\nConnection: keep-alive\r\n${extra}\r\n`;
  const onOne = await twoOnOne(head(`PUT /pfs/put?${q({ ...bq, off: 0, on: 'replace' })}`, `Origin: ${BASE}\r\nContent-Length: ${big.length}\r\n`),
    big, head('GET /pfs/list?path=%2FMovies'), 5000);
  check('…read to its end, which is what lets a browser see that answer: the same connection carries the next request',
    onOne.answers === 2 && /"same":true/.test(onOne.got) && /"n":"film\.bin"/.test(onOne.got),
    `${onOne.answers} answers, closed: ${onOne.closed}, ${onOne.got.replace(/\r\n/g, ' ').slice(0, 300)}`);
  // What a put would refuse at once is said when asked, before a byte goes.
  const noRoom = await pGet('part', { path: '/Movies/huge.bin', size: 2 ** 52, mtime, on: 'replace', fresh: 1 });
  const readOnly = await pGet('part', { path: '/Android/data/com.example/big.bin', size: big.length, mtime, on: 'replace', fresh: 1 });
  const wentAway = await pGet('part', { path: '/Movies/gone/big.bin', size: big.length, mtime, on: 'replace', fresh: 1 });
  check('…and asked first, what the phone would refuse is said before a byte is sent: no room, apps\' folders, a folder that went',
    noRoom.status === 507 && noRoom.json && noRoom.json.error === 'full' && readOnly.status === 403 && wentAway.status === 404,
    `${noRoom.status} ${readOnly.status} ${wentAway.status}`);
  /** A batch's body, as transfer.js makes one: a record for each [path under the folder, text]. */
  const batchOf = (pairs) => Buffer.concat([
    ...pairs.map(([rel, text]) => {
      const name = Buffer.from(rel);
      const data = Buffer.from(text);
      const head = Buffer.alloc(3 + name.length + 16);
      head[0] = 1;
      head.writeUInt16BE(name.length, 1);
      name.copy(head, 3);
      head.writeBigUInt64BE(BigInt(data.length), 3 + name.length);
      head.writeBigUInt64BE(BigInt(mtime), 11 + name.length);
      return Buffer.concat([head, data]);
    }),
    Buffer.from([0]),
  ]);
  const batch = batchOf([['Album/one.txt', 'one'], ['Album/deeper/two.txt', 'two'], ['../escape.txt', 'no'], ['Album/after.txt', 'yes']]);
  const bt = await phoneRequest('POST', `/pfs/batch?${q({ dir: '/Pictures', on: 'rename' })}`, { headers: { ...CHANGE, 'Content-Type': 'application/octet-stream' }, body: batch });
  check('many small files in one request, folders made as they come; one that climbs out refused, the rest landing',
    bt.json && bt.json.done === 3 && bt.json.failed.length === 1 && fs.readFileSync(onPhone('Pictures/Album/deeper/two.txt'), 'utf8') === 'two'
      && fs.readFileSync(onPhone('Pictures/Album/after.txt'), 'utf8') === 'yes' && !fs.existsSync(path.join(PHONE_STORAGE, 'escape.txt'))
      && !fs.existsSync(path.join(tmp, 'escape.txt')), JSON.stringify(bt.json));
  // Two different files of the same size and time. The quick check takes the
  // second for the first (a retry after a cut relies on it); Replace chosen
  // (force) sends and writes it, one file to a request and many alike. Until
  // 2026-11-21 Replace kept the first, without a word.
  const twinQ = { path: '/Movies/twin.bin', size: 1024 * 1024 + 7, mtime };
  const twinA = crypto.randomBytes(twinQ.size);
  const twinB = crypto.randomBytes(twinQ.size);
  await phoneRequest('PUT', `/pfs/put?${q({ ...twinQ, off: 0 })}`, { headers: CHANGE, body: twinA });
  const quick = await pGet('part', { ...twinQ, on: 'replace', fresh: 1 });
  const forcedAsk = await pGet('part', { ...twinQ, on: 'replace', fresh: 1, force: 1 });
  const forcedPut = await phoneRequest('PUT', `/pfs/put?${q({ ...twinQ, off: 0, on: 'replace', force: 1 })}`, { headers: CHANGE, body: twinB });
  const BATCH = { ...CHANGE, 'Content-Type': 'application/octet-stream' };
  await phoneRequest('POST', `/pfs/batch?${q({ dir: '/Pictures', on: 'replace' })}`, { headers: BATCH, body: batchOf([['twin.txt', 'aaa']]) });
  const quickBatch = await phoneRequest('POST', `/pfs/batch?${q({ dir: '/Pictures', on: 'replace' })}`, { headers: BATCH, body: batchOf([['twin.txt', 'bbb']]) });
  const keptFirst = fs.readFileSync(onPhone('Pictures/twin.txt'), 'utf8');
  const forcedBatch = await phoneRequest('POST', `/pfs/batch?${q({ dir: '/Pictures', on: 'replace', force: 1 })}`, { headers: BATCH, body: batchOf([['twin.txt', 'bbb']]) });
  check('two different files of the same size and time: the quick check takes the second for the first; Replace (force) writes it, alone and in a batch',
    quick.json && quick.json.same === true && forcedAsk.json && forcedAsk.json.same !== true
      && forcedPut.json && forcedPut.json.done === true && forcedPut.json.same !== true && fs.readFileSync(onPhone('Movies/twin.bin')).equals(twinB)
      && quickBatch.json && quickBatch.json.same === 1 && keptFirst === 'aaa'
      && forcedBatch.json && forcedBatch.json.done === 1 && fs.readFileSync(onPhone('Pictures/twin.txt'), 'utf8') === 'bbb',
    JSON.stringify([quick.json, forcedAsk.json, forcedPut.json, quickBatch.json, keptFirst, forcedBatch.json]));
  const intoApp = await phoneRequest('PUT', `/pfs/put?${q({ path: '/Android/data/com.example/new.txt', size: 2, mtime })}`, { headers: CHANGE, body: Buffer.from('no') });
  const mkApp = await pPost('mkdir', { path: '/android/data/made' });
  check("nothing goes into apps' folders under Android, whatever the case of the name",
    intoApp.status === 403 && intoApp.json.error === 'protected' && mkApp.status === 403 && !fs.existsSync(onPhone('Android/data/com.example/new.txt'))
      && !fs.existsSync(onPhone('Android/data/made')), `${intoApp.status} ${mkApp.status}`);
  const sneak = await phoneRequest('POST', `/pfs/batch?${q({ dir: '/', on: 'replace' })}`, { headers: { ...CHANGE, 'Content-Type': 'application/octet-stream' },
    body: batchOf([['Android/data/com.example/sneak.txt', 'no'], ['android/data/sneak two.txt', 'no'], ['Documents/fine.txt', 'yes']]) });
  check('…nor by many files sent to the top with Android/ in their paths; the rest land',
    sneak.json && sneak.json.done === 1 && sneak.json.failed.length === 2 && sneak.json.failed.every((f) => f[1] === 'protected')
      && !fs.existsSync(onPhone('Android/data/com.example/sneak.txt')) && !fs.existsSync(onPhone('Android/data/sneak two.txt'))
      && fs.readFileSync(onPhone('Documents/fine.txt'), 'utf8') === 'yes', JSON.stringify(sneak.json));
  fs.mkdirSync(onPhone('Documents/Download'), { recursive: true });
  fs.writeFileSync(onPhone('Documents/Download/mine.txt'), 'mine');
  const over = await pPost('move', { p: ['/Documents/Download'], dir: '/', on: 'replace' });
  check("a move told to replace never replaces one of Android's own folders",
    over.json && over.json.failed[0] && over.json.failed[0][1] === 'protected' && fs.existsSync(onPhone('Download/notes.txt'))
      && fs.existsSync(onPhone('Documents/Download/mine.txt')), JSON.stringify(over.json));

  // Folders and names; deleting is for good, and Android's own folders stay.
  const mk = await pPost('mkdir', { path: '/Documents/New folder', on: 'rename' });
  const rn = await pPost('rename', { path: '/Documents/New folder', name: 'Plans' });
  const mv = await pPost('move', { p: ['/Documents/report.txt'], dir: '/Documents/Plans' });
  const inside = await pPost('move', { p: ['/Documents/Plans'], dir: '/documents/plans' });
  check('a new folder, renamed, a file moved into it; a folder never into itself, whatever the case',
    mk.json && mk.json.path === '/Documents/New folder' && rn.json && rn.json.path === '/Documents/Plans'
      && mv.json && mv.json.moved.length === 1 && fs.existsSync(onPhone('Documents/Plans/report.txt'))
      && inside.json && inside.json.failed[0] && inside.json.failed[0][1] === 'inside', JSON.stringify([mk.json, rn.json, mv.json, inside.json]));
  const trash = await pPost('trash', { p: '/Documents/Plans/report.txt' });
  const del = await pPost('delete', { p: ['/Documents/Plans/report.txt', '/Pictures/Album'] });
  check("no Trash on the phone (the page asks, then deletes for good): deleted, folders and all",
    trash.status === 404 && del.json && del.json.deleted.length === 2 && !fs.existsSync(onPhone('Documents/Plans/report.txt'))
      && !fs.existsSync(onPhone('Pictures/Album')), JSON.stringify([trash.status, del.json]));
  const kept = [];
  for (const p of ['/', '/Download', '/DCIM', '/DCIM/Camera', '/android', '/Android/data', '/Android/data/com.example/cache.txt', '/Pictures']) {
    const d = await pPost('delete', { p });
    const r = await pPost('rename', { path: p, name: 'gone' });
    if (!(d.json && d.json.failed[0] && d.json.failed[0][1] === 'protected' && r.status === 403)) kept.push(`${p}: ${JSON.stringify([d.json, r.status])}`);
  }
  check("the storage itself, Android's own folders, the camera's and apps' files cannot be deleted or renamed from a browser",
    !kept.length && fs.existsSync(onPhone('Download/notes.txt')) && fs.existsSync(onPhone('Android/data/com.example/cache.txt')), kept.join(' | '));
  const anyTop = await pPost('rename', { path: '/WhatsApp', name: 'Chats' });
  check('…while a folder of the person\'s own at the top can be', anyTop.status === 200 && fs.existsSync(onPhone('Chats/Media/hello.txt')), String(anyTop.status));

  // Where it may be asked from, and when.
  const TUN = 'quiet-test-words.trycloudflare.com';
  const TBASE = `https://${TUN}`;
  await command(`tunnel ${TUN}`, 'OK tunnel');
  const tunHead = { Host: TUN, 'cf-connecting-ip': '203.0.113.9' };
  const viaTun = (method, p, headers = {}, body = null) => phoneRequest(method, p, { host: '127.0.0.1', headers: { ...tunHead, ...headers }, body });
  const tList = await viaTun('GET', `/pfs/list?path=${enc('/Download')}`, AUTH);
  const tMk = await viaTun('POST', '/pfs/mkdir', { ...AUTH, ...FORM, Origin: TBASE }, q({ path: '/Download/from far away' }));
  const tLinux = await viaTun('GET', `/fs/list?path=${enc('/')}`, AUTH);
  const tPages = {};
  for (const p of ['/phone-files.js', '/phone-files.css', '/files.js', '/fileicons.js', '/transfer.js', '/peek.js', '/files.css', '/index.html', '/viewer.js']) {
    tPages[p] = (await viaTun('GET', p)).status;
  }
  check("through the Cloudflare tunnel (Remote desktop's): the phone's files, looked at and changed from the page's own origin",
    tList.status === 200 && names(tList).includes('notes.txt') && tMk.status === 200 && fs.existsSync(onPhone('Download/from far away')),
    `${tList.status} ${tMk.status}`);
  check("…with the page's Files, and still nothing of Linux's: its files (/fs/), its page",
    tLinux.status === 403 && ['/phone-files.js', '/phone-files.css', '/files.js', '/fileicons.js', '/transfer.js', '/peek.js', '/files.css'].every((p) => tPages[p] === 200)
      && tPages['/index.html'] === 404 && tPages['/viewer.js'] === 404, JSON.stringify({ fs: tLinux.status, tPages }));
  await command('tunnel none', 'OK tunnel');
  await command('linux off', 'OK linux');
  const lOff = await pGet('list', { path: '/Download' });
  await command('linux on', 'OK linux');
  check("Linux's switch off, the door kept for Remote desktop: the phone's files still given", lOff.status === 200, String(lOff.status));
  await command('phone off', 'OK phone');
  const rOff = await pGet('list', { path: '/Download' });
  const rOffTicket = await phoneRequest('GET', `/pfs/get?k=${tk.json.k}`);
  await command('phone on', 'OK phone');
  check('Remote desktop off: the phone\'s files refused, and said so, even by a ticket', rOff.status === 403 && rOff.json && rOff.json.error === 'off'
    && rOffTicket.status === 403, `${rOff.status} ${rOffTicket.status}`);
  await command('phonefsstate noaccess', 'OK phonefsstate');
  const noAccess = await pGet('info');
  await command('phonefsstate unavailable', 'OK phonefsstate');
  const unavailable = await pGet('list', { path: '/' });
  await command('phonefsstate ready', 'OK phonefsstate');
  check("no All files access yet, or a build without it: said, as words the page knows",
    noAccess.status === 503 && noAccess.json.error === 'noaccess' && unavailable.status === 503 && unavailable.json.error === 'unavailable',
    `${noAccess.status} ${noAccess.buf && noAccess.buf.toString()} ${unavailable.status}`);

  // What ends a transfer under way: a sign-out, the browser's own Forget, the door stopping, Remote desktop going off.
  const dlTicket = async (token) => {
    const r = await phoneRequest('POST', '/pfs/ticket', { headers: { Authorization: `Bearer ${token}`, Origin: BASE, ...FORM }, body: q({ kind: 'get', p: '/Download/big.bin' }) });
    return r.json && r.json.k;
  };
  let d = slowDownload(`/pfs/get?k=${await dlTicket(TOKEN)}`);
  await until(() => d.got > 0, 5000);
  const counted = await transfers();
  await command('droplinux', 'OK droplinux');
  await sleep(1200);
  check("a download of the phone's under way is counted, and Linux's switch going off leaves it alone",
    counted === 1 && !d.ended && d.got > 0, JSON.stringify({ counted, ended: d.ended, how: d.how }));
  await command('signout', 'CODE ');
  d.drain();
  let how = await within(d.done, 15000);
  const afterSignOut = await phoneRequest('GET', `/pfs/get?k=${(await dlTicket(TOKEN)) || 'none'}`);
  const listAfter = await pGet('list', { path: '/' });
  check('a sign-out on the phone cuts a download of the phone\'s files under way; its token opens nothing after',
    d.ended && d.got < PHONE_BIG.length && listAfter.status === 401 && afterSignOut.status === 401,
    JSON.stringify({ how, got: d.got, list: listAfter.status }));
  check('…and none is counted after', (await until(async () => (await transfers()) === 0, 3000)) === true);

  const tokA = await letIn();
  const tokB = await letIn();
  const da = slowDownload(`/pfs/get?k=${await dlTicket(tokA)}`);
  const db = slowDownload(`/pfs/get?path=${enc('/Download/big.bin')}`, { headers: { Authorization: `Bearer ${tokB}` } });
  await until(async () => da.got > 0 && db.got > 0 && (await transfers()) === 2, 5000);
  const forget = await request('POST', '/forget', { headers: { ...FORM, Origin: BASE }, body: form({ token: tokA }) });
  da.drain();
  await within(da.done, 15000);
  await sleep(800);
  check("a browser's own Forget cuts its download of the phone's files, and not another browser's",
    forget.status === 200 && da.ended && da.got < PHONE_BIG.length && !db.ended && (await transfers()) === 1,
    JSON.stringify({ forget: forget.status, a: [da.ended, da.how], b: [db.ended, db.how] }));
  // Switched off on the phone, which tells the door nothing: its reaper sees it
  // within half a second (Host.phoneFiles is null), and the transfer ends.
  const offAt = Date.now();
  await command('phone off', 'OK phone');
  // The door's own count: what this computer had buffered still arrives after.
  const gone = await until(async () => (await transfers()) === 0, 3000, 50);
  const offAfter = gone ? Date.now() - offAt : null;
  db.drain();
  how = await within(db.done, 15000);
  await command('phone on', 'OK phone');
  check("Remote desktop switched off on the phone cuts a download of the phone's files under way, within a second",
    db.ended && offAfter !== null && offAfter < 1500 && db.got < PHONE_BIG.length, JSON.stringify({ how, got: db.got, offAfter }));
  const tokC = await letIn();
  const dc = slowDownload(`/pfs/get?k=${await dlTicket(tokC)}`);
  await until(() => dc.got > 0, 5000);
  await command('stop', 'OK stopped');
  dc.drain();
  how = await within(dc.done, 15000);
  check("the door stopping cuts a download of the phone's files under way", dc.ended && dc.got < PHONE_BIG.length, JSON.stringify({ how, got: dc.got }));
  await command('restart', 'OK restarted');

  // An upload is cut the same way, and what arrived stays out of sight for a resume.
  const tokU = await letIn();
  const upBig = crypto.randomBytes(6 * 1024 * 1024);
  const uq = { path: '/Download/cut.bin', size: upBig.length, mtime };
  const up = await new Promise((resolve) => {
    let mine = false;
    const req = https.request({ host: IP, port: PORT, method: 'PUT', path: `/pfs/put?${q({ ...uq, off: 0 })}`, rejectUnauthorized: false, agent: false,
      headers: { Authorization: `Bearer ${tokU}`, Origin: BASE, 'Content-Length': upBig.length } }, (res) => resolve({ status: res.statusCode }));
    // Closed by the phone, or by this end after a wait: which one is what is checked.
    req.on('socket', (s) => s.on('close', () => resolve({ status: 0, byPhone: !mine })));
    req.on('error', () => {});
    // A little, then nothing: still under way when the phone signs browsers out.
    req.write(upBig.subarray(0, 512 * 1024));
    setTimeout(async () => {
      await command('signout', 'CODE ');
      setTimeout(() => {
        mine = true;
        req.destroy();
      }, 4000);
    }, 600);
  });
  check("a sign-out cuts an upload of the phone's under way too, and nothing half sent shows in the folder",
    up.byPhone === true && !fs.existsSync(onPhone('Download/cut.bin'))
      && !names(await (async () => { const t = await letIn(); return phoneRequest('GET', `/pfs/list?path=${enc('/Download')}`, { headers: { Authorization: `Bearer ${t}` } }); })()).includes('cut.bin'),
    JSON.stringify(up));
}

// --node-only: the door from Node and no browser (a quick run, as for a check
// that must fail on an older copy: OADX_LAUNCHER_DIR).
if (args.includes('--node-only')) {
  console.log(`\n${passed} passed, ${failed} failed`);
  cleanup();
  process.exit(failed ? 1 : 0);
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

console.log("Remote desktop's page: the phone's own files");
{
  // A browser of its own, the phone's page in it: phone.html, the stand-in
  // phone's screen (frames from the harness), and Files over the folder the
  // Node checks above used (PHONE_STORAGE).
  const F = await launchBrowser();
  check("a browser for the phone's page", !!F);
  if (F) {
    await F.cdp('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: 1, mobile: false });
    const fdl = path.join(tmp, 'phone-page-downloads');
    fs.mkdirSync(fdl, { recursive: true });
    await F.cdp('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: fdl, eventsEnabled: true });
    const local = path.join(tmp, 'phone-page-local');
    fs.mkdirSync(local, { recursive: true });
    const fjs = F.js;
    const frows = () => fjs(`[...document.querySelectorAll('#files .fx-row .fx-name .n, #files .fx-tile .fx-tname')].map((e) => e.textContent)`);
    const frow = (name) => fjs(`(() => { const r = [...document.querySelectorAll('#files .fx-row, #files .fx-tile')].find((x) => (x.querySelector('.n') || x.querySelector('.fx-tname')).textContent === ${JSON.stringify(name)});
      if (!r) return null; const b = r.getBoundingClientRect(); return { x: Math.round(b.left + Math.min(160, b.width / 2)), y: Math.round(b.top + b.height / 2) }; })()`);
    const fmouse = async (x, y, { button = 'left', count = 1 } = {}) => {
      const buttons = { left: 1, right: 2 }[button];
      await F.cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
      for (let c = 1; c <= count; c++) {
        await F.cdp('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, buttons, clickCount: c });
        await F.cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, buttons: 0, clickCount: c });
      }
    };
    const fdrop = async (x, y, paths) => {
      const data = { items: [], files: paths, dragOperationsMask: 1 };
      await F.cdp('Input.dispatchDragEvent', { type: 'dragEnter', x, y, data });
      await F.cdp('Input.dispatchDragEvent', { type: 'dragOver', x, y, data });
      await sleep(250);
      const said = await fjs(`(() => { const o = document.querySelector('.drop'); return o && !o.hidden ? o.querySelector('.drop-title').textContent : null; })()`);
      await F.cdp('Input.dispatchDragEvent', { type: 'drop', x, y, data });
      return said;
    };
    const place = (label) => fjs(`(() => { const p = [...document.querySelectorAll('#files .place')].find((x) => x.textContent === ${JSON.stringify(label)}); if (p) p.click(); return !!p; })()`);
    const here = () => fjs(`(document.querySelector('#files .crumb.here') || {}).textContent || ''`);
    const frames = async () => {
      const l = await command('phoneframes?', 'PHONEFRAMES ');
      const [, sent, , keys] = (l || 'PHONEFRAMES -1 -1 -1').split(' ');
      return { sent: Number(sent), keys: Number(keys) };
    };
    await command(`phonefs ${PHONE_STORAGE}`, 'OK phonefs');
    await command('phone on', 'OK phone');
    await command('open', 'OK open');
    await F.cdp('Page.navigate', { url: `${BASE}/phone.html` });
    await clickThrough(F, BASE);
    await until(() => fjs(`!!document.getElementById('pair') && !document.getElementById('pair').hidden`), 8000);
    check("the phone's page before the code: no Files button", await fjs(`document.getElementById('files-btn').hidden === true`));
    const code = await askCode();
    let t = Date.now();
    await fjs(`(() => { document.getElementById('code').value = ${JSON.stringify(code)}; document.getElementById('pair').requestSubmit(); })()`);
    const screenUp = await until(() => fjs(`document.getElementById('cover').hidden === true`), 12000);
    const btn = await until(() => fjs(`document.getElementById('files-btn').hidden === false`), 6000);
    check("let in: the phone's screen on the page, and a Files button in its bar", !!screenUp && !!btn,
      JSON.stringify({ screenUp, btn, cover: await fjs(`document.getElementById('cover-words').textContent`) }));
    await F.shot('30-phone-page');

    // Picture size, in the bar: the hello says Full size; Half size goes to
    // the phone as half its screen and is kept for the next visit, and the
    // status line gives the picture's own size.
    const hello = evSince(t, (e) => e.phoneMsg && e.phoneMsg.t === 'hello');
    const tSize = Date.now();
    await fjs(`(() => { const s = document.getElementById('size'); s.value = '0.5'; s.dispatchEvent(new Event('change')); })()`);
    const sized = await until(() => evSince(tSize, (e) => e.phoneMsg && e.phoneMsg.t === 'size'), 3000);
    const sizeSaid = await fjs(`document.getElementById('status').textContent`);
    check("Picture size in the bar: Full size first; Half size goes to the phone as half its screen, and is kept; the picture's size in the status line",
      !!hello && hello.phoneMsg.scale === 1 && !!sized && sized.phoneMsg.scale === 0.5
        && (await fjs(`localStorage.getItem('lod.phoneSize')`)) === '0.5' && sizeSaid.includes(' · 1280 × 720 · '),
      JSON.stringify({ hello: hello && hello.phoneMsg, sized: sized && sized.phoneMsg, status: sizeSaid }));

    // Files: the places, and the picture let go of.
    t = Date.now();
    await fjs(`document.getElementById('files-btn').click()`);
    const opened = await until(() => fjs(`!!document.querySelector('#files.on') && document.querySelectorAll('#files .fx-row').length > 3`), 10000);
    const places = await fjs(`[...document.querySelectorAll('#files .place span')].map((e) => e.textContent)`);
    const rowsAtTop = await frows();
    check("Files: the phone's places down the side (the folders that are there), the phone's storage listed",
      !!opened && JSON.stringify(places) === JSON.stringify(['Phone', 'Downloads', 'Camera', 'Pictures', 'Documents', 'Movies'])
        && ['Android', 'DCIM', 'Download', 'Documents'].every((n) => rowsAtTop.includes(n)),
      JSON.stringify({ places, rows: rowsAtTop }));
    const off = await until(() => evSince(t, (e) => e.phonePicture === false), 3000);
    const f0 = await frames();
    await sleep(1200);
    const f1 = await frames();
    check("…and the page tells the phone it wants no picture while Files are up: no frames come",
      !!off && f1.sent - f0.sent <= 1, JSON.stringify({ off: !!off, f0, f1 }));
    check('…the address says Files, for a reload or a bookmark', /^#files/.test(await fjs('location.hash')), await fjs('location.hash'));
    await F.shot('31-phone-files');

    // Downloads: typing in Files' search is Files', never the phone's.
    await place('Downloads');
    await until(async () => (await frows()).includes('notes.txt'), 5000);
    const k0 = await frames();
    await fjs(`document.getElementById('fx-q').focus()`);
    await F.cdp('Input.insertText', { text: 'note' });
    await F.press('a', 'KeyA', 65, 'a');
    await F.press('Backspace', 'Backspace', 8);
    await sleep(600);
    const k1 = await frames();
    const filtered = await frows();
    check("typing in Files' search finds by name, and sends no key to the phone",
      JSON.stringify(filtered) === JSON.stringify(['notes.txt']) && k1.keys === k0.keys, JSON.stringify({ filtered, k0, k1 }));
    await fjs(`document.getElementById('fx-q').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);

    // Upload, Files…: into the folder open now.
    const picked = path.join(local, 'from the computer.txt');
    fs.writeFileSync(picked, 'picked on the computer\n');
    const input = await F.cdp('Runtime.evaluate', { expression: `document.querySelectorAll('#files input[type=file]')[0]` });
    const node = await F.cdp('DOM.describeNode', { objectId: input.result.result.objectId });
    await F.cdp('DOM.setFileInputFiles', { files: [picked], backendNodeId: node.result.node.backendNodeId });
    const uploaded = await until(() => fs.existsSync(path.join(PHONE_STORAGE, 'Download/from the computer.txt'))
      && fs.readFileSync(path.join(PHONE_STORAGE, 'Download/from the computer.txt'), 'utf8') === 'picked on the computer\n', 8000);
    check("Upload: the chosen file lands in the phone's folder open in Files, byte for byte", !!uploaded);
    const traySaid = await until(() => fjs(`(() => { const w = document.querySelector('.tray .job-where'); return w ? w.textContent : null; })()`), 4000);
    check("…the transfers' panel says where, in the phone's words", traySaid === 'to Downloads on the phone', String(traySaid));
    // A big file goes in pieces of 32 MB (a PUT each), as the Cloudflare tunnel needs: counted in the page.
    await fjs(`(() => { window.__puts = []; const o = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (m, u, ...rest) { if (m === 'PUT' && /pfs\\/put/.test(u)) window.__puts.push(String(u)); return o.call(this, m, u, ...rest); }; })()`);
    const film = path.join(local, 'big film.bin');
    const filmBytes = crypto.randomBytes(70 * 1024 * 1024 + 5);
    fs.writeFileSync(film, filmBytes);
    await F.cdp('DOM.setFileInputFiles', { files: [film], backendNodeId: node.result.node.backendNodeId });
    const filmLanded = await until(() => {
      const p = path.join(PHONE_STORAGE, 'Download/big film.bin');
      return fs.existsSync(p) && fs.statSync(p).size === filmBytes.length;
    }, 30000);
    const puts = await fjs('window.__puts');
    check('a big file goes in pieces of 32 MB, a PUT each, and lands whole',
      !!filmLanded && fs.readFileSync(path.join(PHONE_STORAGE, 'Download/big film.bin')).equals(filmBytes) && puts.length === 3
        && puts.map((u) => new URL(u, BASE).searchParams.get('off')).join() === `0,${32 * 1024 * 1024},${64 * 1024 * 1024}`,
      JSON.stringify({ landed: !!filmLanded, puts: puts.map((u) => new URL(u, BASE).searchParams.get('off')) }));
    fs.rmSync(path.join(PHONE_STORAGE, 'Download/big film.bin'), { force: true });

    // Files dropped onto the list.
    const dropped = path.join(local, 'dropped photo.png');
    fs.writeFileSync(dropped, crypto.randomBytes(300 * 1024));
    const list = await fjs(`(() => { const b = document.querySelector('#files .fx-scroll').getBoundingClientRect(); return { x: Math.round(b.left + b.width / 2), y: Math.round(b.top + b.height / 2) }; })()`);
    const dropSaid = await fdrop(list.x, list.y + 80, [dropped]);
    const dropLanded = await until(() => fs.existsSync(path.join(PHONE_STORAGE, 'Download/dropped photo.png'))
      && fs.readFileSync(path.join(PHONE_STORAGE, 'Download/dropped photo.png')).equals(fs.readFileSync(dropped)), 10000);
    check('a file dropped onto the list: said where it goes, and there', dropSaid === 'Drop to copy into Downloads' && !!dropLanded, String(dropSaid));

    // Downloads to this computer: a file, and a folder as one ZIP.
    await until(async () => (await frows()).includes('notes.txt'), 5000);
    const nr = await frow('notes.txt');
    await fmouse(nr.x, nr.y);
    await fjs(`document.querySelector('#files .sel-dl').click()`);
    const gotFile = await until(() => fs.existsSync(path.join(fdl, 'notes.txt')) && fs.readFileSync(path.join(fdl, 'notes.txt'), 'utf8') === 'notes from the phone\n', 10000);
    check("Download: the phone's file, where this browser saves downloads", !!gotFile);
    await place('Documents');
    await until(async () => (await frows()).includes('Trip'), 5000);
    const tr = await frow('Trip');
    await fmouse(tr.x, tr.y);
    await fjs(`document.querySelector('#files .sel-dl').click()`);
    const zipAt = path.join(fdl, 'Trip.zip');
    const gotZip = await until(() => fs.existsSync(zipAt) && !fs.readdirSync(fdl).some((n) => n.endsWith('.crdownload')), 10000);
    const zread = gotZip ? readZipEntries(fs.readFileSync(zipAt)) : { ok: false, error: 'no zip' };
    check('…a folder: one ZIP of everything in it, read back', zread.ok && zread.entries['Trip/day two.txt']?.toString() === 'day two\n', zread.error || JSON.stringify(zread.names));

    // Deleting: no Trash on the phone. The menu has one Delete, which asks, then deletes for good.
    await place('Downloads');
    await until(async () => (await frows()).includes('sent.txt'), 5000);
    const sr = await frow('sent.txt');
    await fmouse(sr.x, sr.y);
    await fmouse(sr.x, sr.y, { button: 'right' });
    const menuItems = await until(() => fjs(`(() => { const m = document.querySelector('.fx-menu'); return m ? [...m.querySelectorAll('.lbl')].map((e) => e.textContent) : null; })()`), 3000);
    check("the menu of a file of the phone's: Delete, and nothing of a Trash",
      !!menuItems && menuItems.includes('Delete') && !menuItems.some((x) => /Trash|for good/.test(x)), JSON.stringify(menuItems));
    await F.press('Escape', 'Escape', 27);
    await fmouse(sr.x, sr.y);
    await F.press('Delete', 'Delete', 46);
    const asked = await until(() => fjs(`(() => { const d = document.querySelector('dialog.fx-dialog[open]'); return d ? d.querySelector('h2').textContent + ' | ' + d.querySelector('p').textContent : null; })()`), 4000);
    check('Delete: asked first, in plain words', asked === 'Delete “sent.txt”? | “sent.txt” is deleted from the phone, and cannot be brought back.', String(asked));
    await F.shot('32-phone-delete');
    await fjs(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Delete').click()`);
    check('…Delete: gone from the phone', !!(await until(() => !fs.existsSync(path.join(PHONE_STORAGE, 'Download/sent.txt')), 5000)));

    // Apps' folders: looked at, never changed.
    await fjs(`(() => { location.hash = ''; })()`);
    await place('Phone');
    await until(async () => (await frows()).includes('Android'), 5000);
    const ar = await frow('Android');
    await fmouse(ar.x, ar.y, { count: 2 });
    await until(() => here().then((h) => h === 'Android'), 5000);
    const roNow = await until(() => fjs(`(() => { const s = document.querySelector('#files .fx-status'); return s && /Read only/.test(s.textContent) && document.querySelector('#files .fx-primary').disabled; })()`), 4000);
    check("Android's folder in Files: read only, Upload off", !!roNow, await fjs(`document.querySelector('#files .fx-status').textContent`));

    // Screen: back to the phone, the picture asked for again.
    t = Date.now();
    const s0 = await frames();
    await fjs(`document.querySelector('#files .fx-desk').click()`);
    const back = await until(() => evSince(t, (e) => e.phonePicture === true), 3000);
    await until(async () => (await frames()).sent > s0.sent + 5, 4000);
    const s1 = await frames();
    check('Screen: Files close, the phone is asked for the picture again, and frames come',
      !!back && s1.sent > s0.sent + 5 && (await fjs(`!document.querySelector('#files.on') && !document.body.classList.contains('files-on')`)) && !/^#files/.test(await fjs('location.hash')),
      JSON.stringify({ back: !!back, s0, s1 }));
    // A file dropped on the phone's screen goes into Downloads, as scrcpy's does.
    const onScreen = path.join(local, 'onto the screen.txt');
    fs.writeFileSync(onScreen, 'dropped on the phone\'s screen\n');
    const screenSaid = await fdrop(640, 360, [onScreen]);
    const screenLanded = await until(() => fs.existsSync(path.join(PHONE_STORAGE, 'Download/onto the screen.txt')), 8000);
    check("a file dropped on the phone's screen: said, and into the phone's Downloads", screenSaid === 'Drop to copy into Downloads on the phone' && !!screenLanded, String(screenSaid));
    await sleep(600);
    await F.shot('33-phone-screen-drop');

    // No access to the phone's storage yet: Files says what to do on the phone.
    await command('phonefsstate noaccess', 'OK phonefsstate');
    await fjs(`document.getElementById('files-btn').click()`);
    const noAccess = await until(() => fjs(`(() => { const e = document.querySelector('#files .fx-empty h3'); return e && !document.querySelector('#files .fx-empty').hidden ? e.textContent + ' | ' + document.querySelector('#files .fx-empty p').textContent : null; })()`), 6000);
    check('no access to all files on the phone: Files says what to allow, and where',
      noAccess === 'LinuxOnDroid needs access to all files | On the phone, open LinuxOnDroid › Settings › Remote desktop and choose Allow access to all files. Then choose Try again here.', String(noAccess));
    await F.shot('34-phone-noaccess');
    await command('phonefsstate ready', 'OK phonefsstate');
    await fjs(`[...document.querySelectorAll('#files .fx-empty button')].find((b) => b.textContent === 'Try again').click()`);
    check('…and Try again shows the files once allowed', !!(await until(() => fjs(`document.querySelectorAll('#files .fx-row').length > 0`), 6000)));

    // Signed out on the phone with Files up: the page asks for the code, Files and the button gone.
    await command('signout', 'CODE ');
    await until(() => fjs(`!document.getElementById('pair').hidden`), 8000);
    check('signed out on the phone: the code is asked for again, Files closed and the button gone',
      (await fjs(`!document.getElementById('pair').hidden && !document.querySelector('#files.on') && document.getElementById('files-btn').hidden`)) === true,
      JSON.stringify({ pair: await fjs(`!document.getElementById('pair').hidden`), files: await fjs(`!!document.querySelector('#files.on')`) }));
    const code2 = await askCode();
    const tAgain = Date.now();
    await fjs(`(() => { document.getElementById('code').value = ${JSON.stringify(code2)}; document.getElementById('pair').requestSubmit(); })()`);
    check('…the code again: the Files button is back', !!(await until(() => fjs(`document.getElementById('files-btn').hidden === false`), 8000)));
    const helloAgain = await until(() => evSince(tAgain, (e) => e.phoneMsg && e.phoneMsg.t === 'hello'), 8000);
    check('…and the phone opens again at the Picture size chosen: Half size in the hello and in the bar',
      !!helloAgain && helloAgain.phoneMsg.scale === 0.5 && (await fjs(`document.getElementById('size').value`)) === '0.5',
      JSON.stringify({ hello: helloAgain && helloAgain.phoneMsg, bar: await fjs(`document.getElementById('size').value`) }));
    await fjs(`(() => { const s = document.getElementById('size'); s.value = '1'; s.dispatchEvent(new Event('change')); })()`);
    check("no script errors on the phone's page", F.pageErrors.length === 0, F.pageErrors.slice(0, 3).join(' | '));

    // The page from anywhere (openandroiddex-remote/web) runs this phone.js with no door behind it.
    const A = await F.newTab('about:blank');
    await A.cdp('Page.addScriptToEvaluateOnNewDocument', { source: `window.PHONE_SOCKET = () => new WebSocket('wss://' + location.host + '/phone?t=none');` });
    await A.cdp('Page.navigate', { url: `${BASE}/phone.html` });
    await until(() => A.js(`document.readyState === 'complete'`), 8000);
    await sleep(1500);
    const anywhere = await A.js(`({ btn: document.getElementById('files-btn').hidden, asked: performance.getEntriesByType('resource').filter((e) => /pfs\\/|phone-files\\.js|files\\.js/.test(e.name)).map((e) => e.name) })`);
    check('the page from anywhere (no door): no Files button, and nothing of Files asked for', anywhere.btn === true && anywhere.asked.length === 0, JSON.stringify(anywhere));
    check('…and no script errors there', A.pageErrors.length === 0, A.pageErrors.slice(0, 3).join(' | '));
    A.close();
    await F.quit();
    F.close();
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
