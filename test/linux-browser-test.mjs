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
//   node test/linux-browser-test.mjs --device <serial> [--size 1920x1080]
//
// …the same on a real phone, over its Wi-Fi: the server, LinuxBrowserPeek,
// LinuxStream and the phone's hardware encoder, run by app_process as the
// shell user (test/linux-browser/PhoneHarness.java). Nothing is installed and
// no app is touched; the desktop is test/usb-desktop/StandinXvnc, changing
// every pixel of every frame. Says what each quality gets: frames a second,
// bitrate, the phone's encode time, this computer's decode time.
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
const LAUNCHER = path.join(ROOT, 'openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher');
const ASSETS = path.join(ROOT, 'openandroiddex-launcher/app/src/main/assets/linux/browser');
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
  ...['LinuxBrowser', 'LinuxBrowserCert', 'LinuxBrowserServer', 'LinuxStreamSocket', 'LinuxStreamAudio', 'LinuxAudioLag', 'LinuxFiles']
    .map((c) => path.join(LAUNCHER, `${c}.java`)),
  path.join(HERE, 'linux-browser/DexLog.java'),
  path.join(HERE, 'linux-browser/BrowserHarness.java'),
  path.join(HERE, 'linux-browser/ShaperCheck.java'),
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
  const held = await Promise.all(Array.from({ length: 8 }, () => rawConnect()));
  await sleep(300);
  const ninth = await rawRequest(`GET / HTTP/1.1\r\nHost: ${IP}:${PORT}\r\n\r\n`, 3000);
  check('eight connections from one address that say nothing: a ninth is closed unread', ninth.closed && !ninth.data, JSON.stringify(ninth));
  held.forEach((h) => h.destroy());
  await sleep(500);
  check('…and once they go, the address is answered again', (await request('GET', '/', { plain: true })).status === 200);

  await command('timeouts 1500 75000', 'OK timeouts');
  const slow = await trickle(8000);
  check('a request that comes a byte at a time is closed at its deadline, unanswered',
    slow.after >= 1000 && slow.after < 4000 && !slow.got, JSON.stringify(slow));
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
function wsConnect({ origin = BASE, host = `${IP}:${PORT}`, token = null, plain = false } = {}) {
  return new Promise((resolve) => {
    const where = token == null ? '/stream' : `/stream?t=${encodeURIComponent(token)}`;
    const opened = () => {
      const key = crypto.randomBytes(16).toString('base64');
      s.write(`GET ${where} HTTP/1.1\r\nHost: ${host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n${origin ? `Origin: ${origin}\r\n` : ''}\r\n`);
    };
    const s = plain ? net.connect({ host: IP, port: PORT }, opened) : tls.connect({ host: IP, port: PORT, rejectUnauthorized: false }, opened);
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
async function launchBrowser() {
  const exe = findBrowser();
  if (!exe) return null;
  const cdpPort = await freePort();
  const profile = path.join(tmp, `profile-${cdpPort}`);
  const proc = spawn(exe, [
    '--headless=new', `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${profile}`, '--no-first-run',
    '--no-default-browser-check', '--disable-extensions', '--window-size=1600,900', 'about:blank',
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
  await sleep(1500);
  const px = await js(`(() => {
    const c = document.getElementById('screen');
    const g = c.getContext('2d');
    const d = g.getImageData(0, 0, c.width, c.height).data;
    let lit = 0, n = 0;
    for (let i = 0; i < d.length; i += 4 * 997) { n++; if (d[i] + d[i + 1] + d[i + 2] > 60) lit++; }
    return { w: c.width, h: c.height, lit, n };
  })()`);
  check('decoded and drawn: the canvas is the desktop\'s size and the picture is on it', px.w === VW && px.h === VH && px.lit > px.n * 0.5, JSON.stringify(px));
  check('…one pixel for one, not smoothed', !(await js(`document.getElementById('screen').classList.contains('smooth')`)));
  // The bar is down for its first 3.2 s: looked at before the seconds counted below.
  check('the bar comes down for a moment when the picture first shows', await js(`document.body.classList.contains('bar-on')`));
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
  await until(() => js(`document.getElementById('screen').width === 1920`), 3000);
  check('…and the picture follows', (await js(`document.getElementById('screen').width + 'x' + document.getElementById('screen').height`)) === '1920x1080');
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
  check('typed without https again: the plain page finds the secure one works now, and goes there by itself, to the desktop',
    !!back, await js('location.href'));
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

  t = Date.now();
  say('signout');
  await until(() => visible('pair'), 4000);
  check('signed out on the phone: the page is told at once, asks for the new code, and says why',
    (await js(`document.getElementById('pair-error').textContent`)).startsWith('This browser was signed out'));
  check('…and forgets the old token', !(await js(`localStorage.getItem('lod.token')`)));
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
    await js(`document.getElementById('ended-title').textContent + ' / ' + document.getElementById('wait-title').textContent + ' / ' + document.body.className`));
  check('no script errors in the new tab', fresh.pageErrors.length === 0, fresh.pageErrors.slice(0, 3).join(' | '));
  await fresh.cdp('Page.navigate', { url: 'about:blank' });
  fresh.close();
  await js(`document.getElementById('ended-go').click()`);
  await until(isUp, 6000);
  check('Show the desktop here: the first tab has the desktop back', await isUp());

  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 800, y: 1 });
  await until(() => js(`document.body.classList.contains('bar-on')`), 2000);
  await js(`document.getElementById('more').click()`);
  await shot('11-menu');
  lastTokens = null;
  say('tokens');
  const before = await until(() => lastTokens, 2000);
  await js(`document.getElementById('m-forget').click()`);
  await until(() => visible('pair'), 4000);
  lastTokens = null;
  say('tokens');
  const after = await until(() => lastTokens !== null ? { n: lastTokens } : null, 2000);
  check('Forget this browser: the phone lets go of its token, and the page asks for the code', after && after.n === before - 1 && (await visible('pair')), `${before} → ${after && after.n}`);
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
      'LinuxAudio', 'LinuxAudioLag', 'DexLog', 'LinuxBrowser', 'LinuxBrowserCert', 'LinuxBrowserServer', 'LinuxBrowserPeek', 'LinuxFiles']
      .map((c) => path.join(LAUNCHER, `${c}.java`)),
    path.join(HERE, 'linux-browser/PhoneHarness.java'),
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
  const follows = await until(() => b.js(`document.getElementById('screen').width === 1600 && document.getElementById('screen').height === 900`), 6000);
  check('…a new encoder at the new size, and the picture follows', !!follows);

  // A second window on the same desktop: the first is told, and does not take it back.
  const second = await b.newTab(`${base}/`);
  await until(() => second.js(`document.body && document.body.classList.contains('up')`), 20000);
  check('a second tab opens the same desktop with the same browser\'s token', await second.js(`document.body.classList.contains('up')`));
  const told = await until(() => b.js(`!document.getElementById('ended').hidden && document.getElementById('ended-title').textContent`), 6000);
  check('the first is told the desktop opened somewhere else (LinuxStream says so)', told === 'Linux opened somewhere else', String(told));
  await sleep(4000);
  check('…and neither takes it back from the other', (await second.js(`document.body.classList.contains('up')`)) && !(await b.js(`document.body.classList.contains('up')`)));
  await b.shot('device-03-replaced');

  // Signed out, then switched off, as LinuxService does both: from the main
  // thread, where Android allows no network. Both crashed LinuxOnDroid on
  // the S25 (NetworkOnMainThreadException), taking Linux with it.
  const ask = async (cmd, answer, ms = 6000) => {
    const t0 = Date.now();
    harness.stdin.write(`${cmd}\n`);
    const hit = await until(() => said.find((l) => l.at >= t0 && l.line.startsWith(answer)), ms);
    return hit ? hit.line : null;
  };
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

  check('no script errors in either tab', b.pageErrors.length === 0 && second.pageErrors.length === 0, [...b.pageErrors, ...second.pageErrors].slice(0, 3).join(' | '));
  second.close();
  await b.quit();
  b.close();
}
