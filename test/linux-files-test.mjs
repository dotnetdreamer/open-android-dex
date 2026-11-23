#!/usr/bin/env node
// "Open in a browser", Files (doc/linux-browser.md, "Files"): Linux's files
// in a computer's browser, checked as far as a computer can without a phone.
//
//   node test/linux-files-test.mjs [--browser <chrome or edge exe>] [--headless] [--shots <dir>] [--keep]
//
// The server is the launcher's own (LinuxBrowserServer, LinuxFiles and the
// rest, compiled for this JVM by test/linux-browser/BrowserHarness.java)
// over a folder standing in for the guest's /: a home with Documents and
// Pictures, links that point inside Linux and links that try to point out of
// it, the phone's shared folder bound at ~/Desktop/LinuxOnDeX as linux-rt.sh
// binds it, and /proc, /sys and /dev, which must never show.
//
// First the door, from Node: who gets in, every operation, what a path or a
// link can reach, uploads whole and resumed and many to a request, downloads
// whole and in ranges, a ZIP read back by an unzipper that is not ours (this
// computer's tar), ZIP64 too, the Trash and Undo, a sign-out mid-transfer.
// Then the page, in a browser window you can watch (--headless for none):
// files dropped onto it, a folder dropped, the file pickers, Quick Look,
// renaming, the Trash and Undo, downloads saved where the browser saves them,
// a drop onto the desktop's picture, and what the page says when Linux is
// not there.
//
// Needs Node 24, a JDK (JAVA_HOME), ffmpeg, and Chrome or Edge.

import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import https from 'node:https';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
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
const HEADLESS = args.includes('--headless');
const JDK = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
const JAVA = JDK ? path.join(JDK, 'java') : 'java';
const JAVAC = JDK ? path.join(JDK, 'javac') : 'javac';

let passed = 0;
let failed = 0;
function check(name, ok, detail = '') {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? '  ok  ' : '  FAIL'} ${name}${!ok && detail ? ` — ${String(detail).slice(0, 600)}` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(pred, ms = 8000, step = 50) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await pred();
    if (v) return v;
    await sleep(step);
  }
  return null;
}
/** A form or a query: every value of every name, repeated names kept (the Files' paths). */
const formOf = (o) => {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(o)) for (const x of [].concat(v)) p.append(k, String(x));
  return p.toString();
};
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-files-'));
const cleanups = [];
let cleanedUp = false;
function cleanup() {
  for (const c of cleanups.reverse()) {
    try {
      c();
    } catch {
      // gone already
    }
  }
  cleanups.length = 0;
  if (cleanedUp || args.includes('--keep')) return;
  cleanedUp = true;
  try {
    fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 120, retryDelay: 250 });
  } catch (e) {
    console.log(`note: ${tmp} is left behind (${e.code}); delete it by hand`);
  }
}
process.on('exit', cleanup);
process.on('SIGINT', () => process.exit(130));

function lanAddress() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal && /^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))\./.test(a.address)) return a.address;
    }
  }
  return null;
}
function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, '0.0.0.0', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

// ── the page's pure parts, under Node ───────────────────────────────────

console.log('fileicons.js');
{
  const f = await import(pathToFileURL(path.join(ASSETS, 'fileicons.js')).href);
  check('a photo, a film, a song, a PDF, code and an archive are each their kind',
    f.kindOf('IMG_2041.JPG') === 'image' && f.kindOf('clip.mkv') === 'video' && f.kindOf('a.flac') === 'audio'
      && f.kindOf('r.pdf') === 'pdf' && f.kindOf('main.rs') === 'code' && f.kindOf('b.tar.gz') === 'archive', JSON.stringify(
      ['IMG_2041.JPG', 'clip.mkv', 'a.flac', 'r.pdf', 'main.rs', 'b.tar.gz'].map(f.kindOf)));
  check('sizes read as people read them', f.fmtSize(0) === '0 bytes' && f.fmtSize(1) === '1 byte' && f.fmtSize(1500) === '1.5 kB'
    && f.fmtSize(4_100_000) === '4.1 MB' && f.fmtSize(12_345_678_901) === '12.3 GB', [f.fmtSize(1500), f.fmtSize(4_100_000), f.fmtSize(12_345_678_901)].join());
  const now = new Date(2026, 10, 20, 15, 0).getTime();
  check('times: today, yesterday, this year, long ago',
    f.fmtDate(new Date(2026, 10, 20, 9, 5).getTime(), now) === 'Today 09:05'
      && f.fmtDate(new Date(2026, 10, 19, 23, 59).getTime(), now) === 'Yesterday 23:59'
      && /^\d{1,2} Mar$/.test(f.fmtDate(new Date(2026, 2, 3).getTime(), now))
      && /2019$/.test(f.fmtDate(new Date(2019, 0, 5).getTime(), now)),
    [f.fmtDate(new Date(2026, 10, 20, 9, 5).getTime(), now), f.fmtDate(new Date(2026, 2, 3).getTime(), now)].join(' | '));
  const names = new Set(['photo.jpg', 'photo (2).jpg', 'backup.tar.gz', 'Album']);
  check('"Keep both" names as the phone does: photo (3).jpg, backup (2).tar.gz, Album (2)',
    f.freeName(names, 'photo.jpg', false) === 'photo (3).jpg' && f.freeName(names, 'backup.tar.gz', false) === 'backup (2).tar.gz'
      && f.freeName(names, 'Album', true) === 'Album (2)' && f.freeName(names, 'new.txt', false) === 'new.txt');
  check('time left reads naturally', f.fmtEta(8) === '8 s left' && f.fmtEta(95) === '2 min left' && f.fmtEta(7300) === '2 h 2 min left',
    [f.fmtEta(8), f.fmtEta(95), f.fmtEta(7300)].join());
}

console.log('transfer.js, the batch it sends');
{
  const t = await import(pathToFileURL(path.join(ASSETS, 'transfer.js')).href);
  const head = t.recordHead(1, 'a/b é.txt', 5, 1700000000123);
  const dv = new DataView(head.buffer);
  const nameLen = dv.getUint16(1);
  check('a record: kind, the path in UTF-8, its size and time, big-endian',
    head[0] === 1 && new TextDecoder().decode(head.subarray(3, 3 + nameLen)) === 'a/b é.txt'
      && dv.getBigUint64(3 + nameLen) === 5n && dv.getBigUint64(11 + nameLen) === 1700000000123n);
}

if (argOf('--phone')) {
  await onPhone(argOf('--phone'));
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
], { encoding: 'utf8' });
check("the launcher's browser and files classes compile on a JVM, with no Android in reach", javac.status === 0, javac.stderr);
if (javac.status !== 0) process.exit(1);

// The stand-in desktop and its sound, as linux-browser-test.mjs makes them.
const media = path.join(tmp, 'media');
fs.mkdirSync(media);
function ff(argsList, what) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...argsList], { encoding: 'utf8' });
  if (r.status !== 0) {
    check(`ffmpeg makes ${what}`, false, r.stderr || String(r.error));
    process.exit(1);
  }
}
for (const [name, size] of [['big', '1280x720'], ['small', '852x480']]) {
  ff(['-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=60`, '-t', '3', '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p',
    '-bf', '0', '-g', '240', '-x264-params', 'aud=1:repeat-headers=1', '-tune', 'zerolatency', '-bsf:v', 'h264_mp4toannexb', '-f', 'h264',
    path.join(media, `${name}.h264`)], `the ${name} stand-in`);
}
for (const [name, ch, rate] of [['mono', 1, '32k'], ['stereo', 2, '96k']]) {
  ff(['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '2', '-ac', String(ch), '-c:a', 'libopus', '-b:a', rate,
    '-frame_duration', '20', path.join(media, `${name}.opus`)], `the ${name} Opus`);
}

// ── the stand-in Linux ──────────────────────────────────────────────────

const rootfs = path.join(tmp, 'rootfs');
const shared = path.join(tmp, 'shared');
const outside = path.join(tmp, 'outside');
const put = (rel, data) => {
  const p = path.join(rootfs, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
  return p;
};
for (const d of ['root/Desktop/LinuxOnDeX', 'root/Downloads', 'root/Music', 'root/Videos', 'usr/bin', 'proc/1', 'sys/kernel', 'dev', 'tmp']) {
  fs.mkdirSync(path.join(rootfs, d), { recursive: true });
}
put('root/.config/user-dirs.dirs', [
  '# written by xdg-user-dirs-update', 'XDG_DESKTOP_DIR="$HOME/Desktop"', 'XDG_DOWNLOAD_DIR="$HOME/Downloads"',
  'XDG_DOCUMENTS_DIR="$HOME/Documents"', 'XDG_MUSIC_DIR="$HOME/Music"', 'XDG_PICTURES_DIR="$HOME/Pictures"',
  'XDG_VIDEOS_DIR="$HOME/Videos"', '',
].join('\n'));
put('root/Documents/notes.md', '# Notes\n\nLinux on the phone, files in the browser.\n');
put('root/Documents/page.html', '<script>window.parent.ownedBy = "linux"</script><h1>from Linux</h1>');
put('root/Documents/Projects/app/main.rs', 'fn main() { println!("hi"); }\n');
put('root/Documents/Projects/app/Cargo.toml', '[package]\nname = "app"\n');
put('root/.hidden', 'not shown unless asked\n');
put('etc/hostname', 'linuxondroid\n');
put('proc/1/status', 'the phone\'s own\n');
const bigBytes = crypto.randomBytes(24 * 1024 * 1024 + 123);
put('root/Downloads/big.bin', bigBytes);
fs.mkdirSync(outside, { recursive: true });
fs.writeFileSync(path.join(outside, 'secret.txt'), 'outside Linux\n');
fs.mkdirSync(shared, { recursive: true });
fs.writeFileSync(path.join(shared, 'phone.txt'), 'on the phone\'s shared folder\n');
ff(['-f', 'lavfi', '-i', 'testsrc2=size=320x200:rate=1', '-frames:v', '1', put('root/Pictures/photo.png', '')], 'a photo');
ff(['-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=30', '-t', '2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
  put('root/Videos/clip.mp4', '')], 'a video');
// Links, made by the harness with Linux's own targets.
fs.writeFileSync(path.join(rootfs, '.links'), [
  'root/docs\tDocuments', // inside, relative
  'root/etc-link\t/etc', // absolute: the guest's /etc, never the phone's
  'root/up\t../../../../..', // past / : stops at /
  'root/secret\t' + path.join(outside, 'secret.txt'), // a path of the computer's: not Linux's
  'root/hostname\t' + path.join(rootfs, 'etc', 'hostname'), // a path proot would write: the rootfs's own
  'root/loop\tloop', // itself
  '',
].join('\n'));

const IP = lanAddress();
check("this computer has an address on a home network for the browser's test", !!IP);
if (!IP) process.exit(1);
const PORT = await freePort();
const state = path.join(tmp, 'state');

const harness = spawn(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.BrowserHarness', state, String(PORT),
  path.join(media, 'big.h264'), path.join(media, 'small.h264'), ASSETS, path.join(media, 'mono.opus'), path.join(media, 'stereo.opus'),
  rootfs, shared], { stdio: ['pipe', 'pipe', 'pipe'] });
cleanups.push(() => harness.kill());
const events = [];
const outLines = [];
let ready = null;
let harnessErr = '';
{
  let rest = '';
  harness.stdout.on('data', (d) => {
    rest += d;
    let i;
    while ((i = rest.indexOf('\n')) >= 0) {
      const line = rest.slice(0, i).replace(/\r$/, '');
      rest = rest.slice(i + 1);
      if (line.startsWith('EV ')) {
        try {
          events.push({ at: Date.now(), ...JSON.parse(line.slice(3)) });
        } catch {
          events.push({ at: Date.now(), raw: line.slice(3) });
        }
      } else {
        outLines.push({ at: Date.now(), line });
        if (line.startsWith('READY ')) {
          const [, port, code] = line.split(' ');
          ready = { port: Number(port), code };
        }
      }
    }
  });
  harness.stderr.on('data', (d) => (harnessErr += d));
}
const say = (cmd) => harness.stdin.write(cmd + '\n');
async function command(cmd, answer, ms = 5000) {
  const t = Date.now();
  say(cmd);
  const hit = await until(() => outLines.find((l) => l.at >= t && l.line.startsWith(answer)), ms, 20);
  return hit ? hit.line : null;
}
await until(() => ready, 20000);
check('the harness listens, with a code', !!ready, harnessErr.slice(-600));
if (!ready) process.exit(1);
const BASE = `https://${IP}:${PORT}`;

/** One request, the phone's certificate taken as it is. Bodies are Buffers; so is what comes back. */
function request(method, urlPath, { headers = {}, body = null, agent = false, onSocket, onResponse } = {}) {
  return new Promise((resolve) => {
    const all = { ...headers };
    if (body != null && all['Content-Length'] == null) all['Content-Length'] = Buffer.byteLength(body);
    const req = https.request({ host: IP, port: PORT, method, path: urlPath, headers: all, rejectUnauthorized: false, agent }, (res) => {
      if (onResponse) onResponse(res);
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
        resolve({ status: res.statusCode, headers: res.headers, buf, json, complete: res.complete });
      });
      res.on('error', (e) => resolve({ status: res.statusCode, error: e.message, buf: Buffer.concat(chunks) }));
      res.on('aborted', () => resolve({ status: res.statusCode, aborted: true, buf: Buffer.concat(chunks) }));
    });
    if (onSocket) req.on('socket', onSocket);
    req.on('error', (e) => resolve({ status: 0, error: e.message }));
    if (body != null) req.write(body);
    req.end();
  });
}
const enc = encodeURIComponent;

