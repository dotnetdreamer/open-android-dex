#!/usr/bin/env node
// "Open in a browser"'s six changes of 2026-11-20 (doc/linux-browser.md, "Six
// changes, after a look at voxl") against LinuxOnDroid itself on a phone:
// its real Linux, Xvnc, hardware encoder, PulseAudio and Wi-Fi.
//
//   node test/linux-browser-app.mjs <serial> [--headless]
//
// Needs the debuggable build (built with -PdebuggableLauncher) with "Open in
// a browser" switched on, and the phone on adb. A browser of its own is let
// in with no tap (as linux-files-test.mjs --phone does: the code read with
// run-as, the pairing window opened as the card opens it, then put back),
// and Linux is started from the page if it is not running. In Linux, through
// a proot of the test's own: a terminal running top for something that moves,
// a tone played for Linux's sound, and a recording of "Browser microphone".
// Leaves the phone as it was: the test's terminal and files gone, its browser
// signed out (the phone's list of browsers byte for byte as before), and
// Linux logged out of again if it was not running.
//
// Needs Node 24 and Chrome or Edge. A visible window unless --headless.

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import https from 'node:https';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

const args = process.argv.slice(2);
const serial = args.find((a) => !a.startsWith('--'));
const HEADLESS = args.includes('--headless');
const PKG = 'com.ccrstech.linuxondroid';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0;
let failed = 0;
function check(name, ok, detail = '') {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? '  ok  ' : '  FAIL'} ${name}${!ok && detail ? ` — ${detail}` : ''}`);
}
async function until(pred, ms = 8000, step = 100) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await pred();
    if (v) return v;
    await sleep(step);
  }
  return null;
}
if (!serial) {
  console.log('usage: node test/linux-browser-app.mjs <serial> [--headless]');
  process.exit(2);
}
const ADB = path.join(process.env.ANDROID_HOME || path.join(os.homedir(), 'AppData/Local/Android/Sdk'), 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const sh = (cmd, input) => spawnSync(ADB, ['-s', serial, ...(input != null ? ['exec-in'] : ['shell']), cmd], { encoding: 'utf8', input });
const ras = (cmd) => sh(`run-as ${PKG} sh -c '${cmd}'`);
const logSince = (t) => sh(`logcat -d -v time -T '${t}' -s OpenDeX:I`).stdout;
const phoneNow = () => sh('date "+%m-%d %H:%M:%S.000"').stdout.trim();

// ── the phone, as it is ──
const ip = (/inet (\d+\.\d+\.\d+\.\d+)/.exec(sh('ip -4 addr show wlan0').stdout) || [])[1];
const state = ras('cat files/browser/state');
check('the debuggable build (run-as), listening for browsers, on the Wi-Fi', !!ip && state.status === 0 && /^(listening|watching) 7443/.test(state.stdout.trim()),
  `${ip} ${state.status} ${state.stdout} ${state.stderr}`);
if (!ip || state.status !== 0) process.exit(1);
const tokensBefore = ras('cat files/browser/tokens').stdout;
const pairingBefore = ras('cat files/browser/pairing-until 2>/dev/null').stdout.trim();
const linuxWasRunning = ras('test -f files/linux/rt.pid && echo yes').stdout.trim() === 'yes';
// The app's libraries, proot among them: legacyNativeLibraryDir, then the ABI's folder in it.
const pkgDump = sh(`dumpsys package ${PKG}`).stdout;
const abiDir = { 'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm', x86_64: 'x86_64', x86: 'x86' }[(pkgDump.match(/primaryCpuAbi=(\S+)/) || [])[1]] || 'arm64';
const nativeDir = `${(pkgDump.match(/legacyNativeLibraryDir=(\S+)/) || [])[1]}/${abiDir}`;
console.log(`  Linux ${linuxWasRunning ? 'running' : 'not running'} before the test; the app's native libraries in ${nativeDir}`);

