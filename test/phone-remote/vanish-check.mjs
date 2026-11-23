// A computer that vanishes without a word (a tab killed, a lid shut, the
// network gone) holds the phone no longer than 90 s: its session ends, the
// phone's own sound comes back and its screen may sleep again. On a real
// phone, through the door: a browser of this script's own signs in with the
// door's code, opens the phone's screen, asks for the sound in it with the
// phone kept quiet, then says nothing more while it reads what comes, until
// the phone lets it go. Never sends a goodbye or closes.
//
//   node vanish-check.mjs <adb serial> <door address, https://ip:7443>
//
// Needs a debuggable build (run-as reads the code and opens the door's
// sign-in window for ten minutes, put back after) and the sound's
// permission for the quiet part. The browser signs itself out at the end.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import https from 'node:https';
import tls from 'node:tls';
import { join } from 'node:path';

const [serial, door] = process.argv.slice(2);
const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
const PKG = process.env.PKG || 'com.ccrstech.linuxondroid';
const sh = (cmd) => execFileSync(ADB, ['-s', serial, 'shell', cmd], { encoding: 'utf8' }).replace(/\r/g, '').trim();
const runas = (cmd) => sh(`run-as ${PKG} sh -c '${cmd}'`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (...a) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s`, ...a);
let failed = 0, passed = 0;
const check = (what, ok, detail = '') => { if (ok) passed++; else failed++; log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`); };
const muted = () => /Muted: true/.test(sh('dumpsys audio | grep -A1 "^- STREAM_MUSIC:"'));
const appLog = () => sh('logcat -d -v time -s OpenDeX:I');
const url = new URL(door);

function post(path, form) {
  return new Promise((res) => {
    const body = new URLSearchParams(form).toString();
    const req = https.request(`${door}${path}`, { method: 'POST', rejectUnauthorized: false,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body), Origin: door, 'User-Agent': 'vanish-check' } },
    (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => res({ status: r.statusCode, body: d })); });
    req.on('error', (e) => res({ status: 0, body: String(e) }));
    req.end(body);
  });
}

/** A client's text frame: masked, as a browser's. */
function textFrame(s) {
  const p = Buffer.from(s);
  const head = p.length < 126 ? Buffer.from([0x81, 0x80 | p.length]) : Buffer.from([0x81, 0x80 | 126, p.length >> 8, p.length & 0xff]);
  const mask = crypto.randomBytes(4);
  const out = Buffer.alloc(p.length);
  for (let i = 0; i < p.length; i++) out[i] = p[i] ^ mask[i & 3];
  return Buffer.concat([head, mask, out]);
}

const last = sh('logcat -d -v time -s OpenDeX:I | grep -E "(opened|closed) the screen" | tail -1');
if (/opened the screen/.test(last)) { console.log('someone has the phone open:', last); process.exit(2); }
const untilBefore = runas('cat files/browser/pairing-until');
const tokensBefore = runas('sha256sum files/browser/tokens').split(' ')[0];
const phoneNow = Number(sh('date +%s%3N'));
runas(`echo ${phoneNow + 600000} > files/browser/pairing-until`);
const code = runas('cat files/browser/code').split(' ')[0];
let token = null;
let sock = null;
try {
  const paired = await post('/pair', { code });
  token = JSON.parse(paired.body || '{}').token || null;
  check('signed in with the door\'s code', !!token, `${paired.status}`);
  if (!token) throw new Error('no token');
  sock = tls.connect({ host: url.hostname, port: Number(url.port), rejectUnauthorized: false });
  await new Promise((r, j) => { sock.once('secureConnect', r); sock.once('error', j); });
  const key = crypto.randomBytes(16).toString('base64');
  sock.write(`GET /phone?t=${encodeURIComponent(token)} HTTP/1.1\r\nHost: ${url.host}\r\nOrigin: ${door}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n`
    + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nUser-Agent: vanish-check\r\n\r\n`);
  let head = '';
  let bytes = 0;
  const upgraded = await new Promise((res) => {
    const onData = (d) => {
      if (head.indexOf('\r\n\r\n') < 0) {
        head += d.toString('latin1');
        if (head.indexOf('\r\n\r\n') >= 0) res(/^HTTP\/1\.1 101/.test(head));
      } else bytes += d.length;
    };
    sock.on('data', onData);
    setTimeout(() => res(false), 8000);
  });
  check('the phone\'s screen opened', upgraded, head.split('\r\n')[0]);
  if (!upgraded) throw new Error('no upgrade');
  const marker = `vanish-${Date.now()}`;
  sh(`log -t OpenDeX "[test] ${marker}"`);
  // The phone kept awake as it is (wake:false), the sound asked for here with the phone quiet.
  sock.write(textFrame(JSON.stringify({ t: 'hello', v: 1, w: 800, h: 600, bitrate: 2e6, fps: 30, acks: false, quality: 'low', wake: false,
    audio: true, phone: false, codecs: ['pcm'] })));
  const quiet = await (async () => { for (let i = 0; i < 20; i++) { if (muted()) return true; await sleep(300); } return false; })();
  check('…the phone is quiet for it (sound asked for here)', quiet);
  const silentFrom = Date.now();
  log('now saying nothing, reading what comes…');
  let ended = null;
  for (let i = 0; i < 130 && !ended; i++) {
    await sleep(1000);
    const all = appLog();
    const at = all.indexOf(marker);
    const since = at < 0 ? '' : all.slice(at);
    if (/its session ends/.test(since) && /closed the screen/.test(since)) ended = since;
  }
  const after = (Date.now() - silentFrom) / 1000;
  check('the phone lets the silent computer go, within 90 s and some', !!ended && after <= 100, `${after.toFixed(0)} s, ${(bytes / 1e6).toFixed(1)} MB read meanwhile`);
  check('…and the phone\'s own sound is back', !muted());
} catch (e) {
  check('the run', false, String(e));
} finally {
  try { sock && sock.destroy(); } catch { /* gone */ }
  if (token) log('signed out:', (await post('/forget', { token })).status);
  runas(`echo ${untilBefore} > files/browser/pairing-until`);
  log(`tokens ${runas('sha256sum files/browser/tokens').split(' ')[0] === tokensBefore ? 'as before' : 'CHANGED'}; sign-in window ${runas('cat files/browser/pairing-until')}`);
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}