const pair = await request('POST', '/pair', {
  headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: BASE }, body: formOf({ code: ready.code }),
});
const TOKEN = pair.json && pair.json.token;
check('a browser is let in with the code, as the page is', pair.status === 200 && !!TOKEN, pair.buf && pair.buf.toString());
if (!TOKEN) process.exit(1);
const AUTH = { Authorization: `Bearer ${TOKEN}` };
const CHANGE = { ...AUTH, Origin: BASE };
const FORM = { 'Content-Type': 'application/x-www-form-urlencoded' };
const fsGet = (op, q = {}) => request('GET', `/fs/${op}?${formOf(q)}`, { headers: AUTH });
const fsPost = (op, form = {}, q = {}) => request('POST', `/fs/${op}?${formOf(q)}`, { headers: { ...CHANGE, ...FORM }, body: formOf(form) });
const names = (r) => (r.json && r.json.entries ? r.json.entries.map((e) => e.n).sort() : []);
const entryOf = (r, n) => (r.json && r.json.entries ? r.json.entries.find((e) => e.n === n) : null);

console.log('who gets in');
{
  const none = await request('GET', `/fs/list?path=${enc('/root')}`);
  check('no token: 401, and not a name of a file', none.status === 401 && !/Documents/.test(none.buf.toString()), none.status);
  const wrong = await request('GET', `/fs/list?path=${enc('/root')}`, { headers: { Authorization: 'Bearer ' + 'x'.repeat(43) } });
  check('a made-up token: 401', wrong.status === 401);
  const noOrigin = await request('POST', '/fs/mkdir', { headers: { ...AUTH, ...FORM }, body: formOf({ path: '/root/x' }) });
  check('a change from somewhere other than the page itself (no Origin) is refused, token or not',
    noOrigin.status === 403 && !fs.existsSync(path.join(rootfs, 'root/x')), noOrigin.status);
  const otherOrigin = await request('POST', '/fs/mkdir', { headers: { ...AUTH, ...FORM, Origin: 'https://evil.example' }, body: formOf({ path: '/root/x' }) });
  check('…and from another site\'s page', otherOrigin.status === 403 && !fs.existsSync(path.join(rootfs, 'root/x')));
  const named = await request('GET', `/fs/list?path=${enc('/root')}`, { headers: { ...AUTH, Host: 'linux.example:7443' } });
  check('a request to a name rather than the address (DNS rebinding) gets nothing', named.status === 421, named.status);
  const ticketLess = await request('GET', `/fs/get?k=${'a'.repeat(32)}`);
  check('a made-up ticket opens nothing', ticketLess.status === 401);
  const ticketForList = await request('GET', `/fs/list?path=${enc('/root')}&k=${'a'.repeat(32)}`);
  check('a ticket is only for a download or a video, never a listing', ticketForList.status === 401);
  const getChange = await request('GET', `/fs/mkdir?path=${enc('/root/by-get')}`, { headers: AUTH });
  check('a change by GET is refused, token and all: only the page\'s POST changes anything',
    getChange.status === 405 && !fs.existsSync(path.join(rootfs, 'root/by-get')), getChange.status);
}

console.log('looking');
{
  const info = await fsGet('info');
  const ids = info.json ? info.json.places.map((p) => p.id) : [];
  check('places: home, the XDG folders that exist, the phone\'s shared folder, Linux, the Trash',
    ['home', 'desktop', 'documents', 'downloads', 'pictures', 'music', 'videos', 'shared', 'root', 'trash'].every((x) => ids.includes(x)),
    JSON.stringify(ids));
  check('…with room left on the phone', info.json && info.json.free > 0 && info.json.total > info.json.free);
  const home = await fsGet('list', { path: '/root' });
  check('home lists its folders and files, hidden ones too (the page hides them)',
    home.status === 200 && ['Desktop', 'Documents', 'Downloads', '.hidden', 'Pictures'].every((n) => names(home).includes(n)), names(home).join());
  const root = await fsGet('list', { path: '/' });
  check('Linux\'s / never shows /proc, /sys or /dev, which are the phone\'s own',
    root.status === 200 && names(root).includes('etc') && !['proc', 'sys', 'dev'].some((n) => names(root).includes(n)), names(root).join());
  const proc = await fsGet('list', { path: '/proc' });
  const procFile = await fsGet('get', { path: '/proc/1/status' });
  check('…nor can they be opened by name', proc.status === 404 && procFile.status === 404, `${proc.status} ${procFile.status}`);
  const climbed = await fsGet('list', { path: '/root/../../../..' });
  check('.. stops at Linux\'s /', climbed.json && climbed.json.path === '/' && names(climbed).includes('etc'), climbed.json && climbed.json.path);
  const desk = await fsGet('list', { path: '/root/Desktop' });
  const lod = entryOf(desk, 'LinuxOnDeX');
  check('the phone\'s shared folder shows where Linux sees it, marked as the phone\'s', lod && lod.t === 'd' && lod.v === 'shared', JSON.stringify(lod));
  const inShared = await fsGet('list', { path: '/root/Desktop/LinuxOnDeX' });
  check('…and holds the phone\'s files', names(inShared).includes('phone.txt'), names(inShared).join());
  const docs = entryOf(home, 'docs');
  check('a link inside Linux says where it leads', docs && docs.t === 'l' && docs.lt === 'd', JSON.stringify(docs));
  const viaLink = await fsGet('list', { path: '/root/docs' });
  check('…and opens as the folder it leads to', viaLink.json && viaLink.json.path === '/root/Documents' && names(viaLink).includes('notes.md'));
  const etcLink = await fsGet('list', { path: '/root/etc-link' });
  check('an absolute link is Linux\'s /etc, not the computer\'s or the phone\'s',
    etcLink.json && etcLink.json.path === '/etc' && names(etcLink).join() === 'hostname', names(etcLink).join());
  const up = await fsGet('list', { path: '/root/up' });
  check('a link that climbs past / stops at Linux\'s /', up.json && up.json.path === '/' && names(up).includes('root'));
  const secret = await fsGet('get', { path: '/root/secret' });
  check('a link holding a path outside Linux leads nowhere', [400, 404].includes(secret.status) && !secret.buf.toString().includes('outside Linux'), secret.status);
  const host = await fsGet('get', { path: '/root/hostname' });
  check('a link proot made (a path of the phone\'s into the rootfs) reads as the file in Linux', host.status === 200 && host.buf.toString() === 'linuxondroid\n', host.status);
  const loop = await fsGet('get', { path: '/root/loop' });
  check('a link to itself is refused, not followed for ever', loop.status === 400 && loop.json && loop.json.error === 'loop', loop.status);
  const notDir = await fsGet('list', { path: '/root/Documents/notes.md' });
  check('a file is not a folder', notDir.status === 409 && notDir.json.error === 'notdir');
  const found = await fsGet('find', { path: '/root', q: 'NOTES' });
  check('find: a name, whatever its case, anywhere under a folder',
    found.json && found.json.results.some((r) => r.p === '/root/Documents/notes.md'), JSON.stringify(found.json));
  const glob = await fsGet('find', { path: '/root', q: '*.rs' });
  check('…or a pattern', glob.json && glob.json.results.length === 1 && glob.json.results[0].p === '/root/Documents/Projects/app/main.rs');
  const du = await fsGet('du', { path: '/root/Documents' });
  check('how much a folder holds', du.json && du.json.files === 4 && du.json.dirs === 2, JSON.stringify(du.json));
}

console.log('bringing back');
{
  const whole = await fsGet('get', { path: '/root/Downloads/big.bin' });
  check('a file, whole, byte for byte', whole.status === 200 && whole.buf.equals(bigBytes) && whole.headers['accept-ranges'] === 'bytes');
  const r1 = await request('GET', `/fs/get?path=${enc('/root/Downloads/big.bin')}`, { headers: { ...AUTH, Range: 'bytes=100-199' } });
  const r2 = await request('GET', `/fs/get?path=${enc('/root/Downloads/big.bin')}`, { headers: { ...AUTH, Range: 'bytes=-10' } });
  const r3 = await request('GET', `/fs/get?path=${enc('/root/Downloads/big.bin')}`, { headers: { ...AUTH, Range: `bytes=${bigBytes.length - 5}-` } });
  check('ranges: a middle, the last ten, from a point to the end',
    r1.status === 206 && r1.buf.equals(bigBytes.subarray(100, 200)) && r1.headers['content-range'] === `bytes 100-199/${bigBytes.length}`
      && r2.status === 206 && r2.buf.equals(bigBytes.subarray(bigBytes.length - 10))
      && r3.status === 206 && r3.buf.equals(bigBytes.subarray(bigBytes.length - 5)), `${r1.status} ${r2.status} ${r3.status}`);
  const r4 = await request('GET', `/fs/get?path=${enc('/root/Downloads/big.bin')}`, { headers: { ...AUTH, Range: `bytes=${bigBytes.length}-` } });
  check('a range past the end: 416, saying how long it is', r4.status === 416 && r4.headers['content-range'] === `bytes */${bigBytes.length}`);
  const r5 = await request('GET', `/fs/get?path=${enc('/root/Downloads/big.bin')}`, { headers: { ...AUTH, Range: 'bytes=0-9', 'If-Range': '"old"' } });
  check('a resume of a file that changed since (If-Range) gets the whole new file', r5.status === 200 && r5.buf.length === bigBytes.length);
  const html = await fsGet('get', { path: '/root/Documents/page.html' });
  check('an HTML file of Linux\'s is shown as text, in a sandbox: never run as this page',
    html.status === 200 && /^text\/plain/.test(html.headers['content-type']) && /sandbox/.test(html.headers['content-security-policy'] || '')
      && html.headers['x-content-type-options'] === 'nosniff', JSON.stringify([html.headers['content-type'], html.headers['content-security-policy']]));
  const t = await fsPost('ticket', { kind: 'get', p: '/root/Documents/notes.md' });
  check('a ticket, for a download the browser makes itself', t.status === 200 && t.json && t.json.k);
  const viaTicket = await request('GET', `/fs/get?k=${t.json.k}&dl=1`);
  check('…which gets that file, as a download (UTF-8 name too), with no token',
    viaTicket.status === 200 && viaTicket.buf.toString().startsWith('# Notes') && /^attachment; filename="notes.md"; filename\*=UTF-8''notes.md$/.test(viaTicket.headers['content-disposition']),
    viaTicket.headers['content-disposition']);
  const otherPath = await request('GET', `/fs/get?k=${t.json.k}&path=${enc('/etc/hostname')}`);
  check('…and only that file, whatever else the URL names', otherPath.status === 200 && otherPath.buf.toString().startsWith('# Notes'));
  const tOutside = await fsPost('ticket', { kind: 'get', p: '/proc/1/status' });
  check('no ticket for what cannot be opened', tOutside.status === 404);

  // A ZIP of a folder and a file, read back by this computer's own tar (libarchive), and by the test.
  const z = await fsPost('ticket', { kind: 'zip', p: ['/root/Documents', '/root/Pictures/photo.png'], name: 'Pick é.zip' });
  const zr = await request('GET', `/fs/zip?k=${z.json.k}`);
  check('a ZIP of a folder and a file, its length known before the first byte',
    zr.status === 200 && Number(zr.headers['content-length']) === zr.buf.length && /filename\*=UTF-8''Pick%20%C3%A9\.zip/.test(zr.headers['content-disposition']),
    `${zr.status} ${zr.headers['content-length']} ${zr.buf.length} ${zr.headers['content-disposition']}`);
  const read = readZip(zr.buf);
  check('…every entry where its directory says, each CRC right',
    read.ok && ['Documents/', 'Documents/notes.md', 'Documents/Projects/app/main.rs', 'photo.png'].every((n) => read.names.includes(n)), read.error || read.names.join());
  const unzipped = unzipWithTar(zr.buf, 'zip1');
  check('…and this computer\'s own unzipper agrees, file for file',
    unzipped.ok && fs.readFileSync(path.join(unzipped.dir, 'Documents/Projects/app/main.rs'), 'utf8') === 'fn main() { println!("hi"); }\n'
      && fs.readFileSync(path.join(unzipped.dir, 'photo.png')).equals(fs.readFileSync(path.join(rootfs, 'root/Pictures/photo.png'))), unzipped.error);
  // ZIP64, every field of it, without four gigabytes: the harness moves the threshold.
  await command('zip64 1000', 'OK zip64');
  const z64 = await fsPost('ticket', { kind: 'zip', p: ['/root/Downloads/big.bin', '/root/Documents'] });
  const z64r = await request('GET', `/fs/zip?k=${z64.json.k}`);
  await command('zip64 4294967295', 'OK zip64');
  const read64 = readZip(z64r.buf);
  check('ZIP64: sizes, offsets and the end records, read back', read64.ok && read64.zip64 && read64.names.includes('big.bin'), read64.error);
  const un64 = unzipWithTar(z64r.buf, 'zip64');
  check('…and by this computer\'s unzipper, byte for byte', un64.ok && fs.readFileSync(path.join(un64.dir, 'big.bin')).equals(bigBytes), un64.error);
}