// A command in Linux, in a proot of the test's own (as linux-rt.sh starts the
// session's: the same root, binds and loader), written to the guest's /tmp.
const GUEST = `#!/system/bin/sh
ROOT=$(pwd)/files/linux
ND=$1
[ "$(cat $ROOT/noseccomp 2>/dev/null)" = 1 ] && export PROOT_NO_SECCOMP=1
export PROOT_LOADER=$ND/libloader.so PROOT_LOADER_32=$ND/libloader32.so PROOT_TMP_DIR=$ROOT/tmp
exec $ND/libproot.so -0 --link2symlink -r $ROOT/rootfs -b /dev -b /proc -b /sys -b $ROOT/tmp:/tmp -b $ROOT/shm:/dev/shm -w /root \\
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\
  PULSE_SERVER=tcp:127.0.0.1:4713 DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=unix:abstract=dex-session-bus \\
  /bin/sh /tmp/oadx-test-$2.sh
`;
sh(`run-as ${PKG} sh -c 'cat > files/linux/tmp/oadx-test-guest.sh'`, GUEST);
function guest(script) {
  sh(`run-as ${PKG} sh -c 'cat > files/linux/tmp/oadx-test-cmd.sh'`, script);
  return sh(`run-as ${PKG} sh files/linux/tmp/oadx-test-guest.sh ${nativeDir} cmd`);
}
/** One that keeps running (proot waits for all it started): on the phone, in the background. */
function guestBg(name, script) {
  sh(`run-as ${PKG} sh -c 'cat > files/linux/tmp/oadx-test-${name}.sh'`, script);
  return sh(`run-as ${PKG} sh -c 'nohup sh files/linux/tmp/oadx-test-guest.sh ${nativeDir} ${name} >/dev/null 2>&1 &'`);
}

// ── a browser of the test's own, let in with no tap ──
const code = ras('cat files/browser/code').stdout.trim().split(/\s+/)[0];
ras(`echo ${Date.now() + 120000} > files/browser/pairing-until`);
const BASE = `https://${ip}:7443`;
const token = await new Promise((resolve) => {
  const body = new URLSearchParams({ code }).toString();
  const req = https.request({ host: ip, port: 7443, method: 'POST', path: '/pair', rejectUnauthorized: false, agent: false,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': body.length, Origin: BASE } }, (res) => {
    let s = '';
    res.on('data', (c) => (s += c));
    res.on('end', () => {
      try {
        resolve(JSON.parse(s).token);
      } catch {
        resolve(null);
      }
    });
  });
  req.on('error', () => resolve(null));
  req.end(body);
});
if (pairingBefore) ras(`echo ${pairingBefore} > files/browser/pairing-until`);
else ras('rm -f files/browser/pairing-until');
check('a test browser let in with the phone\'s code, no tap; the pairing window put back as it was', !!token);
if (!token) process.exit(1);

const exe = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe']
  .find((c) => fs.existsSync(c));
const cdpPort = await new Promise((r) => {
  const s = net.createServer();
  s.listen(0, () => {
    const p = s.address().port;
    s.close(() => r(p));
  });
});
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-app-'));
const proc = spawn(exe, [...(HEADLESS ? ['--headless=new'] : ['--window-position=60,60', '--window-size=1320,820']),
  `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-extensions',
  '--disable-features=CalculateNativeWinOcclusion', '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding',
  '--autoplay-policy=no-user-gesture-required', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', 'about:blank'],
{ stdio: 'ignore' });
let target;
await until(async () => {
  try {
    target = (await (await fetch(`http://127.0.0.1:${cdpPort}/json`)).json()).find((t) => t.type === 'page');
    return target;
  } catch {
    return null;
  }
}, 20000, 200);
const sock = new WebSocket(target.webSocketDebuggerUrl);
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
  } else if (m.method === 'Page.javascriptDialogOpening') {
    sock.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
  }
};
const cdp = (method, params = {}) => new Promise((r) => {
  const i = ++id;
  pending.set(i, r);
  sock.send(JSON.stringify({ id: i, method, params }));
});
const js = async (e) => {
  const m = await cdp('Runtime.evaluate', { expression: e, awaitPromise: true, returnByValue: true });
  return m.result && !m.result.exceptionDetails ? m.result.result.value : undefined;
};
let started = false;
let termPid = '';
async function cleanUp() {
  // In Linux: the test's terminal and tone, and its files.
  if (termPid) guest(`kill ${termPid} 2>/dev/null; true`);
  guest('[ -f /tmp/oadx-test-tone.pid ] && kill $(cat /tmp/oadx-test-tone.pid) 2>/dev/null; rm -f /tmp/oadx-test-tone.pid /tmp/oadx-test-mic.raw /tmp/oadx-test-term.pid; true');
  // Its browser signed out, as Forget this browser does.
  await new Promise((resolve) => {
    const body = new URLSearchParams({ token }).toString();
    const req = https.request({ host: ip, port: 7443, method: 'POST', path: '/forget', rejectUnauthorized: false, agent: false,
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': body.length, Origin: BASE } }, (res) => {
      res.resume();
      res.on('end', resolve);
    });
    req.on('error', resolve);
    req.end(body);
  });
  const tokensAfter = ras('cat files/browser/tokens').stdout;
  check("the test browser signed out; the phone's browsers exactly as before", tokensAfter === tokensBefore,
    `${tokensBefore.split('\n').filter(Boolean).length} before, ${tokensAfter.split('\n').filter(Boolean).length} after`);
  // Linux as it was: logged out of again if the test started it.
  if (started && !linuxWasRunning) {
    guest('xfce4-session-logout --logout --fast >/dev/null 2>&1; true');
    const gone = await until(() => ras('test -f files/linux/rt.pid || echo gone').stdout.trim() === 'gone', 30000, 500);
    check('Linux, started by the test, logged out of again', !!gone);
  }
  ras('rm -f files/linux/tmp/oadx-test-cmd.sh files/linux/tmp/oadx-test-term.sh files/linux/tmp/oadx-test-tone.sh files/linux/tmp/oadx-test-guest.sh');
  try {
    await Promise.race([cdp('Browser.close'), sleep(3000)]);
  } catch {
    // gone
  }
  spawnSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' });
}

