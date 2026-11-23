#!/usr/bin/env node
// How late the browser's picture is, end to end, by where the page decodes it
// (doc/linux-browser.md, "The picture in a worker"). The phone's part is the launcher's own server on this JVM
// (test/linux-browser/BrowserHarness.java) with H.264 that ffmpeg made, every
// frame stamped with this computer's clock as it is sent, so that the page
// can say how long after that it drew it.
//
//   node test/linux-browser-latency.mjs [--headless] [--seconds 10] [--rounds 2] [--browser <exe>]
//
// Two ways, each with the page's own thread idle and then busy (a 30 ms task
// every 50 ms, as a heavy page or a long garbage collection would be):
//   main     WebCodecs on the page's own thread (lod.decode 'main')
//   worker   the socket, the decoder and the canvas in video-worker.js, the default
// Frames are paced as LinuxStream paces them (no more in flight than the
// round trip holds). The order of the ways is interleaved, round after round.
// A visible window by default: a headless browser draws on a clock of its own.
//
// Measured so too, on 2026-11-20, and taken out after: acknowledging each
// frame once decoded rather than as it arrived. It was no sooner on screen
// either way (this computer decodes a frame in a millisecond or two).
//
// Needs Node 24, a JDK (JAVA_HOME), ffmpeg, and Chrome or Edge.

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import https from 'node:https';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const LAUNCHER = path.join(ROOT, 'openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher');
const ASSETS = path.join(ROOT, 'openandroiddex-launcher/app/src/main/assets/linux/browser');
const args = process.argv.slice(2);
const argOf = (name, d) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : d;
};
const HEADLESS = args.includes('--headless');
const SECONDS = Number(argOf('--seconds', '10'));
const ROUNDS = Number(argOf('--rounds', '2'));
const JDK = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
const JAVA = JDK ? path.join(JDK, 'java') : 'java';
const JAVAC = JDK ? path.join(JDK, 'javac') : 'javac';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-latency-'));
const cleanups = [];
process.on('exit', () => {
  for (const c of cleanups.reverse()) {
    try {
      c();
    } catch {
      // gone already
    }
  }
  try {
    fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 120, retryDelay: 250 });
  } catch (e) {
    console.log(`note: ${tmp} is left behind (${e.code})`);
  }
});
async function until(pred, ms = 8000, step = 50) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await pred();
    if (v) return v;
    await sleep(step);
  }
  return null;
}
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
const fail = (why) => {
  console.log(`cannot measure: ${why}`);
  process.exit(1);
};

// ── the harness ──
const classes = path.join(tmp, 'classes');
fs.mkdirSync(classes);
const javac = spawnSync(JAVAC, ['-nowarn', '-d', classes,
  ...['LinuxBrowser', 'LinuxBrowserCert', 'LinuxBrowserServer', 'LinuxStreamSocket', 'LinuxStreamAudio', 'LinuxAudioLag', 'LinuxFiles',
    'LinuxStreamRate'].map((c) => path.join(LAUNCHER, `${c}.java`)),
  path.join(HERE, 'linux-browser/DexLog.java'), path.join(HERE, 'linux-browser/BrowserHarness.java')], { encoding: 'utf8' });
if (javac.status !== 0) fail(javac.stderr);
// A desktop where much moves: harder to decode than a still one, as a video is.
const h264 = path.join(tmp, 'h264');
fs.mkdirSync(h264);
for (const [name, size] of [['big', '1280x720'], ['small', '852x480']]) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=60,noise=alls=12:allf=t`,
    '-t', '4', '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-bf', '0', '-g', '240', '-b:v', '12M',
    '-maxrate', '12M', '-bufsize', '1M', '-x264-params', 'aud=1:repeat-headers=1', '-tune', 'zerolatency', '-bsf:v', 'h264_mp4toannexb',
    '-f', 'h264', path.join(h264, `${name}.h264`)], { encoding: 'utf8' });
  if (r.status !== 0) fail(r.stderr || String(r.error));
}
const opusDir = path.join(tmp, 'opus');
fs.mkdirSync(opusDir);
for (const [name, ch, rate] of [['mono', 1, '32k'], ['stereo', 2, '96k']]) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
    '-t', '4', '-ac', String(ch), '-c:a', 'libopus', '-b:a', rate, '-frame_duration', '20', path.join(opusDir, `${name}.opus`)], { encoding: 'utf8' });
  if (r.status !== 0) fail(r.stderr || String(r.error));
}
const IP = lanAddress();
if (!IP) fail('no address on a home network');
const PORT = await freePort();
const harness = spawn(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.BrowserHarness', path.join(tmp, 'state'), String(PORT),
  path.join(h264, 'big.h264'), path.join(h264, 'small.h264'), ASSETS, path.join(opusDir, 'mono.opus'), path.join(opusDir, 'stereo.opus')],
{ stdio: ['pipe', 'pipe', 'pipe'] });
cleanups.push(() => harness.kill());
let ready = null;
const lines = [];
{
  let rest = '';
  harness.stdout.on('data', (d) => {
    rest += d;
    let i;
    while ((i = rest.indexOf('\n')) >= 0) {
      const line = rest.slice(0, i).replace(/\r$/, '');
      rest = rest.slice(i + 1);
      if (line.startsWith('READY ')) {
        const [, port, code] = line.split(' ');
        ready = { port: Number(port), code };
      } else if (!line.startsWith('EV ')) {
        lines.push({ at: Date.now(), line });
      }
    }
  });
}
const command = async (cmd, answer) => {
  const t = Date.now();
  harness.stdin.write(`${cmd}\n`);
  return until(() => lines.find((l) => l.at >= t && l.line.startsWith(answer)), 5000, 20);
};
await until(() => ready, 20000);
if (!ready) fail('the harness did not start');
const BASE = `https://${IP}:${PORT}`;