console.log('sending');
{
  const mtime = Date.UTC(2024, 4, 6, 7, 8, 10);
  const one = Buffer.from('hello from the computer\n');
  const p1 = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/hello.txt', size: one.length, mtime })}`, { headers: CHANGE, body: one });
  const onDisk = path.join(rootfs, 'root/Downloads/hello.txt');
  check('a file, whole, into place, with its own time', p1.status === 200 && p1.json.done && fs.readFileSync(onDisk).equals(one)
    && Math.abs(fs.statSync(onDisk).mtimeMs - mtime) < 2000, JSON.stringify(p1.json));
  const again = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/hello.txt', size: one.length, mtime, on: 'rename' })}`, { headers: CHANGE, body: one });
  check('the same file sent again is already there: no copy made', again.json && again.json.same === true && !fs.existsSync(path.join(rootfs, 'root/Downloads/hello (2).txt')));
  const other = Buffer.from('another hello\n');
  const fail = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/hello.txt', size: other.length, mtime: mtime + 60000 })}`, { headers: CHANGE, body: other });
  check('a different file under a taken name: refused unless told what to do', fail.status === 409 && fail.json.error === 'exists' && fs.readFileSync(onDisk).equals(one));
  const keepBoth = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/hello.txt', size: other.length, mtime: mtime + 60000, on: 'rename' })}`, { headers: CHANGE, body: other });
  check('…keep both: "hello (2).txt"', keepBoth.json && keepBoth.json.path === '/root/Downloads/hello (2).txt');
  const replace = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/hello.txt', size: other.length, mtime: mtime + 60000, on: 'replace' })}`, { headers: CHANGE, body: other });
  check('…replace: the new one in its place', replace.json && replace.json.done && fs.readFileSync(onDisk).equals(other));

  // A big one, in two goes: what a cut connection and a resume are.
  const big = crypto.randomBytes(9 * 1024 * 1024 + 7);
  const q = { path: '/root/Downloads/resume.bin', size: big.length, mtime };
  const half = 4 * 1024 * 1024 + 3;
  const a = await request('PUT', `/fs/put?${formOf({ ...q, off: 0 })}`, { headers: { ...CHANGE, 'Content-Length': half }, body: big.subarray(0, half) });
  check('half a file: kept as a hidden part, not yet in place', a.json && a.json.done === false && a.json.have === half
    && !fs.existsSync(path.join(rootfs, 'root/Downloads/resume.bin')) && !names(await fsGet('list', { path: '/root/Downloads' })).some((n) => n.startsWith('.lodpart')));
  const have = await fsGet('part', q);
  check('…what arrived, asked for', have.json && have.json.have === half);
  const wrongOff = await request('PUT', `/fs/put?${formOf({ ...q, off: half + 1 })}`, { headers: CHANGE, body: big.subarray(half + 1) });
  check('…a resume from the wrong place is told where to start', wrongOff.status === 409 && wrongOff.json.have === half);
  const b = await request('PUT', `/fs/put?${formOf({ ...q, off: half })}`, { headers: CHANGE, body: big.subarray(half) });
  check('…the rest, from there: whole, in place', b.json && b.json.done && fs.readFileSync(path.join(rootfs, 'root/Downloads/resume.bin')).equals(big));

  // A cut that really happens: the socket closed half way through the body.
  const cutQ = { path: '/root/Downloads/cut.bin', size: big.length, mtime };
  await new Promise((resolve) => {
    const req = https.request({ host: IP, port: PORT, method: 'PUT', path: `/fs/put?${formOf({ ...cutQ, off: 0 })}`,
      headers: { ...CHANGE, 'Content-Length': big.length }, rejectUnauthorized: false, agent: false });
    req.on('error', () => resolve());
    req.on('close', () => resolve());
    req.write(big.subarray(0, 3 * 1024 * 1024), () => setTimeout(() => req.destroy(), 300));
  });
  const cutHave = await until(async () => {
    const r = await fsGet('part', cutQ);
    return r.json && r.json.have > 0 ? r.json.have : null;
  }, 3000);
  const cutRest = cutHave ? await request('PUT', `/fs/put?${formOf({ ...cutQ, off: cutHave })}`, { headers: CHANGE, body: big.subarray(cutHave) }) : {};
  check('a connection cut mid-file: what arrived is kept, and the rest finishes it',
    cutHave > 0 && cutRest.json && cutRest.json.done && fs.readFileSync(path.join(rootfs, 'root/Downloads/cut.bin')).equals(big), `${cutHave} ${JSON.stringify(cutRest.json)}`);
  const cancel = Buffer.alloc(1000, 1);
  const cq = { path: '/root/Downloads/cancelled.bin', size: 5000, mtime };
  await request('PUT', `/fs/put?${formOf({ ...cq, off: 0 })}`, { headers: CHANGE, body: cancel });
  const unpart = await fsPost('unpart', {}, cq);
  const after = await fsGet('part', cq);
  check('a cancelled upload leaves nothing behind', unpart.status === 200 && after.json.have === 0);

  // Many small files and folders in one request.
  const files = [];
  for (let i = 0; i < 40; i++) files.push({ rel: `Album/day ${i % 4}/IMG_${String(i).padStart(4, '0')}.txt`, data: Buffer.from(`photo ${i} `.repeat(i + 1)) });
  const body = batchBody([...files, { rel: 'Album/empty', dir: true }, { rel: '../escape.txt', data: Buffer.from('no') },
    { rel: '/abs.txt', data: Buffer.from('no') }, { rel: 'Album/ok after bad.txt', data: Buffer.from('yes') }], mtime);
  const t0 = Date.now();
  const bt = await request('POST', `/fs/batch?${formOf({ dir: '/root/Pictures', on: 'rename' })}`, { headers: { ...CHANGE, 'Content-Type': 'application/octet-stream' }, body });
  const allThere = files.every((f) => fs.readFileSync(path.join(rootfs, 'root/Pictures', f.rel)).equals(f.data));
  check('forty files in four folders and an empty folder, one request',
    bt.status === 200 && bt.json.done === 42 && allThere && fs.statSync(path.join(rootfs, 'root/Pictures/Album/empty')).isDirectory(), JSON.stringify(bt.json));
  check('…a path that climbs out, or starts at /, is refused and the rest still lands',
    bt.json && bt.json.failed.length === 2 && !fs.existsSync(path.join(rootfs, 'root/escape.txt')) && !fs.existsSync(path.join(rootfs, 'abs.txt'))
      && fs.readFileSync(path.join(rootfs, 'root/Pictures/Album/ok after bad.txt'), 'utf8') === 'yes', JSON.stringify(bt.json && bt.json.failed));
  console.log(`        (${Date.now() - t0} ms)`);
  const bt2 = await request('POST', `/fs/batch?${formOf({ dir: '/root/Pictures', on: 'rename' })}`, { headers: { ...CHANGE, 'Content-Type': 'application/octet-stream' }, body: batchBody(files, mtime) });
  check('the same batch again (a retry after a cut): already there, nothing doubled',
    bt2.json && bt2.json.same === 40 && !fs.existsSync(path.join(rootfs, 'root/Pictures/Album/day 0/IMG_0000 (2).txt')), JSON.stringify(bt2.json));
  const bt3 = await request('POST', `/fs/batch?${formOf({ dir: '/root/Pictures', on: 'skip' })}`, { headers: CHANGE,
    body: batchBody([{ rel: 'Album/day 0/IMG_0000.txt', data: Buffer.from('changed') }, { rel: 'Album/new.txt', data: Buffer.from('new') }], mtime + 5000) });
  check('skip: what is there stays, what is new lands', bt3.json && bt3.json.skipped.length === 1 && bt3.json.done === 1
    && fs.readFileSync(path.join(rootfs, 'root/Pictures/Album/day 0/IMG_0000.txt')).equals(files[0].data));
  // The phone lets go just after its answer is on its way: waited for.
  await until(() => (events.filter((e) => e.busy !== undefined).at(-1) || {}).busy === false, 3000);
  const busyEv = events.filter((e) => e.busy !== undefined);
  check('the phone was kept awake for the transfers, and let go after',
    busyEv.some((e) => e.busy === true) && busyEv.at(-1).busy === false && (await command('fsbusy?', 'FSBUSY')) === 'FSBUSY 0', JSON.stringify(busyEv.slice(-3)));

  const speedFile = crypto.randomBytes(64 * 1024 * 1024);
  const s0 = Date.now();
  const up = await request('PUT', `/fs/put?${formOf({ path: '/root/Downloads/speed.bin', size: speedFile.length, mtime })}`, { headers: CHANGE, body: speedFile });
  const upMs = Date.now() - s0;
  const s1 = Date.now();
  const down = await fsGet('get', { path: '/root/Downloads/speed.bin' });
  const downMs = Date.now() - s1;
  check('64 MB up and back, intact', up.json && up.json.done && down.buf.equals(speedFile));
  console.log(`        this computer to itself over its network address, TLS: up ${(64 / (upMs / 1000)).toFixed(0)} MB/s, down ${(64 / (downMs / 1000)).toFixed(0)} MB/s`);
  fs.rmSync(path.join(rootfs, 'root/Downloads/speed.bin'));
}

console.log('folders, names, the Trash');
{
  const mk = await fsPost('mkdir', { path: '/root/Documents/New folder', on: 'rename' });
  const mk2 = await fsPost('mkdir', { path: '/root/Documents/New folder', on: 'rename' });
  check('a new folder, and a second: "New folder (2)"', mk.json.path === '/root/Documents/New folder' && mk2.json.path === '/root/Documents/New folder (2)');
  const rn = await fsPost('rename', { path: '/root/Documents/New folder (2)', name: 'Plans' });
  const taken = await fsPost('rename', { path: '/root/Documents/Plans', name: 'notes.md' });
  check('renamed; a name already taken is refused', rn.json.path === '/root/Documents/Plans' && taken.status === 409);
  const badName = await fsPost('rename', { path: '/root/Documents/Plans', name: '../Plans' });
  check('a name with a slash in it is no name', badName.status === 400);
  const mv = await fsPost('move', { p: ['/root/Documents/notes.md'], dir: '/root/Documents/Plans' });
  check('moved into a folder', mv.json.moved.length === 1 && fs.existsSync(path.join(rootfs, 'root/Documents/Plans/notes.md')));
  const into = await fsPost('move', { p: ['/root/Documents/Plans'], dir: '/root/Documents/Plans' });
  check('a folder into itself: refused', into.json.failed.length === 1 && into.json.failed[0][1] === 'inside');
  const cp = await fsPost('move', { p: ['/root/Documents/Plans/notes.md'], dir: '/root/Documents/Plans', copy: 1 });
  check('copied beside itself: "notes (2).md"', cp.json.moved[0] && cp.json.moved[0][1] === '/root/Documents/Plans/notes (2).md');
  const toPhone = await fsPost('move', { p: ['/root/Documents/Plans/notes (2).md'], dir: '/root/Desktop/LinuxOnDeX' });
  check('moved onto the phone\'s shared folder (another filesystem on a phone): there, and gone from here',
    toPhone.json.moved.length === 1 && fs.existsSync(path.join(shared, 'notes (2).md')) && !fs.existsSync(path.join(rootfs, 'root/Documents/Plans/notes (2).md')));
  const ex = await fsPost('exists', { dir: '/root/Documents', n: ['Plans', 'nothing', 'page.html'] });
  check('which names are taken in a folder', JSON.stringify(ex.json.exists) === JSON.stringify({ Plans: 'd', 'page.html': 'f' }), JSON.stringify(ex.json));
  for (const p of ['/', '/root', '/usr', '/root/Desktop/LinuxOnDeX', '/root/Desktop', '/root/.local/share/Trash']) {
    const d = await fsPost('delete', { p });
    const t = await fsPost('trash', { p });
    if (!(d.json.failed[0] && d.json.failed[0][1] === 'protected' && t.json && (t.json.failed[0] || [])[1] === 'protected')) {
      check(`${p} cannot be deleted or put in the Trash from a browser`, false, JSON.stringify([d.json, t.json]));
    }
  }
  check('/, the home folder, Linux\'s own folders, the shared folder and the Trash cannot be deleted from a browser',
    fs.existsSync(path.join(rootfs, 'usr/bin')) && fs.existsSync(path.join(rootfs, 'root/Desktop/LinuxOnDeX')));

  const tr = await fsPost('trash', { p: ['/root/Documents/Plans', '/root/Documents/page.html'] });
  const trashed = path.join(rootfs, 'root/.local/share/Trash');
  const info = fs.existsSync(path.join(trashed, 'info/Plans.trashinfo')) ? fs.readFileSync(path.join(trashed, 'info/Plans.trashinfo'), 'utf8') : '';
  check('into Linux\'s Trash, as its file manager lays it out (files/ and a .trashinfo saying where from)',
    tr.json.trashed.length === 2 && fs.existsSync(path.join(trashed, 'files/Plans/notes.md')) && /^\[Trash Info\]\nPath=\/root\/Documents\/Plans\nDeletionDate=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\n$/.test(info), info);
  const tl = await fsGet('trashlist');
  check('the Trash lists where each came from', tl.json && tl.json.entries.some((e) => e.id === 'Plans' && e.o === '/root/Documents/Plans'));
  const un = await fsPost('untrash', { id: ['Plans'] });
  check('Undo: back where it was', un.json.restored[0] === '/root/Documents/Plans' && fs.existsSync(path.join(rootfs, 'root/Documents/Plans/notes.md'))
    && !fs.existsSync(path.join(trashed, 'info/Plans.trashinfo')));
  const sharedTrash = await fsPost('trash', { p: '/root/Desktop/LinuxOnDeX/phone.txt' });
  check('a file on the phone\'s shared folder cannot go in Linux\'s Trash: said, and left alone',
    sharedTrash.json.cannot[0] === '/root/Desktop/LinuxOnDeX/phone.txt' && fs.existsSync(path.join(shared, 'phone.txt')) && fs.readdirSync(path.join(trashed, 'info')).every((n) => n !== 'phone.txt.trashinfo'), JSON.stringify(sharedTrash.json));
  const del = await fsPost('delete', { p: '/root/Desktop/LinuxOnDeX/phone.txt' });
  check('…and deleted for good when asked', del.json.deleted.length === 1 && !fs.existsSync(path.join(shared, 'phone.txt')));
  const linkDel = await fsPost('delete', { p: '/root/docs' });
  check('deleting a link deletes the link, never what it leads to', linkDel.json.deleted.length === 1 && fs.existsSync(path.join(rootfs, 'root/Documents/Projects/app/main.rs')));
  const empty = await fsPost('emptytrash');
  check('the Trash emptied', empty.json.deleted === 1 && fs.readdirSync(path.join(trashed, 'files')).length === 0 && fs.readdirSync(path.join(trashed, 'info')).length === 0, JSON.stringify(empty.json));
}

console.log('when Linux is not there');
{
  await command('fsstate installing', 'OK fsstate');
  const i = await fsGet('info');
  await command('fsstate missing', 'OK fsstate');
  const m = await fsGet('list', { path: '/root' });
  await command('fsstate ready', 'OK fsstate');
  check('still being installed, or not installed: said, as a word the page knows', i.status === 503 && i.json.error === 'installing' && m.status === 503 && m.json.error === 'missing');
}