try {
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Page.navigate', { url: `${BASE}/` });
  await sleep(1500);
  await js(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
  await sleep(300);
  await js(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
  await until(() => js(`location.href.startsWith('${BASE}') && typeof VideoDecoder === 'function'`), 10000);
  await js(`localStorage.setItem('lod.token', ${JSON.stringify(token)}); localStorage.setItem('lod.mode', 'medium'); localStorage.setItem('lod.sound', '1'); true`);
  const t0 = phoneNow();
  await cdp('Page.reload', {});
  // A reload never starts Linux: the card does.
  const first = await until(async () => (await js(`document.body.classList.contains('up') ? 'up'
    : (!document.getElementById('ended').hidden && document.getElementById('ended-go').textContent === 'Start Linux') ? 'start' : ''`)) || null, 30000, 200);
  if (first === 'start') {
    started = true;
    await js(`document.getElementById('ended-go').click()`);
  }
  const up = await until(() => js(`document.body.classList.contains('up')`), 180000, 500);
  check(`the phone's own Linux on screen in the browser${started ? ', started from the page' : ''}`, !!up,
    await js(`document.getElementById('wait-title').textContent + ' / ' + document.getElementById('ended-title').textContent`));
  if (!up) throw new Error('not up');
  await sleep(3000);
  const v0 = await js('lodVideo()');
  check('decoded and drawn in a worker, off the page\'s own thread', v0.decode === 'worker', JSON.stringify(v0));
  const enc = (logSince(t0).match(/encoding .*$/m) || [''])[0];
  check(`the phone's hardware encoder, key frames only when asked (${enc.replace(/^.*encoding /, '')})`, /key frames when asked/.test(enc), enc);

  // Something that moves: a terminal running top, in a window of its own.
  guestBg('term', 'xfce4-terminal --disable-server --geometry=90x24 --title=oadx-test -x top -d 0.2 >/dev/null 2>&1 & echo $! > /tmp/oadx-test-term.pid; wait');
  termPid = (await until(() => ras('cat files/linux/tmp/oadx-test-term.pid 2>/dev/null').stdout.trim() || null, 8000, 300)) || '';
  await sleep(4000);
  const k0 = await js('lodVideo()');
  await sleep(15000);
  const k1 = await js('lodVideo()');
  console.log(`  15 s of top: ${k1.drawnTotal - k0.drawnTotal} frames drawn, key frames ${k1.keysIn - k0.keysIn} (asked for ${k1.keysAsked - k0.keysAsked})`);
  check('with Linux moving, no key frame comes that the page did not ask for', k1.drawnTotal - k0.drawnTotal > 30 && k1.keysIn - k0.keysIn <= k1.keysAsked - k0.keysAsked,
    JSON.stringify({ k0, k1, termPid }));
  const st = await js('lodStats()');
  check('the bitrate follows how late frames arrive: the phone reports the queue it reads', st && typeof st.queueMs === 'number' && st.queueMs >= 0,
    JSON.stringify(st));

  // A tab out of sight.
  const t1 = phoneNow();
  const other = await cdp('Target.createTarget', { url: 'about:blank' });
  await until(() => js('document.visibilityState === "hidden"'), 3000);
  // Frames already on their way when the tab went land first.
  await sleep(1500);
  const h1 = await js('lodVideo()');
  const st1 = JSON.stringify(await js('lodStats()'));
  await sleep(4000);
  const h2 = await js('lodVideo()');
  const st2 = JSON.stringify(await js('lodStats()'));
  // The phone sends its stats only with a frame: the same stats, no frame sent.
  check('a hidden tab: the phone stops the picture (its log says so) while Linux moves, sends no frame, and nothing is drawn',
    /the browser's tab is hidden/.test(logSince(t1)) && st1 === st2 && h2.drawnTotal - h1.drawnTotal <= 1, JSON.stringify({ h1, h2, st1, st2 }));
  const backAt = Date.now();
  await cdp('Target.closeTarget', { targetId: other.result.targetId });
  await cdp('Page.bringToFront');
  const firstBack = await until(async () => ((await js('lodVideo()')).drawnTotal > h2.drawnTotal ? Date.now() : null), 5000, 5);
  const h3 = await js('lodVideo()');
  check(`…the tab back: the picture again from a key frame, ${firstBack ? firstBack - backAt : '-'} ms after`,
    !!firstBack && h3.keysIn > h2.keysIn && firstBack - backAt < 1000, JSON.stringify(h3));

  // Linux's sound: a tone, as an app that asks for 20 ms of latency plays one.
  guestBg('tone', `(python3 -c "
import math, sys
o = sys.stdout.buffer
n = 0
while True:
    b = bytearray()
    for i in range(960):
        v = int(8000 * math.sin(2 * math.pi * 440 * n / 48000)); n += 1
        b += v.to_bytes(2, 'little', signed=True) * 2
    o.write(bytes(b))
" | pacat --raw --format=s16le --rate=48000 --channels=2 --latency-msec=20 >/dev/null 2>&1) & echo $! > /tmp/oadx-test-tone.pid; wait`);
  await js(`document.getElementById('screen').focus()`);
  await until(async () => (await js('lodSound()')).runs > 50, 10000);
  await sleep(5000);
  const s1 = await js('lodSound()');
  console.log(`  Linux's sound in the browser: ${s1.format}, ${s1.cushionMs} ms in hand (the longest recent gap ${s1.gapMs} ms), ${s1.underruns} ran dry`);
  check("Linux's own sound plays in the browser, with what the real tap's gaps ask for in hand", s1.runs > 100 && s1.cushionMs > 0 && s1.cushionMs < 120,
    JSON.stringify(s1));
  guest('[ -f /tmp/oadx-test-tone.pid ] && kill $(cat /tmp/oadx-test-tone.pid) 2>/dev/null; pkill -f "pacat --raw --format=s16le --rate=48000 --channels=2 --latency-msec=20" 2>/dev/null; true');

  // The microphone, into Linux's PulseAudio, and recorded there.
  const src = guest('pactl info | grep "Default Source"; pactl list sources short');
  check('Linux has "Browser microphone", its default source', /Default Source: browser_mic/.test(src.stdout) && /browser_mic\s+module-pipe-source/.test(src.stdout),
    src.stdout + src.stderr);
  await js(`document.getElementById('mic').click()`);
  const reached = await until(async () => (await js('lodMic()')).reached === true, 6000);
  check('the microphone button: the phone says the microphone reaches Linux', !!reached, JSON.stringify(await js('lodMic()')));
  const rec = guest(`timeout 3 parec --device=browser_mic --raw --format=s16le --rate=48000 --channels=1 --latency-msec=20 > /tmp/oadx-test-mic.raw 2>/dev/null; python3 -c "
import sys
d = open('/tmp/oadx-test-mic.raw', 'rb').read()
n = len(d) // 2
s = [int.from_bytes(d[2*i:2*i+2], 'little', signed=True) for i in range(n)]
best = 0
for b in range(0, n - 480, 480):
    r = (sum(x * x for x in s[b:b+480]) / 480) ** 0.5
    best = max(best, r)
print(n, int(best))
"`);
  const [samples, loudest] = rec.stdout.trim().split(/\s+/).map(Number);
  check(`a recording in Linux hears this computer's microphone: ${samples} samples in 3 s, the loudest 10 ms at ${loudest}`,
    samples > 48000 * 2 && loudest > 300, rec.stdout + rec.stderr);
  await js(`document.getElementById('mic').click()`);
  check('no script errors in the page', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
} catch (e) {
  check('the run went to its end', false, String(e && e.stack || e));
} finally {
  await cleanUp();
}
console.log(`\n${passed} passed, ${failed} failed`);
try {
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 40, retryDelay: 250 });
} catch {
  // left behind
}
process.exit(failed ? 1 : 0);