// A token, as the page gets one: the code, from this computer's own address.
const token = await new Promise((resolve) => {
  const body = new URLSearchParams({ code: ready.code }).toString();
  const req = https.request({ host: IP, port: PORT, method: 'POST', path: '/pair', rejectUnauthorized: false, agent: false,
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
if (!token) fail('pairing did not give a token');
await command('wallclock on', 'OK wallclock');
await command('pace on', 'OK pace');

// ── the browser ──
const exe = argOf('--browser', ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe']
  .find((c) => fs.existsSync(c)));
if (!exe) fail('no Chrome or Edge');
const cdpPort = await freePort();
const profile = path.join(tmp, 'profile');
const proc = spawn(exe, [
  ...(HEADLESS ? ['--headless=new'] : ['--window-position=40,40', '--window-size=1320,820']),
  `--remote-debugging-port=${cdpPort}`, `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--disable-extensions',
  // A window that another covers is still the one being measured.
  '--disable-features=CalculateNativeWinOcclusion', '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding',
  '--disable-background-timer-throttling', '--autoplay-policy=no-user-gesture-required', 'about:blank',
], { stdio: 'ignore' });
cleanups.push(() => {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' });
    spawnSync('powershell', ['-NoProfile', '-Command',
      `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*oadx-latency-*' -and $_.Name -match 'msedge|chrome' } | `
      + `ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`]);
  } else {
    proc.kill();
  }
});
let target = null;
await until(async () => {
  try {
    target = (await (await fetch(`http://127.0.0.1:${cdpPort}/json`)).json()).find((t) => t.type === 'page');
    return target;
  } catch {
    return null;
  }
}, 20000, 200);
if (!target) fail('the browser has no page');
const sock = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => (sock.onopen = r));
let id = 0;
const pending = new Map();
sock.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m);
    pending.delete(m.id);
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
  return m.result && !m.result.exceptionDetails ? m.result.result.value : undefined;
};
await cdp('Page.enable');
await cdp('Runtime.enable');
await cdp('Page.navigate', { url: `${BASE}/` });
await sleep(1500);
await js(`document.getElementById('details-button') && document.getElementById('details-button').click()`);
await sleep(300);
await js(`document.getElementById('proceed-link') && document.getElementById('proceed-link').click()`);
if (!(await until(() => js(`location.href.startsWith('${BASE}') && typeof VideoDecoder === 'function'`), 10000))) fail('the page did not load');

// ── the measurements ──
const WAYS = [['main'], ['worker']];
const BUSY = `window.__busy = setInterval(() => { const t = performance.now(); while (performance.now() - t < 30); }, 50)`;
const results = {};

async function measure(decode, busy) {
  await js(`localStorage.setItem('lod.token', ${JSON.stringify(token)}); localStorage.setItem('lod.decode', '${decode}');
    localStorage.setItem('lod.mode', 'high'); localStorage.setItem('lod.sound', '1'); true`);
  await cdp('Page.reload', {});
  const up = await until(() => js(`document.body && document.body.classList.contains('up')`), 15000, 100);
  if (!up) return { error: await js(`document.body ? document.body.innerText.slice(0, 120) : ''`) };
  const v = await js('lodVideo()');
  if (v.decode !== decode) return { error: `the page decodes on ${v.decode}` };
  await sleep(2000);
  if (busy) await js(BUSY);
  await js('lodVideoReset()');
  const d0 = (await js('lodVideo()')).drawnTotal;
  const t0 = Date.now();
  // The last 600 frames' lateness is what lodVideo keeps: ten seconds at 60.
  await sleep(SECONDS * 1000);
  const v1 = await js('lodVideo()');
  const fps = Math.round(((v1.drawnTotal - d0) * 1000) / (Date.now() - t0));
  const s = await js('lodSound()');
  if (busy) await js('clearInterval(window.__busy); true');
  return { median: v1.lateMedian, p95: v1.late95, fps, sound: s.leadMs, soundNow: s.nowMs, cushion: s.cushionMs, underruns: s.underruns };
}

console.log(`measuring ${HEADLESS ? 'headless' : 'in a visible window'}, ${SECONDS} s each, ${ROUNDS} rounds`);
for (let round = 0; round < ROUNDS; round++) {
  for (const busy of [false, true]) {
    for (const [decode] of round % 2 ? [...WAYS].reverse() : WAYS) {
      const key = `${decode}/${busy ? 'busy' : 'idle'}`;
      const r = await measure(decode, busy);
      (results[key] ||= []).push(r);
      console.log(`  ${key.padEnd(22)} ${r.error ? `ERROR ${r.error}` : `late ${r.median} ms median, ${r.p95} ms at 95%, ${r.fps} fps; sound in hand ${r.sound} ms (now ${r.soundNow}, target ${r.cushion}), ${r.underruns} ran dry`}`);
    }
  }
}
console.log('\nmedians of the rounds:');
for (const [key, list] of Object.entries(results)) {
  const ok = list.filter((r) => !r.error);
  if (!ok.length) continue;
  const med = (k) => ok.map((r) => r[k]).sort((a, b) => a - b)[Math.floor(ok.length / 2)];
  console.log(`  ${key.padEnd(22)} late ${med('median')} ms median, ${med('p95')} ms at 95%, ${med('fps')} fps`);
}
await command('pace off', 'OK pace');
process.exit(0);