console.log('a sign-out on the phone');
{
  const t = await fsPost('ticket', { kind: 'get', p: '/root/Downloads/big.bin' });
  // A download that reads slowly, so it is still going when the phone signs everyone out.
  let gotBytes = 0;
  let ended = false;
  // Slow until the sign-out, then as fast as it comes: what this computer had
  // buffered is read out, and then the end is the phone's.
  let drain = false;
  const slow = new Promise((resolve) => {
    const req = https.request({ host: IP, port: PORT, path: `/fs/get?k=${t.json.k}`, rejectUnauthorized: false, agent: false }, (res) => {
      res.on('data', (c) => {
        gotBytes += c.length;
        if (drain) return;
        res.pause();
        setTimeout(() => res.resume(), 200);
      });
      res.on('end', () => resolve('end'));
      res.on('aborted', () => resolve('aborted'));
      res.on('error', () => resolve('error'));
      res.on('close', () => resolve('close'));
    });
    req.on('error', () => resolve('error'));
    req.end();
  }).then((how) => {
    ended = true;
    return how;
  });
  await until(() => gotBytes > 0, 5000);
  await command('signout', 'CODE');
  drain = true;
  const how = await Promise.race([slow, sleep(15000).then(() => 'still going')]);
  check('a download under way when the phone signs browsers out is cut there', ended && gotBytes < bigBytes.length && how !== 'still going', `${how} ${gotBytes}`);
  const after = await request('GET', `/fs/get?k=${t.json.k}`);
  const listAfter = await fsGet('list', { path: '/root' });
  check('…its ticket and its token open nothing after', after.status === 401 && listAfter.status === 401, `${after.status} ${listAfter.status}`);
}

if (args.includes('--api-only')) {
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

// ── the page, in a browser ──────────────────────────────────────────────

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
 * A browser window of its own (its own profile and debugging port), on the
 * screen unless --headless: driven over the DevTools protocol, which puts its
 * clicks and keys into that page alone, never into this computer's input.
 */
async function launchBrowser() {
  const exe = findBrowser();
  if (!exe) return null;
  const cdpPort = await freePort();
  const profile = path.join(tmp, `profile-${cdpPort}`);
  const proc = spawn(exe, [
    ...(HEADLESS ? ['--headless=new'] : []), `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${profile}`, '--no-first-run',
    '--no-default-browser-check', '--disable-extensions', '--window-size=1440,900', '--window-position=60,40',
    // Covered by another window, it would stop drawing (and the list draws as it scrolls).
    '--disable-features=CalculateNativeWinOcclusion', '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding',
    '--disable-background-timer-throttling', 'about:blank',
  ], { stdio: 'ignore' });
  const kill = () => {
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
  const sock = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => (sock.onopen = r));
  let id = 0;
  const pending = new Map();
  const pageErrors = [];
  const downloads = [];
  const netlog = [];
  /** Requests DevTools holds (Fetch.enable), the newest last. */
  const held = [];
  sock.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m);
      pending.delete(m.id);
    } else if (m.method === 'Runtime.exceptionThrown') {
      pageErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      pageErrors.push(m.params.args.map((a) => a.value ?? a.description).join(' '));
    } else if (m.method && m.method.startsWith('Network.')) {
      netlog.push({ at: Date.now(), m: m.method, id: m.params.requestId, url: m.params.request ? m.params.request.url : undefined,
        timing: m.params.response ? m.params.response.timing : undefined, conn: m.params.response ? m.params.response.connectionId : undefined,
        reused: m.params.response ? m.params.response.connectionReused : undefined, err: m.params.errorText });
    } else if (m.method === 'Browser.downloadProgress' || m.method === 'Page.downloadProgress') {
      downloads.push(m.params);
    } else if (m.method === 'Fetch.requestPaused') {
      held.push(m.params);
    } else if (m.method === 'Page.javascriptDialogOpening') {
      sock.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
    }
  };
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
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('DOM.enable');
  if (process.env.NETLOG) await cdp('Network.enable');
  return { cdp, js, shot, pageErrors, downloads, kill, netlog, held };
}

const B = await launchBrowser();
check('a Chromium browser starts, with a debugging port', !!B);
if (!B) process.exit(1);
const { cdp, js, shot, pageErrors } = B;
const dlDir = path.join(tmp, 'downloads');
fs.mkdirSync(dlDir, { recursive: true });
await cdp('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: dlDir, eventsEnabled: true });
await cdp('Browser.grantPermissions', { permissions: ['clipboardReadWrite', 'clipboardSanitizedWrite'], origin: BASE });
const center = (sel) => js(`(() => { const e = document.querySelector(${JSON.stringify(sel)}); if (!e) return null;
  const r = e.getBoundingClientRect(); return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; })()`);
async function mouse(x, y, { button = 'left', count = 1, modifiers = 0 } = {}) {
  const buttons = { left: 1, right: 2 }[button];
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, modifiers });
  for (let c = 1; c <= count; c++) {
    await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, buttons, clickCount: c, modifiers });
    await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, buttons: 0, clickCount: c, modifiers });
  }
}
async function clickSel(sel, opts) {
  const c = await center(sel);
  if (!c) return false;
  await mouse(c.x, c.y, opts);
  return true;
}
const KEYS = {
  F2: ['F2', 'F2', 113], Delete: ['Delete', 'Delete', 46], Enter: ['Enter', 'Enter', 13, '\r'], Escape: ['Escape', 'Escape', 27],
  Space: [' ', 'Space', 32, ' '], Backspace: ['Backspace', 'Backspace', 8], ArrowDown: ['ArrowDown', 'ArrowDown', 40], ArrowUp: ['ArrowUp', 'ArrowUp', 38],
  ArrowRight: ['ArrowRight', 'ArrowRight', 39], N: ['N', 'KeyN', 78], A: ['a', 'KeyA', 65], H: ['h', 'KeyH', 72],
};
async function key(name, modifiers = 0) {
  const [k, code, vk, text] = KEYS[name];
  await cdp('Input.dispatchKeyEvent', { type: text && !modifiers ? 'keyDown' : 'rawKeyDown', key: k, code, windowsVirtualKeyCode: vk, text: modifiers ? undefined : text, modifiers });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, modifiers });
}
/** A field typed into the way the page reads it (a window not in front takes no inserted text). */
const typeInto = (sel, value) => js(`(() => { const e = document.querySelector(${JSON.stringify(sel)}); if (!e) return false;
  e.focus(); e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`);
const rowsNow = () => js(`[...document.querySelectorAll('#files .fx-row .fx-name .n, #files .fx-tile .fx-tname')].map((e) => e.textContent)`);
const rowSel = (name) => `#files .fx-row:has(.n[title], .n), #files [data-name="${name}"]`;
async function rowOf(name) {
  return js(`(() => { const r = [...document.querySelectorAll('#files .fx-row, #files .fx-tile')].find((x) => (x.querySelector('.n') || x.querySelector('.fx-tname')).textContent === ${JSON.stringify(name)});
    if (!r) return null; const b = r.getBoundingClientRect(); return { x: Math.round(b.left + Math.min(160, b.width / 2)), y: Math.round(b.top + b.height / 2) }; })()`);
}
async function dropFiles(x, y, paths) {
  const data = { items: [], files: paths, dragOperationsMask: 1 };
  await cdp('Input.dispatchDragEvent', { type: 'dragEnter', x, y, data });
  await cdp('Input.dispatchDragEvent', { type: 'dragOver', x, y, data });
  await sleep(250);
  const overlay = await js(`(() => { const o = document.querySelector('.drop'); return o && !o.hidden ? o.querySelector('.drop-title').textContent : null; })()`);
  await cdp('Input.dispatchDragEvent', { type: 'drop', x, y, data });
  return overlay;
}
/** Files has finished opening {@code label}: the path says so and the list is the folder's. */
const settled = (label) => until(() => js(`(() => { const c = document.querySelector('#files .crumb.here');
  const st = document.querySelector('#files .fx-status'); return !!c && c.textContent === ${JSON.stringify(label)} && !!st && !st.textContent.startsWith('Opening'); })()`), 8000);
const readOr = (p) => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null);
const trayState = () => js(`[...document.querySelectorAll('.tray .job')].map((j) => j.dataset.state + ' ' + j.querySelector('.job-name').textContent)`);

// Things on this computer to send: files, a folder with a folder in it, an empty one.
const local = path.join(tmp, 'computer');
fs.mkdirSync(path.join(local, 'Holiday/day one'), { recursive: true });
fs.mkdirSync(path.join(local, 'Holiday/empty'), { recursive: true });
const sendA = path.join(local, 'report.txt');
fs.writeFileSync(sendA, 'from the computer\n');
const sendBig = path.join(local, 'film.bin');
fs.writeFileSync(sendBig, crypto.randomBytes(20 * 1024 * 1024));
for (let i = 0; i < 25; i++) fs.writeFileSync(path.join(local, 'Holiday/day one', `photo ${i}.txt`), `photo ${i}\n`.repeat(i + 1));
fs.writeFileSync(path.join(local, 'Holiday/notes.md'), '# Holiday\n');
const code = (await command('code', 'CODE')).slice(5);
// The API part deleted it for good; the page's part shows it again.
put('root/Documents/page.html', '<script>window.parent.ownedBy = "linux"</script><h1>from Linux</h1>');

console.log('Files in the page');
{
  await cdp('Page.navigate', { url: `${BASE}/#files` });
  await sleep(1200);
  await js(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
  await sleep(300);
  await js(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
  await until(() => js(`location.href.startsWith('${BASE}') && !!document.getElementById('code')`), 10000);
  await until(() => js(`!document.getElementById('pair').hidden`), 8000);
  await typeInto('#code', code);
  const opened = await until(() => js(`!!document.querySelector('#files.on') && document.querySelectorAll('#files .fx-row').length > 3`), 10000);
  check('an address with #files opens straight into Files once the code is typed, the desktop never started',
    !!opened && !events.some((e) => e.hello), JSON.stringify(await rowsNow()));
  const places = await js(`[...document.querySelectorAll('#files .place span')].map((e) => e.textContent)`);
  check('the places down the side: Home, the XDG folders, the phone\'s folder, Linux, the Trash',
    JSON.stringify(places) === JSON.stringify(['Home', 'Desktop', 'Documents', 'Downloads', 'Pictures', 'Music', 'Videos', 'Phone’s folder', 'Linux', 'Trash']), JSON.stringify(places));
  check('hidden files are out of sight until asked for', !(await rowsNow()).includes('.hidden'));
  await shot('01-home');

  // Into Documents by double-click; the path follows.
  const docs = await rowOf('Documents');
  await js(`window.__dbl = []; document.addEventListener('dblclick', (e) => window.__dbl.push(e.target.className || e.target.tagName), true)`);
  await js(`window.__f = []; (() => { const of = window.fetch; window.fetch = (...a) => { const t0 = performance.now(); const u = String(a[0]);
    window.__f.push(['start', u.slice(0, 60), Math.round(t0)]); return of(...a).then((r) => { window.__f.push(['end', u.slice(0, 60), Math.round(performance.now() - t0), r.status]); return r; },
    (e) => { window.__f.push(['err', u.slice(0, 60), String(e)]); throw e; }); }; })()`);
  const under = await js(`(() => { const e = document.elementFromPoint(${docs.x}, ${docs.y}); return e ? e.className || e.tagName : null; })()`);
  await mouse(docs.x, docs.y, { count: 2 });
  await settled('Documents');
  check('a double-click opens a folder, and the path says where', (await rowsNow()).includes('page.html'),
    (process.env.NETLOG ? JSON.stringify(B.netlog.filter((x) => (x.url || '').includes('fs/') || x.err).slice(-12)) : '') + JSON.stringify({ fetches: await js('window.__f'), now: await js('Math.round(performance.now())'), rows: await rowsNow(), errors: pageErrors, status: await js(`document.querySelector('#files .fx-status').textContent`), under, dbl: await js('window.__dbl'), crumbs: await js(`[...document.querySelectorAll('#files .fx-crumbs .crumb')].map((e) => e.textContent)`) }));

  // Files dropped from the computer onto the list.
  const list = await center('#files .fx-scroll');
  const said = await dropFiles(list.x, list.y + 120, [sendA, sendBig]);
  check('dragged over the list, it says where they will go', said === 'Drop to copy into Documents', said);
  const landed = await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/film.bin'))
    && fs.readFileSync(path.join(rootfs, 'root/Documents/film.bin')).equals(fs.readFileSync(sendBig))
    && fs.existsSync(path.join(rootfs, 'root/Documents/report.txt')), 20000);
  check('dropped: a small file and a 20 MB one, byte for byte, in Linux\'s Documents', !!landed);
  await until(() => js(`[...document.querySelectorAll('.tray .job')].some((j) => j.dataset.state === 'done')`), 8000);
  await shot('02-sent');
  check('the panel says it is done, and the list shows them arriving', JSON.stringify(await trayState()).includes('done')
    && (await until(async () => (await rowsNow()).includes('film.bin'), 6000)), JSON.stringify(await trayState()));

  // A whole folder, dropped: the tree as it is, the empty folder too.
  await dropFiles(list.x, list.y + 120, [path.join(local, 'Holiday')]);
  const tree = await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/Holiday/day one/photo 24.txt'))
    && fs.existsSync(path.join(rootfs, 'root/Documents/Holiday/empty'))
    && readOr(path.join(rootfs, 'root/Documents/Holiday/notes.md')) === '# Holiday\n', 20000);
  check('a folder dropped: every file in its folders, and the empty folder', !!tree,
    fs.existsSync(path.join(rootfs, 'root/Documents/Holiday')) ? fs.readdirSync(path.join(rootfs, 'root/Documents/Holiday')).join() : 'no Holiday');

  // The same name again, different inside: the page asks.
  fs.writeFileSync(sendA, 'changed on the computer\n');
  fs.utimesSync(sendA, new Date(), new Date(Date.now() + 5000));
  await dropFiles(list.x, list.y + 120, [sendA]);
  const asked = await until(() => js(`(() => { const d = document.querySelector('dialog.fx-dialog[open]'); return d ? d.querySelector('h2').textContent : null; })()`), 5000);
  check('a name already there: the page asks what to do', asked === '“report.txt” is already in Documents', asked);
  await shot('03-conflict');
  await js(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Keep both').click()`);
  const both = await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/report (2).txt')), 8000);
  check('…keep both: "report (2).txt" beside the first, which is as it was', !!both
    && fs.readFileSync(path.join(rootfs, 'root/Documents/report.txt'), 'utf8') === 'from the computer\n');

  // A different 20 MB file with the same name, size and time (two different
  // files can share both, and the quick check takes one for the other):
  // Replace sends it, and Linux has its bytes. Then the first one again,
  // Replace: sent again, whatever the quick check says, and its bytes back.
  // Until 2026-11-21 Replace kept the file there, without a word, whenever
  // the size and time matched. (Before that, the same file again ended in
  // "The phone stopped answering": the phone answered before reading the
  // body and hung up, and Edge, still sending, saw only the reset.)
  await js(`window.__puts = 0; (() => { const o = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function (m, u, ...r) {
    if (m === 'PUT' && String(u).includes('fs/put')) window.__puts++; return o.call(this, m, u, ...r); }; })()`);
  const otherFilm = path.join(local, 'other', 'film.bin');
  fs.mkdirSync(path.dirname(otherFilm), { recursive: true });
  fs.writeFileSync(otherFilm, crypto.randomBytes(20 * 1024 * 1024));
  const bigTimes = fs.statSync(sendBig);
  fs.utimesSync(otherFilm, bigTimes.atime, bigTimes.mtime);
  const replaceWith = async (file) => {
    await js('window.__puts = 0');
    await dropFiles(list.x, list.y + 120, [file]);
    const asked = await until(() => js(`(() => { const d = document.querySelector('dialog.fx-dialog[open]'); return d ? d.querySelector('h2').textContent : null; })()`), 5000);
    await js(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Replace').click()`);
    const done = await until(() => js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === 'film.bin' && !x.dataset.seenByTest);
      if (!j || ['running', 'queued', 'preparing'].includes(j.dataset.state)) return null;
      j.dataset.seenByTest = '1'; return j.dataset.state + ' · ' + j.querySelector('.job-status').textContent; })()`), 20000);
    return { asked, done, puts: await js('window.__puts') };
  };
  const onLinux = () => fs.readFileSync(path.join(rootfs, 'root/Documents/film.bin'));
  const toOther = await replaceWith(otherFilm);
  const hasOther = onLinux().equals(fs.readFileSync(otherFilm));
  check('…a different 20 MB file with the same name, size and time, Replace: sent, and Linux has its bytes',
    toOther.asked === '“film.bin” is already in Documents' && /^done · /.test(toOther.done || '') && !/already there/.test(toOther.done || '')
      && toOther.puts === 1 && hasOther, JSON.stringify({ toOther, hasOther, tray: await trayState() }));
  const back = await replaceWith(sendBig);
  const hasFirst = onLinux().equals(fs.readFileSync(sendBig));
  check('…and the first one again, Replace: sent again, its bytes back',
    /^done · /.test(back.done || '') && back.puts === 1 && hasFirst, JSON.stringify({ back, hasFirst }));

  // Cancel, and Pause, pressed while the page asks the phone about a big file
  // (the `part` question before its PUT): nothing is sent, and the file there
  // stays as it was. DevTools holds the question in the browser until the
  // panel's button has been pressed with the mouse, then lets the phone's
  // answer through. Until 2026-11-22 the page sent the PUT once that answer
  // came, whatever had been pressed: a cancelled Replace still replaced the
  // file, and a paused one went on sending.
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/fs/part*', requestStage: 'Request' }] });
  /**
   * A job's button in the panel, where the mouse would press it: the panel
   * opened first with its own Fold button when it has folded itself away
   * (it does 6 s after a job finishes while nothing moves, and a paused job
   * is not moving), as a person would open it. Where the button is once the
   * panel has stopped moving (it slides open: a press read mid-slide lands
   * beside the button).
   */
  const panelButton = async (label, name = 'film.bin') => {
    if (await js(`!!document.querySelector('.tray.collapsed')`)) {
      const f = await center('.tray .tray-fold');
      if (f) await mouse(f.x, f.y);
    }
    const where = () => js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === ${JSON.stringify(name)} && !x.dataset.seenByTest);
      const b = j && j.querySelector('button[aria-label="${label}"]'); if (!b) return null; const r = b.getBoundingClientRect();
      return r.width > 0 && r.height > 0 ? { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) } : null; })()`);
    let last = null;
    return until(async () => {
      const at = await where();
      const still = at && last && at.x === last.x && at.y === last.y;
      last = at;
      return still ? at : null;
    }, 3000, 120);
  };
  const jobState = (name = 'film.bin') => js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === ${JSON.stringify(name)} && !x.dataset.seenByTest);
    return j ? j.dataset.state + ' · ' + j.querySelector('.job-status').textContent : 'gone'; })()`);
  /** Replace with {@code file}; once its question is held, {@code label} pressed; then the answer let through. */
  const pressWhileAsked = async (file, label) => {
    B.held.length = 0;
    await js('window.__puts = 0');
    await dropFiles(list.x, list.y + 120, [file]);
    await until(() => js(`!!document.querySelector('dialog.fx-dialog[open]')`), 5000);
    await js(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Replace').click()`);
    const asked = await until(() => B.held.find((p) => p.request.url.includes('/fs/part')), 8000);
    const at = await panelButton(label);
    if (at) await mouse(at.x, at.y);
    const after = await jobState();
    if (asked) await cdp('Fetch.continueRequest', { requestId: asked.requestId });
    await sleep(2500);
    return { asked: !!asked, fresh: !!asked && asked.request.url.includes('fresh=1'), pressed: at, after, puts: await js('window.__puts'), now: await jobState() };
  };
  const cancelled = await pressWhileAsked(otherFilm, 'Cancel');
  const keptOnCancel = onLinux().equals(fs.readFileSync(sendBig));
  check('Cancel pressed while the phone is asked about a big file: nothing is sent, and the file there stays as it was',
    cancelled.asked && !!cancelled.pressed && cancelled.puts === 0 && keptOnCancel,
    JSON.stringify({ cancelled, keptOnCancel }));
  await until(() => js(`![...document.querySelectorAll('.tray .job')].some((x) => x.querySelector('.job-name').textContent === 'film.bin' && !x.dataset.seenByTest)`), 5000);
  const paused = await pressWhileAsked(otherFilm, 'Pause');
  const keptOnPause = onLinux().equals(fs.readFileSync(sendBig));
  check('…Pause pressed then: nothing is sent while paused, and the file there stays as it was',
    paused.asked && !!paused.pressed && paused.puts === 0 && /^paused/.test(paused.now) && keptOnPause,
    JSON.stringify({ paused, keptOnPause }));
  // Resume: asked again (from what the phone has, not fresh), and then sent.
  B.held.length = 0;
  const play = await panelButton('Resume');
  if (play) await mouse(play.x, play.y);
  const again = await until(() => B.held.find((p) => p.request.url.includes('/fs/part')), 8000);
  if (again) await cdp('Fetch.continueRequest', { requestId: again.requestId });
  const resumed = await until(() => js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === 'film.bin' && !x.dataset.seenByTest);
    if (!j || ['running', 'queued', 'paused'].includes(j.dataset.state)) return null; j.dataset.seenByTest = '1'; return j.dataset.state + ' · ' + j.querySelector('.job-status').textContent; })()`), 20000);
  const hasResumed = onLinux().equals(fs.readFileSync(otherFilm));
  check('…Resume: asked again from what the phone has, then sent, and Linux has the new bytes',
    !!play && !!again && !again.request.url.includes('fresh=1') && /^done · /.test(resumed || '') && (await js('window.__puts')) === 1 && hasResumed,
    JSON.stringify({ play, again: again && again.request.url.replace(/^.*\?/, ''), resumed, puts: await js('window.__puts'), hasResumed, now: await jobState() }));
  await cdp('Fetch.disable');
  // The first film back, for the downloads below.
  const restored = await replaceWith(sendBig);
  check('…and the first film put back, Replace', /^done · /.test(restored.done || '') && onLinux().equals(fs.readFileSync(sendBig)), JSON.stringify(restored));

  // Two big files dropped together; one is refused while the other's body is
  // on its way, which stops that one too (the job failed). Try again sends
  // both. Until 2026-11-22 Try again sent only the refused one: the other
  // stayed stopped, and the job never finished. (The refusal is the server's
  // own: DevTools sends that file's question on to a folder that has gone,
  // as if the folder were deleted on the phone meanwhile. Not answered by
  // DevTools itself: Fetch.fulfillRequest makes Edge forget the "proceed"
  // given for the phone's certificate, and every new connection after it
  // fails ERR_CERT_AUTHORITY_INVALID, which broke the sign-out checks below.)
  const twinA = path.join(local, 'twin-a.bin');
  const twinB = path.join(local, 'twin-b.bin');
  fs.writeFileSync(twinA, crypto.randomBytes(20 * 1024 * 1024));
  fs.writeFileSync(twinB, crypto.randomBytes(20 * 1024 * 1024));
  const onLinuxAt = (name) => path.join(rootfs, 'root/Documents', name);
  B.held.length = 0;
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/fs/part*', requestStage: 'Request' }, { urlPattern: '*/fs/put*', requestStage: 'Request' }] });
  await dropFiles(list.x, list.y + 120, [twinA, twinB]);
  const heldFor = (op, name) => until(() => B.held.find((p) => p.request.url.includes(`/fs/${op}`) && p.request.url.includes(name)), 8000);
  const askA = await heldFor('part', 'twin-a.bin');
  const askB = await heldFor('part', 'twin-b.bin');
  if (askA) await cdp('Fetch.continueRequest', { requestId: askA.requestId });
  const putA = await heldFor('put', 'twin-a.bin');
  const goneUrl = askB && askB.request.url.replace('Documents%2Ftwin-b.bin', 'Documents%2Fgone%2Ftwin-b.bin');
  if (askB) await cdp('Fetch.continueRequest', { requestId: askB.requestId, url: goneUrl });
  // A drop of two files is the job "2 items" (files.js, sendDrop).
  const twinJob = () => jobState('2 items');
  const failedNow = await until(async () => ((await twinJob()).startsWith('failed') ? twinJob() : null), 6000);
  await cdp('Fetch.disable');
  const sentBefore = fs.existsSync(onLinuxAt('twin-a.bin')) || fs.existsSync(onLinuxAt('twin-b.bin'));
  const retryAt = await panelButton('Try again', '2 items');
  if (retryAt) await mouse(retryAt.x, retryAt.y);
  const twinsDone = await until(async () => ((await twinJob()).startsWith('done') ? twinJob() : null), 20000);
  const bothThere = fs.existsSync(onLinuxAt('twin-a.bin')) && fs.readFileSync(onLinuxAt('twin-a.bin')).equals(fs.readFileSync(twinA))
    && fs.existsSync(onLinuxAt('twin-b.bin')) && fs.readFileSync(onLinuxAt('twin-b.bin')).equals(fs.readFileSync(twinB));
  check('two big files, one refused while the other was sending: the job fails, and Try again sends both',
    !!askA && !!askB && goneUrl !== askB.request.url && !!putA && !!failedNow && !sentBefore && !!retryAt && !!twinsDone && bothThere,
    JSON.stringify({ askA: !!askA, askB: !!askB, redirected: !!askB && goneUrl !== askB.request.url, putA: !!putA, failedNow, sentBefore, retryAt, twinsDone, now: await twinJob(), bothThere }));
  await js(`[...document.querySelectorAll('.tray .job')].filter((x) => x.querySelector('.job-name').textContent === '2 items').forEach((x) => { x.dataset.seenByTest = '1'; })`);

  // Cancel while a drop is still being made ready (its names asked about with
  // the phone; a dropped folder read): nothing of the drop is sent. Until
  // 2026-11-22 the drop went anyway once the phone answered: the job was made
  // ready again over the Cancel.
  const prepFile = path.join(local, 'prep-cancel.txt');
  fs.writeFileSync(prepFile, 'never sent\n');
  await js(`window.__sends = 0; (() => { const o = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function (m, u, ...r) {
    if ((m === 'PUT' && String(u).includes('fs/put')) || (m === 'POST' && String(u).includes('fs/batch'))) window.__sends++; return o.call(this, m, u, ...r); }; })()`);
  B.held.length = 0;
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/fs/exists*', requestStage: 'Request' }] });
  await dropFiles(list.x, list.y + 120, [prepFile]);
  const named = await until(() => B.held.find((p) => p.request.url.includes('/fs/exists')), 8000);
  // The panel draws a new job on its next frame.
  const preparing = (await until(async () => { const st = await jobState('prep-cancel.txt'); return /^preparing/.test(st) ? st : null; }, 3000)) || (await jobState('prep-cancel.txt'));
  const stopPrep = await panelButton('Cancel', 'prep-cancel.txt');
  if (stopPrep) await mouse(stopPrep.x, stopPrep.y);
  if (named) await cdp('Fetch.continueRequest', { requestId: named.requestId });
  await sleep(2500);
  await cdp('Fetch.disable');
  const prepSent = fs.existsSync(path.join(rootfs, 'root/Documents/prep-cancel.txt'));
  check('Cancel while a drop is made ready (its names asked about): nothing of the drop is sent',
    !!named && /^preparing/.test(preparing) && !!stopPrep && (await js('window.__sends')) === 0 && !prepSent,
    JSON.stringify({ named: !!named, preparing, stopPrep, sends: await js('window.__sends'), prepSent, now: await jobState('prep-cancel.txt') }));

  // Save into a folder on this computer (Chrome's and Edge's folder picker;
  // here a folder of the page's own private storage, OPFS, handed over as
  // if picked: the same File System Access calls). Cancel while the folder
  // is being read: nothing is downloaded. Cancel while a file comes in: no
  // empty file is left where it was going. Until 2026-11-22 the first went
  // on and downloaded everything once the phone answered, and the second
  // left an empty file (made before the first byte, the bytes going to a
  // side file that a cancel throws away).
  await js(`window.__picker = window.showDirectoryPicker; window.showDirectoryPicker = async () => { const r = await navigator.storage.getDirectory();
    try { await r.removeEntry('picked', { recursive: true }); } catch {} return r.getDirectoryHandle('picked', { create: true }); }`);
  const inPicked = () => js(`(async () => { const r = await navigator.storage.getDirectory(); const out = [];
    const walk = async (d, pre) => { for await (const [n, h] of d.entries()) { if (h.kind === 'directory') await walk(h, pre + n + '/'); else out.push(pre + n + ' ' + (await h.getFile()).size); } };
    try { await walk(await r.getDirectoryHandle('picked'), ''); } catch { return null; } return out; })()`);
  const gets = () => js(`(window.__f || []).filter((x) => x[0] === 'start' && x[1].includes('fs/get')).length`);
  await js(`document.querySelectorAll('.tray .job').forEach((x) => { x.dataset.seenByTest = '1'; })`);
  /** Right-click on {@code name}'s row, then "Save into a folder on this computer…", with the mouse. */
  const saveIntoFolder = async (name) => {
    const r = await rowOf(name);
    if (!r) return 'no row';
    await mouse(r.x, r.y);
    await mouse(r.x, r.y, { button: 'right' });
    const item = await until(() => js(`(() => { const b = [...document.querySelectorAll('.fx-menu [role=menuitem]')].find((x) => x.textContent.includes('Save into a folder on this computer'));
      if (!b) return null; const q = b.getBoundingClientRect(); return { x: Math.round(q.left + q.width / 2), y: Math.round(q.top + q.height / 2) }; })()`), 3000);
    if (!item) return 'no menu item';
    await mouse(item.x, item.y);
    return 'chosen';
  };
  B.held.length = 0;
  const getsBefore = await gets();
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/fs/list*', requestStage: 'Request' }] });
  const chose = await saveIntoFolder('Holiday');
  const reading = await until(() => B.held.find((p) => p.request.url.includes('/fs/list') && p.request.url.includes('Holiday')), 8000);
  const folderJob = (await until(async () => { const st = await jobState('Holiday'); return /^preparing/.test(st) ? st : null; }, 3000)) || (await jobState('Holiday'));
  const stopRead = await panelButton('Cancel', 'Holiday');
  if (stopRead) await mouse(stopRead.x, stopRead.y);
  // Only that first question held: the rest of the folder is read at once,
  // well before a cancelled job leaves the panel (1.8 s), as on a quick link.
  for (const p of B.held) await cdp('Fetch.continueRequest', { requestId: p.requestId });
  await cdp('Fetch.disable');
  for (const p of B.held) await cdp('Fetch.continueRequest', { requestId: p.requestId });
  await sleep(2500);
  const afterRead = await inPicked();
  check('Save into a folder, Cancel while the folder is read: nothing is downloaded',
    chose === 'chosen' && !!reading && /^preparing/.test(folderJob) && !!stopRead && (await gets()) === getsBefore && Array.isArray(afterRead) && afterRead.length === 0,
    JSON.stringify({ chose, reading: !!reading, folderJob, stopRead, gets: (await gets()) - getsBefore, afterRead, now: await jobState('Holiday') }));
  // A file coming in slowly (the browser's own throttling), and Cancel.
  await cdp('Network.enable');
  await cdp('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: 1024 * 1024, uploadThroughput: -1 });
  const choseFilm = await saveIntoFolder('film.bin');
  const coming = await until(async () => { const s = await jobState('film.bin'); return /^running · [1-9]/.test(s) ? s : null; }, 10000);
  const stopFilm = await panelButton('Cancel', 'film.bin');
  if (stopFilm) await mouse(stopFilm.x, stopFilm.y);
  await sleep(1500);
  await cdp('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  if (!process.env.NETLOG) await cdp('Network.disable');
  const afterFilm = await inPicked();
  check('…Cancel while a file comes in: no file is left where it was going',
    choseFilm === 'chosen' && !!coming && !!stopFilm && Array.isArray(afterFilm) && afterFilm.length === 0,
    JSON.stringify({ choseFilm, coming, stopFilm, afterFilm, now: await jobState('film.bin') }));
  await js(`window.showDirectoryPicker = window.__picker`);

  // The file picker, as Upload's menu opens it.
  const picked = path.join(local, 'picked.txt');
  fs.writeFileSync(picked, 'picked by hand\n');
  const input = await cdp('Runtime.evaluate', { expression: `document.querySelectorAll('#files input[type=file]')[0]` });
  const node = await cdp('DOM.describeNode', { objectId: input.result.result.objectId });
  await cdp('DOM.setFileInputFiles', { files: [picked], backendNodeId: node.result.node.backendNodeId });
  check('Upload, Files…: the chosen file lands', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/picked.txt')), 8000)));

  // Rename in place, F2.
  await until(async () => (await rowsNow()).includes('picked.txt'), 6000);
  const pr = await rowOf('picked.txt');
  await mouse(pr.x, pr.y);
  await key('F2');
  await until(() => js(`!!document.querySelector('.fx-rename')`), 3000);
  await js(`(() => { const f = document.querySelector('.fx-rename'); f.value = 'chosen.txt'; })()`);
  await key('Enter');
  check('F2 renames where it is', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/chosen.txt')), 5000)));

  // Delete goes to the Trash; Undo brings it back.
  await until(async () => (await rowsNow()).includes('chosen.txt'), 6000);
  const ch = await rowOf('chosen.txt');
  await mouse(ch.x, ch.y);
  await key('Delete');
  const inTrash = await until(() => fs.existsSync(path.join(rootfs, 'root/.local/share/Trash/files/chosen.txt')), 5000);
  const snackText = await until(() => js(`(() => { const s = document.querySelector('.snack.show'); return s ? s.textContent : null; })()`), 3000);
  check('Delete: into Linux\'s Trash, said with an Undo', !!inTrash && /in the Trash.*Undo/.test(snackText || ''), snackText);
  await shot('04-undo');
  await js(`document.querySelector('.snack.show .snack-act').click()`);
  check('…Undo: back where it was', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/chosen.txt'))
    && !fs.existsSync(path.join(rootfs, 'root/.local/share/Trash/files/chosen.txt')), 5000)));

  // Quick look: text with its line numbers; an HTML file shown, not run.
  await until(async () => (await rowsNow()).includes('page.html'), 6000);
  const ph = await rowOf('page.html');
  await mouse(ph.x, ph.y);
  await key('Space');
  const shown = await until(() => js(`(() => { const c = document.querySelector('dialog.peek[open] .code'); return c ? c.textContent : null; })()`), 6000);
  check('Space: a quick look at an HTML file of Linux\'s shows its text, and runs none of it',
    shown && shown.includes('<script>') && (await js(`window.ownedBy === undefined`)), shown);
  await key('Escape');
  await until(() => js(`!document.querySelector('dialog.peek[open]')`), 3000);

  // Pictures, in the grid, with the phone's small pictures; a photo and a video seen.
  const pics = await rowOf('Pictures') || null;
  await js(`[...document.querySelectorAll('#files .place')].find((p) => p.textContent === 'Pictures').click()`);
  await until(async () => (await rowsNow()).includes('photo.png'), 5000);
  await js(`document.querySelectorAll('#files .seg-btn')[1].click()`);
  const thumb = await until(() => js(`(() => { const t = [...document.querySelectorAll('#files .fx-tile')].find((x) => x.querySelector('.fx-tname').textContent === 'photo.png');
    const i = t && t.querySelector('img'); return i && i.complete && i.naturalWidth > 0 ? i.naturalWidth : 0; })()`), 6000);
  check('the grid: a photo wears its own small picture', thumb === 320, String(thumb));
  await shot('05-grid');
  const tileP = await rowOf('photo.png');
  await mouse(tileP.x, tileP.y, { count: 2 });
  const img = await until(() => js(`(() => { const i = document.querySelector('dialog.peek[open] .peek-img'); return i && i.complete && i.naturalWidth ? i.naturalWidth : 0; })()`), 6000);
  check('a photo, seen whole', img === 320, String(img));
  await shot('06-quicklook');
  await key('Escape');
  void pics;
  await js(`[...document.querySelectorAll('#files .place')].find((p) => p.textContent === 'Videos').click()`);
  await until(async () => (await rowsNow()).includes('clip.mp4'), 5000);
  const tileV = await rowOf('clip.mp4');
  await mouse(tileV.x, tileV.y, { count: 2 });
  const played = await until(() => js(`(() => { const v = document.querySelector('dialog.peek[open] video'); return v && v.readyState >= 2 && v.duration > 1 ? v.duration : 0; })()`), 8000);
  check('a video plays from the phone as it comes (Range), seekable', played > 1.5, String(played) + ' ' + JSON.stringify(await js(`(() => { const d = document.querySelector('dialog.peek'); if (!d) return 'no peek';
    const v = d.querySelector('video'); return { open: d.open, stage: d.querySelector('.peek-stage').className, text: d.querySelector('.peek-stage').textContent.slice(0, 120),
      video: v ? { rs: v.readyState, dur: v.duration, err: v.error && v.error.code, src: (v.currentSrc || '').slice(0, 60), net: v.networkState } : null,
      tiles: [...document.querySelectorAll('#files .fx-tile .fx-tname')].map((x) => x.textContent), crumb: (document.querySelector('#files .crumb.here') || {}).textContent }; })()`)));
  await shot('video-check');
  await js(`(() => { const v = document.querySelector('dialog.peek[open] video'); v.currentTime = 1.5; })()`);
  check('…and seeks', !!(await until(() => js(`(() => { const v = document.querySelector('dialog.peek[open] video'); return v && v.currentTime >= 1.4 && v.readyState >= 2; })()`), 5000)));
  await key('Escape');
  await js(`document.querySelectorAll('#files .seg-btn')[0].click()`);

  // Downloads: one file as the browser's own download; a folder as a ZIP.
  await js(`[...document.querySelectorAll('#files .place')].find((p) => p.textContent === 'Documents').click()`);
  await until(async () => (await rowsNow()).includes('film.bin'), 5000);
  const fb = await rowOf('film.bin');
  await mouse(fb.x, fb.y);
  await js(`document.querySelector('#files .sel-dl').click()`);
  const gotFile = await until(() => fs.existsSync(path.join(dlDir, 'film.bin')) && fs.statSync(path.join(dlDir, 'film.bin')).size === 20 * 1024 * 1024, 15000);
  check('Download: the file, where this browser saves downloads, byte for byte',
    !!gotFile && fs.readFileSync(path.join(dlDir, 'film.bin')).equals(fs.readFileSync(sendBig)));
  const hol = await rowOf('Holiday');
  await mouse(hol.x, hol.y);
  await js(`document.querySelector('#files .sel-dl').click()`);
  const zipPath = path.join(dlDir, 'Holiday.zip');
  const gotZip = await until(() => fs.existsSync(zipPath) && !fs.readdirSync(dlDir).some((n) => n.endsWith('.crdownload')), 15000);
  const zipRead = gotZip ? readZip(fs.readFileSync(zipPath)) : { ok: false, error: 'no zip' };
  check('…a folder: one ZIP, everything in it, read back', zipRead.ok && zipRead.names.includes('Holiday/day one/photo 24.txt') && zipRead.names.includes('Holiday/empty/'), zipRead.error);

  // Search: this folder at once; Enter, every folder under it.
  await typeInto('#fx-q', 'photo 1');
  const filtered = await rowsNow();
  await js(`document.getElementById('fx-q').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))`);
  const deep = await until(async () => {
    const r = await rowsNow();
    return r.includes('photo 12.txt') ? r : null;
  }, 6000);
  check('search: nothing in this folder by that name; Enter finds it in the folders under it', filtered.length === 0 && !!deep && deep.length === 11,
    JSON.stringify([filtered, deep]));
  await shot('07-search');
  await js(`document.getElementById('fx-q').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);

  // The Trash, as a place: what is in it, and putting it back.
  const pg = await until(() => rowOf('page.html'), 4000);
  await mouse(pg.x, pg.y);
  await key('Delete');
  await until(() => fs.existsSync(path.join(rootfs, 'root/.local/share/Trash/files/page.html')), 5000);
  await js(`[...document.querySelectorAll('#files .place')].find((p) => p.textContent === 'Trash').click()`);
  await settled('Trash');
  const trashRows = await until(async () => {
    const r = await rowsNow();
    return r.includes('page.html') ? r : null;
  }, 5000);
  check('the Trash shows what was deleted, and where from', !!trashRows
    && (await js(`[...document.querySelectorAll('#files .fx-row .fx-kind')].map((e) => e.textContent).includes('/root/Documents')`)));
  await shot('08-trash');
  const tp = await rowOf('page.html');
  await mouse(tp.x, tp.y);
  await js(`document.querySelector('#files .sel-restore').click()`);
  check('…Put back: where it came from', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/page.html')), 5000)));

  // The keyboard: up a folder, into one, a new folder named as it is made.
  await js(`[...document.querySelectorAll('#files .place')].find((p) => p.textContent === 'Documents').click()`);
  await until(async () => (await rowsNow()).includes('Holiday'), 5000);
  await js(`document.querySelector('#files .fx-scroll').focus()`);
  await key('N', 2 | 8);
  await until(() => js(`!!document.querySelector('.fx-rename')`), 4000);
  await js(`(() => { const f = document.querySelector('.fx-rename'); f.value = 'Made here'; })()`);
  await key('Enter');
  check('Ctrl+Shift+N: a new folder, named where it appears', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Documents/Made here')), 5000)));
  await js(`document.querySelector('#files .fx-scroll').focus()`);
  await key('Backspace');
  const upHome = await settled('Home');
  check('Backspace: up to the home folder, the folder it came from chosen', !!upHome
    && (await js(`[...document.querySelectorAll('#files .fx-row.sel .n')].map((e) => e.textContent).join()`)) === 'Documents');
  await key('Enter');
  check('Enter: into it again', !!(await settled('Documents')));

  // A sign-out on the phone, met by the Files: the page asks for the code again.
  const connsBefore = await command('conns', 'CONNS');
  await command('signout', 'CODE');
  await js(`document.querySelector('#files .fx-btn[aria-label="Up one folder"]').click()`);
  const askedAgain = await until(() => js(`!document.getElementById('pair').hidden && !document.body.classList.contains('files-on')`), 6000);
  check('signed out on the phone: the next step in Files asks for the code', !!askedAgain, JSON.stringify(await js(`({ pair: !document.getElementById('pair').hidden,
    body: document.body.className, hash: location.hash, status: (document.querySelector('#files .fx-status') || {}).textContent,
    empty: (document.querySelector('#files .fx-empty:not([hidden]) h3') || {}).textContent, snack: (document.querySelector('.snack.show') || {}).textContent,
    fetches: (window.__f || []).slice(-8) })`)) + ` ${connsBefore} → ${await command('conns', 'CONNS')}`
);
  if (process.env.NETLOG && !askedAgain) console.log(`    net errors: ${JSON.stringify(B.netlog.filter((x) => x.err).slice(-6).map((x) => [x.url, x.err]))}`);
  const code2 = (await command('code', 'CODE')).slice(5);
  await typeInto('#code', code2);
  check('…and the code brings them back to Files', !!(await until(() => js(`!!document.querySelector('#files.on') && document.querySelectorAll('#files .fx-row').length > 0`), 8000)));

  // Linux not installed: said, and the way back once it is.
  await command('fsstate missing', 'OK fsstate');
  await js(`window.__old = true; location.reload()`);
  await until(() => js(`window.__old === undefined && document.readyState === 'complete'`), 8000);
  const missing = await until(() => js(`(() => { const e = document.querySelector('#files .fx-empty h3'); return e && !document.querySelector('#files .fx-empty').hidden ? e.textContent : null; })()`), 8000);
  check('Linux not installed: Files says so', missing === 'Linux is not installed yet', JSON.stringify({ missing, cls: await js(`document.getElementById('files').className`),
    crumbs: await js(`[...document.querySelectorAll('#files .crumb')].map((e) => e.textContent)`), hash: await js('location.hash'), title: await js('document.title'), href: await js('location.href'), state: await command('state?', 'STATE') }));
  await shot('10-missing');
  await command('fsstate ready', 'OK fsstate');
  await js(`[...document.querySelectorAll('#files .fx-empty button')].find((b) => b.textContent === 'Try again').click()`);
  check('…and Try again shows its files once it is', !!(await until(() => js(`document.querySelectorAll('#files .fx-row').length > 0`), 6000)));

  // The desktop: a file dropped on its picture lands on Linux's desktop.
  await js(`document.querySelector('#files .fx-desk').click()`);
  const upDesk = await until(() => js(`document.body.classList.contains('up') && !document.body.classList.contains('files-on')`), 12000);
  check('Desktop: the picture again, the stream asked for by this person', !!upDesk);
  const deskFile = path.join(local, 'for the desktop.txt');
  fs.writeFileSync(deskFile, 'dropped on the picture\n');
  const sc = await center('#screen');
  const deskSaid = await dropFiles(sc.x, sc.y, [deskFile]);
  check('dragged over the desktop\'s picture, it says it goes on Linux\'s desktop', deskSaid === 'Drop to put the files on Linux’s desktop', deskSaid);
  check('…and it is there', !!(await until(() => fs.existsSync(path.join(rootfs, 'root/Desktop/for the desktop.txt')), 8000)));
  await sleep(600);
  await shot('09-desktop-drop');
  check('the page threw nothing along the way', pageErrors.length === 0, pageErrors.join(' | '));
}

// ── on a real phone ─────────────────────────────────────────────────────

/**
 * --phone <serial>: the same, against LinuxOnDroid itself on a phone over its
 * Wi-Fi, its real Linux, Android's own thumbnails. Needs the debuggable
 * build (built with -PdebuggableLauncher), "Open in a browser" switched on,
 * and the phone on adb. Pairs a browser of its own without a tap (the code
 * read with run-as, the pairing window opened as the card opens it, and put
 * back as it was), works in one folder of its own under the home folder, and
 * leaves nothing: that folder deleted, its own Trash items deleted (never the
 * rest of the Trash), its browser signed out, the phone's list of browsers as
 * it was.
 */
async function onPhone(serial) {
  const PKG = 'com.ccrstech.linuxondroid';
  const sh = (cmd) => spawnSync('adb', ['-s', serial, 'shell', cmd], { encoding: 'utf8' });
  const ras = (cmd) => sh(`run-as ${PKG} sh -c "${cmd}"`);
  const ip = (/inet (\d+\.\d+\.\d+\.\d+)/.exec(sh('ip -4 addr show wlan0').stdout) || [])[1];
  check('the phone is on adb, with an address on its Wi-Fi', !!ip, sh('ip -4 addr show wlan0').stderr);
  if (!ip) return;
  const asked = ras('cat files/browser/state');
  check('the build is debuggable (run-as) and the phone is listening for browsers', asked.status === 0 && /^(listening|watching) 7443/.test(asked.stdout.trim()),
    `${asked.status} ${asked.stdout} ${asked.stderr}`);
  if (asked.status !== 0) return;
  const tokensBefore = ras('cat files/browser/tokens').stdout;
  const pairingBefore = ras('cat files/browser/pairing-until 2>/dev/null').stdout.trim();
  const code = ras('cat files/browser/code').stdout.trim().split(/\s+/)[0];
  // The card shows the code: a code works from then for ten minutes.
  ras(`echo ${Date.now() + 120000} > files/browser/pairing-until`);
  const BASEP = `https://${ip}:7443`;
  const agent = new https.Agent({ keepAlive: true, maxSockets: 4, rejectUnauthorized: false });
  const req = (method, p, { headers = {}, body = null } = {}) => new Promise((resolve) => {
    const all = { ...headers };
    if (body != null && all['Content-Length'] == null) all['Content-Length'] = Buffer.byteLength(body);
    const r = https.request({ host: ip, port: 7443, method, path: p, headers: all, agent }, (res) => {
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
    });
    r.on('error', (e) => resolve({ status: 0, error: e.message }));
    if (body != null) r.write(body);
    r.end();
  });
  const pairR = await req('POST', '/pair', { headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: BASEP }, body: formOf({ code }) });
  if (pairingBefore) ras(`echo ${pairingBefore} > files/browser/pairing-until`);
  else ras('rm -f files/browser/pairing-until');
  const tok = pairR.json && pairR.json.token;
  check('a test browser let in with the phone\'s code, no tap; the pairing window put back as it was', !!tok, JSON.stringify(pairR.json));
  if (!tok) return;
  const A = { Authorization: `Bearer ${tok}` };
  const W = { ...A, Origin: BASEP };
  const F = { 'Content-Type': 'application/x-www-form-urlencoded' };
  const get = (op, q = {}) => req('GET', `/fs/${op}?${formOf(q)}`, { headers: A });
  const post = (op, form = {}, q = {}) => req('POST', `/fs/${op}?${formOf(q)}`, { headers: { ...W, ...F }, body: formOf(form) });
  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
  const dir = `/root/Files test ${stamp}`;
  const trashedIds = [];
  try {
    console.log(`the phone's own Linux, at ${BASEP}`);
    const info = await get('info');
    const ids = info.json ? info.json.places.map((p) => p.id) : [];
    console.log(`        places: ${ids.join(', ')}; ${fmtGB(info.json && info.json.free)} free of ${fmtGB(info.json && info.json.total)}`);
    check('Files on the phone: its places, and the room left on the phone', ids.includes('home') && ids.includes('root') && ids.includes('trash') && info.json.free > 0, JSON.stringify(info.json));
    const root = await get('list', { path: '/' });
    const rootNames = (root.json && root.json.entries || []).map((e) => e.n);
    check('Linux\'s / on the phone: usr, etc and root, and never /proc, /sys or /dev', ['usr', 'etc', 'root'].every((n) => rootNames.includes(n))
      && !['proc', 'sys', 'dev'].some((n) => rootNames.includes(n)), rootNames.join());
    const bin = await get('list', { path: '/bin' });
    check('/bin, Ubuntu\'s link to usr/bin, opens as /usr/bin', bin.json && bin.json.path === '/usr/bin' && bin.json.entries.length > 50, bin.json && bin.json.path);
    const alt = await get('list', { path: '/etc/alternatives' });
    const altLinks = (alt.json && alt.json.entries || []).filter((e) => e.t === 'l');
    const altInside = altLinks.filter((e) => e.lt === 'f' || e.lt === 'd');
    check('/etc/alternatives: absolute links, each followed inside Linux to what it names', altLinks.length > 5 && altInside.length >= altLinks.length * 0.8,
      `${altInside.length} of ${altLinks.length}`);
    const l2s = ras("find files/linux/rootfs/usr -name '.l2s.*' 2>/dev/null | head -1").stdout.trim();
    if (l2s) {
      const where = '/' + l2s.replace(/^files\/linux\/rootfs\//, '').replace(/\/[^/]+$/, '');
      const l = await get('list', { path: where });
      check(`proot's hard links (.l2s.*, in ${where}) are hidden, and the names that share them read as files`,
        l.json && !l.json.entries.some((e) => e.n.startsWith('.l2s.')), where);
    } else {
      console.log('        (no .l2s. files in /usr on this phone: proot\'s hard links not seen)');
    }
    const mk = await post('mkdir', { path: dir });
    check(`a folder of the test's own: ${dir}`, mk.json && mk.json.path === dir, JSON.stringify(mk.json));

    // Speed over the phone's Wi-Fi, both ways.
    const big = crypto.randomBytes(256 * 1024 * 1024);
    const mtime = Date.now() - 60000;
    let t0 = Date.now();
    const up = await req('PUT', `/fs/put?${formOf({ path: `${dir}/big.bin`, size: big.length, mtime })}`, { headers: W, body: big });
    const upS = (Date.now() - t0) / 1000;
    t0 = Date.now();
    const down = await get('get', { path: `${dir}/big.bin` });
    const downS = (Date.now() - t0) / 1000;
    const sameBytes = down.buf && crypto.createHash('sha256').update(down.buf).digest('hex') === crypto.createHash('sha256').update(big).digest('hex');
    check('256 MB to the phone\'s Linux and back over Wi-Fi, byte for byte', up.json && up.json.done && sameBytes, JSON.stringify(up.json));
    console.log(`        up ${(256 / upS).toFixed(1)} MB/s (${upS.toFixed(1)} s), down ${(256 / downS).toFixed(1)} MB/s (${downS.toFixed(1)} s)`);
    const onPhoneSize = ras(`stat -c %s 'files/linux/rootfs${dir}/big.bin'`).stdout.trim();
    check('…the file in the rootfs on the phone, its size exact', Number(onPhoneSize) === big.length, onPhoneSize);

    // A thousand small files: what a folder of documents or code is.
    const items = [];
    for (let i = 0; i < 1000; i++) items.push({ rel: `many/part ${i % 10}/file ${i}.txt`, data: crypto.randomBytes(1000 + (i % 20) * 900) });
    const total = items.reduce((n, x) => n + x.data.length, 0);
    t0 = Date.now();
    const bt = await req('POST', `/fs/batch?${formOf({ dir, on: 'replace', total })}`, { headers: { ...W, 'Content-Type': 'application/octet-stream' }, body: batchBody(items, mtime) });
    const btS = (Date.now() - t0) / 1000;
    check('a thousand small files in ten folders, one request', bt.json && bt.json.done === 1000, JSON.stringify(bt.json).slice(0, 200));
    console.log(`        1,000 files, ${(total / 1e6).toFixed(1)} MB, in ${btS.toFixed(2)} s`);

    // Resumed: half, then the rest.
    const mid = crypto.randomBytes(32 * 1024 * 1024);
    const q = { path: `${dir}/resumed.bin`, size: mid.length, mtime };
    const half = 16 * 1024 * 1024 + 5;
    await req('PUT', `/fs/put?${formOf({ ...q, off: 0 })}`, { headers: { ...W, 'Content-Length': half }, body: mid.subarray(0, half) });
    const have = await get('part', q);
    const rest = await req('PUT', `/fs/put?${formOf({ ...q, off: have.json.have })}`, { headers: W, body: mid.subarray(have.json.have) });
    const back = await get('get', { path: `${dir}/resumed.bin` });
    check('half a file, then the rest from where the phone says it got to: whole', have.json.have === half && rest.json.done && back.buf.equals(mid));

    // The phone's own small pictures: a photo, a video, a song's cover, a PDF.
    const media = path.join(tmp, 'phone-media');
    fs.mkdirSync(media, { recursive: true });
    const ffq = (a) => spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...a], { encoding: 'utf8' });
    ffq(['-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=1', '-frames:v', '1', '-q:v', '3', path.join(media, 'photo.jpg')]);
    ffq(['-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=30', '-t', '3', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', path.join(media, 'clip.mp4')]);
    ffq(['-f', 'lavfi', '-i', 'sine=frequency=330:sample_rate=44100', '-t', '3', '-i', path.join(media, 'photo.jpg'), '-map', '0', '-map', '1',
      '-c:a', 'libmp3lame', '-c:v', 'mjpeg', '-id3v2_version', '3', '-disposition:v', 'attached_pic', path.join(media, 'song.mp3')]);
    fs.writeFileSync(path.join(media, 'doc.pdf'), tinyPdf('Linux files'));
    for (const n of ['photo.jpg', 'clip.mp4', 'song.mp3', 'doc.pdf']) {
      const data = fs.readFileSync(path.join(media, n));
      await req('PUT', `/fs/put?${formOf({ path: `${dir}/${n}`, size: data.length, mtime })}`, { headers: W, body: data });
    }
    for (const n of ['photo.jpg', 'clip.mp4', 'song.mp3', 'doc.pdf']) {
      const t = await get('thumb', { path: `${dir}/${n}`, s: 256 });
      const dims = jpegSize(t.buf);
      check(`the phone's own decoder makes ${n}'s small picture (${dims ? `${dims.w}x${dims.h}` : 'none'})`,
        t.status === 200 && /^image\/(jpeg|png)$/.test(t.headers['content-type'] || '') && (!dims || Math.max(dims.w, dims.h) <= 256), `${t.status} ${t.headers && t.headers['content-type']}`);
    }
    const rangeV = await req('GET', `/fs/get?${formOf({ path: `${dir}/clip.mp4` })}`, { headers: { ...A, Range: 'bytes=100-1099' } });
    check('a video by ranges, as a browser seeks one', rangeV.status === 206 && rangeV.buf.length === 1000);

    // A ZIP of the folder, read back by this computer's unzipper.
    t0 = Date.now();
    const zt = await post('ticket', { kind: 'zip', p: [`${dir}/many`, `${dir}/photo.jpg`], name: 'phone.zip' });
    const zr = await req('GET', `/fs/zip?k=${zt.json.k}`);
    const zS = (Date.now() - t0) / 1000;
    const zread = readZip(zr.buf);
    const zun = unzipWithTar(zr.buf, 'phone-zip');
    check('a ZIP of a thousand files and a photo, made on the phone, read back by the test and by tar',
      zread.ok && zread.names.length === 1000 + 11 + 1 && zun.ok, zread.error || zun.error);
    console.log(`        ZIP ${(zr.buf.length / 1e6).toFixed(1)} MB in ${zS.toFixed(2)} s`);

    // The Trash, Linux's own, on the phone.
    const tr = await post('trash', { p: `${dir}/doc.pdf` });
    const id = tr.json && tr.json.trashed[0] && tr.json.trashed[0].id;
    if (id) trashedIds.push(id);
    const inTrash = ras(`ls 'files/linux/rootfs/root/.local/share/Trash/files/${id}' 'files/linux/rootfs/root/.local/share/Trash/info/${id}.trashinfo'`);
    check('into Linux\'s Trash on the phone, with its .trashinfo', !!id && inTrash.status === 0, inTrash.stderr);
    const un = await post('untrash', { id });
    if (un.json && un.json.restored.length) trashedIds.pop();
    check('…and back', un.json && un.json.restored[0] === `${dir}/doc.pdf`);

    // The phone's shared folder, where this build has it.
    const sharedPlace = info.json.places.find((p) => p.id === 'shared');
    if (sharedPlace) {
      const n = `files-test-${stamp}.txt`;
      const data = Buffer.from('from the computer, through Linux\'s shared folder\n');
      const sp = await req('PUT', `/fs/put?${formOf({ path: `${sharedPlace.path}/${n}`, size: data.length, mtime })}`, { headers: W, body: data });
      const seen = sh(`cat "/sdcard/LinuxOnDeX/${n}"`).stdout;
      check('into the phone\'s shared folder: there on the phone itself (/sdcard/LinuxOnDeX)', sp.json && sp.json.done && seen === data.toString());
      const st = await post('trash', { p: `${sharedPlace.path}/${n}` });
      check('…which Linux\'s Trash cannot take: said', st.json && st.json.cannot.length === 1, JSON.stringify(st.json));
      const del = await post('delete', { p: `${sharedPlace.path}/${n}` });
      check('…and deleted for good when asked: gone from the phone too', del.json && del.json.deleted.length === 1 && sh(`ls "/sdcard/LinuxOnDeX/${n}" 2>&1`).stdout.includes('No such'));
    } else {
      console.log('        (no shared folder in this build or no grant: not tried)');
    }

    if (!args.includes('--no-page')) await phonePage(BASEP, tok, dir);
  } finally {
    // Nothing of the test's left: its folder, its own Trash items, its browser.
    const gone = await post('delete', { p: dir });
    for (const id of trashedIds) await post('delete', { p: `/root/.local/share/Trash/files/${id}` });
    check('the test\'s folder deleted, and nothing of it left in the Trash', gone.json && gone.json.deleted.length === 1
      && ras(`ls -d 'files/linux/rootfs${dir}' 2>&1`).stdout.includes('No such'), JSON.stringify(gone.json));
    await req('POST', '/forget', { headers: { ...F, Origin: BASEP }, body: formOf({ token: tok }) });
    const tokensAfter = ras('cat files/browser/tokens').stdout;
    check('the test browser signed out; the phone\'s browsers exactly as before', tokensAfter === tokensBefore,
      `${tokensBefore.split('\n').filter(Boolean).length} before, ${tokensAfter.split('\n').filter(Boolean).length} after`);
    agent.destroy();
  }
}

/** Files in a browser window against the phone itself: what a person sees, in pictures for the record. */
async function phonePage(base, token, dir) {
  const Bp = await launchBrowser();
  check('a browser for the phone\'s page', !!Bp);
  if (!Bp) return;
  const { cdp: c, js: j, shot: s, pageErrors: errs } = Bp;
  await c('Page.navigate', { url: `${base}/` });
  await sleep(1200);
  await j(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
  await sleep(300);
  await j(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
  await until(() => j(`location.href.startsWith('${base}') && document.readyState === 'complete'`), 10000);
  // This browser holds the test's token, as one that typed the code does.
  await j(`localStorage.setItem('lod.token', ${JSON.stringify(token)}); localStorage.setItem('lod.fx.mode', 'list')`);
  await c('Page.navigate', { url: `${base}/#files` });
  const home = await until(() => j(`document.querySelectorAll('#files .fx-row').length > 2 ? [...document.querySelectorAll('#files .fx-row .n')].map((e) => e.textContent) : null`), 15000);
  check('the page on the phone: Files opens on Linux\'s home', !!home, JSON.stringify(home));
  await sleep(600);
  await s('phone-01-home');
  await j(`localStorage.setItem('lod.fx.mode', 'grid')`);
  await c('Page.navigate', { url: `${base}/#files=${encodeURI(dir)}` });
  const thumbs = await until(() => j(`(() => { const imgs = [...document.querySelectorAll('#files .fx-thumb img')].filter((i) => i.complete && i.naturalWidth > 0); return imgs.length >= 3 ? imgs.length : 0; })()`), 15000);
  check('the grid wears the phone\'s small pictures: the photo, the video, the PDF (and the song\'s cover)', thumbs >= 3, String(thumbs));
  await sleep(800);
  await s('phone-02-grid');
  const tile = await j(`(() => { const t = [...document.querySelectorAll('#files .fx-tile')].find((x) => x.querySelector('.fx-tname').textContent === 'clip.mp4'); if (!t) return null; const r = t.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + 50 }; })()`);
  if (tile) {
    await c('Input.dispatchMouseEvent', { type: 'mouseMoved', x: tile.x, y: tile.y });
    for (const n of [1, 2]) {
      await c('Input.dispatchMouseEvent', { type: 'mousePressed', x: tile.x, y: tile.y, button: 'left', buttons: 1, clickCount: n });
      await c('Input.dispatchMouseEvent', { type: 'mouseReleased', x: tile.x, y: tile.y, button: 'left', buttons: 0, clickCount: n });
    }
    const played = await until(() => j(`(() => { const v = document.querySelector('dialog.peek[open] video'); return v && v.readyState >= 2 && v.currentTime > 0.3 ? v.currentTime : 0; })()`), 12000);
    check('a video of Linux\'s plays in the page, from the phone', played > 0.3, String(played));
    await s('phone-03-video');
    await c('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await c('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  }
  // A folder dropped from this computer.
  const local = path.join(tmp, 'drop-to-phone');
  fs.mkdirSync(path.join(local, 'Trip/day 1'), { recursive: true });
  for (let i = 0; i < 40; i++) fs.writeFileSync(path.join(local, 'Trip/day 1', `note ${i}.txt`), `note ${i}\n`);
  const at = await j(`(() => { const r = document.querySelector('#files .fx-scroll').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height - 80 }; })()`);
  const data = { items: [], files: [path.join(local, 'Trip')], dragOperationsMask: 1 };
  await c('Input.dispatchDragEvent', { type: 'dragEnter', x: at.x, y: at.y, data });
  await c('Input.dispatchDragEvent', { type: 'dragOver', x: at.x, y: at.y, data });
  await sleep(300);
  await s('phone-04-drop');
  await c('Input.dispatchDragEvent', { type: 'drop', x: at.x, y: at.y, data });
  const sh2 = (cmd) => spawnSync('adb', ['-s', argOf('--phone'), 'shell', cmd], { encoding: 'utf8' });
  const landed = await until(() => {
    const r = sh2(`run-as com.ccrstech.linuxondroid sh -c "ls 'files/linux/rootfs${dir}/Trip/day 1' | wc -l"`);
    return Number(r.stdout.trim()) === 40;
  }, 15000, 500);
  check('a folder dropped onto the page: forty files in Linux on the phone', !!landed);
  await sleep(1200);
  await s('phone-05-sent');
  check('the page threw nothing', errs.length === 0, errs.join(' | '));
  await Bp.kill();
}

function fmtGB(n) {
  return n ? `${(n / 1e9).toFixed(1)} GB` : '?';
}

/** A JPEG's or PNG's width and height, from its header. */
function jpegSize(b) {
  if (!b || b.length < 24) return null;
  if (b[0] === 0x89 && b[1] === 0x50) return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) return null;
    const m = b[i + 1];
    const len = b.readUInt16BE(i + 2);
    if (m >= 0xc0 && m <= 0xc3) return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    i += 2 + len;
  }
  return null;
}

/** A one-page PDF with a line of text, its cross-reference table exact. */
function tinyPdf(text) {
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    null,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  const stream = `BT /F1 28 Tf 30 300 Td (${text}) Tj ET`;
  objs[3] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`;
  let out = '%PDF-1.4\n';
  const offs = [];
  objs.forEach((o, i) => {
    offs.push(out.length);
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offs.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

// ── helpers ─────────────────────────────────────────────────────────────

/** The body of a batch: records as transfer.js makes them, built here independently. */
function batchBody(items, mtime) {
  const parts = [];
  for (const it of items) {
    const name = Buffer.from(it.rel, 'utf8');
    const head = Buffer.alloc(3 + name.length + (it.dir ? 0 : 16));
    head[0] = it.dir ? 2 : 1;
    head.writeUInt16BE(name.length, 1);
    name.copy(head, 3);
    if (!it.dir) {
      head.writeBigUInt64BE(BigInt(it.data.length), 3 + name.length);
      head.writeBigUInt64BE(BigInt(mtime), 11 + name.length);
    }
    parts.push(head);
    if (!it.dir) parts.push(it.data);
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

/** A ZIP read from its end: the end records (ZIP64's too), the directory, each local header and its CRC. */
function readZip(buf) {
  try {
    const sig = (at, s) => buf.readUInt32LE(at) === s;
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) {
      if (sig(i, 0x06054b50)) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) return { ok: false, error: 'no end record' };
    let count = buf.readUInt16LE(eocd + 10);
    let cdSize = buf.readUInt32LE(eocd + 12);
    let cdOff = buf.readUInt32LE(eocd + 16);
    let zip64 = false;
    if (sig(eocd - 20, 0x07064b50)) {
      const at = Number(buf.readBigUInt64LE(eocd - 20 + 8));
      if (!sig(at, 0x06064b50)) return { ok: false, error: 'ZIP64 locator points nowhere' };
      zip64 = true;
      count = Number(buf.readBigUInt64LE(at + 32));
      cdSize = Number(buf.readBigUInt64LE(at + 40));
      cdOff = Number(buf.readBigUInt64LE(at + 48));
    }
    const names = [];
    let p = cdOff;
    for (let i = 0; i < count; i++) {
      if (!sig(p, 0x02014b50)) return { ok: false, error: `no central entry ${i} at ${p}` };
      const crc = buf.readUInt32LE(p + 16);
      let csize = buf.readUInt32LE(p + 20);
      let usize = buf.readUInt32LE(p + 24);
      const nlen = buf.readUInt16LE(p + 28);
      const xlen = buf.readUInt16LE(p + 30);
      const clen = buf.readUInt16LE(p + 32);
      let off = buf.readUInt32LE(p + 42);
      const name = buf.toString('utf8', p + 46, p + 46 + nlen);
      let x = p + 46 + nlen;
      const xend = x + xlen;
      while (x + 4 <= xend) {
        const id = buf.readUInt16LE(x);
        const len = buf.readUInt16LE(x + 2);
        if (id === 1) {
          let q = x + 4;
          if (usize === 0xffffffff) { usize = Number(buf.readBigUInt64LE(q)); q += 8; }
          if (csize === 0xffffffff) { csize = Number(buf.readBigUInt64LE(q)); q += 8; }
          if (off === 0xffffffff) { off = Number(buf.readBigUInt64LE(q)); q += 8; }
          zip64 = true;
        }
        x += 4 + len;
      }
      if (!sig(off, 0x04034b50)) return { ok: false, error: `no local header for ${name} at ${off}` };
      const lnlen = buf.readUInt16LE(off + 26);
      const lxlen = buf.readUInt16LE(off + 28);
      if (buf.toString('utf8', off + 30, off + 30 + lnlen) !== name) return { ok: false, error: `local name differs for ${name}` };
      const data = buf.subarray(off + 30 + lnlen + lxlen, off + 30 + lnlen + lxlen + usize);
      if (data.length !== usize || csize !== usize) return { ok: false, error: `sizes for ${name}` };
      if (!name.endsWith('/') && (zlib.crc32(data) >>> 0) !== crc) return { ok: false, error: `CRC of ${name}` };
      names.push(name);
      p += 46 + nlen + xlen + clen;
    }
    if (p !== cdOff + cdSize) return { ok: false, error: 'directory size' };
    return { ok: true, names, zip64 };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

/** The ZIP through this computer's tar (libarchive on Windows 10 and later, macOS; bsdtar on Linux). */
function unzipWithTar(buf, name) {
  const file = path.join(tmp, `${name}.zip`);
  const dir = path.join(tmp, `${name}-out`);
  fs.writeFileSync(file, buf);
  fs.mkdirSync(dir, { recursive: true });
  const tar = process.platform === 'win32' ? path.join(process.env.SystemRoot || 'C:/Windows', 'System32', 'tar.exe') : 'bsdtar';
  const r = spawnSync(tar, ['-xf', file, '-C', dir], { encoding: 'utf8' });
  return { ok: r.status === 0, dir, error: r.stderr || String(r.error || '') };
}

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
