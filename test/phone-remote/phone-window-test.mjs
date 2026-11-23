#!/usr/bin/env node
// The desktop app's phone window (PhoneWindow.tsx, PhoneView.tsx) keeps the
// quality and the Picture size chosen in it when the link goes and the window
// comes back by itself, and when the window is reloaded. No phone: the
// window's own page, from the app's Vite, in a visible Edge, with the app's
// commands answered here (remote_phones, remote_phone_stream,
// remote_phone_view_set) and a stand-in phone stream, a WebSocket server
// here that answers a hello the way PhoneStream.java does and can drop the
// link (the TCP connection cut, no close frame: a network gone) or close it
// (a close frame, no reason given).
//
// Until 2026-11-22 a window that came back asked the phone for what the
// window had been opened with: a choice made since then was gone until the
// window was closed and opened again.
//
//   node test/phone-remote/phone-window-test.mjs [--browser <edge.exe>]
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchEdge } from './edge.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = path.resolve(HERE, '../../open-android-dex-tauri');
const argv = process.argv.slice(2);
const argOf = (n, d) => (argv.includes(n) ? argv[argv.indexOf(n) + 1] : d);
const EDGE = argOf('--browser', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');
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
let passed = 0;
let failed = 0;
const check = (what, ok, detail = '') => {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? '  ok  ' : '  FAIL'} ${what}${!ok && detail ? ` — ${String(detail).slice(0, 700)}` : ''}`);
};
const freePort = () => new Promise((resolve) => {
  const s = net.createServer();
  s.listen(0, '127.0.0.1', () => {
    const p = s.address().port;
    s.close(() => resolve(p));
  });
});

// ── the stand-in phone stream ──
const conns = [];
/** The stand-in's picture: black, as a phone's screen sharing shows its PIN pad, in access units. */
let UNITS = [];
const isKey = (au) => {
  for (let i = 0; i + 4 < au.length; i++) {
    if (au[i] === 0 && au[i + 1] === 0 && au[i + 2] === 1 && (au[i + 3] & 0x1f) === 5) return true;
  }
  return false;
};
function makeUnits(dir) {
  const file = path.join(dir, 'black.h264');
  const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=black:size=720x1560:rate=30',
    '-t', '2', '-c:v', 'libx264', '-profile:v', 'high', '-pix_fmt', 'yuv420p', '-bf', '0', '-g', '30',
    '-x264-params', 'aud=1:repeat-headers=1', '-tune', 'zerolatency', '-bsf:v', 'h264_mp4toannexb', '-f', 'h264', file], { encoding: 'utf8' });
  if (r.status !== 0) return [];
  const b = fs.readFileSync(file);
  // Access units start at their delimiter (NAL type 9).
  const starts = [];
  for (let i = 0; i + 4 < b.length; i++) {
    if (b[i] === 0 && b[i + 1] === 0 && b[i + 2] === 0 && b[i + 3] === 1 && (b[i + 4] & 0x1f) === 9) starts.push(i);
  }
  return starts.map((s, k) => b.subarray(s, k + 1 < starts.length ? starts[k + 1] : b.length));
}
const PICTURE = { low: [588, 1280], medium: [720, 1560], high: [1080, 2340] };
function wsServer() {
  const srv = http.createServer((req, res) => {
    res.writeHead(404);
    res.end();
  });
  srv.on('upgrade', (req, sock) => {
    const accept = crypto.createHash('sha1').update(`${req.headers['sec-websocket-key']}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64');
    sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    sock.setNoDelay(true);
    const c = {
      n: conns.length + 1, texts: [], hello: null, binaries: 0, closed: false, quality: 'medium', scale: 1,
      send(text) {
        const p = Buffer.from(text);
        const head = p.length < 126 ? Buffer.from([0x81, p.length]) : Buffer.from([0x81, 126, p.length >> 8, p.length & 0xff]);
        if (!sock.destroyed) sock.write(Buffer.concat([head, p]));
      },
      /** What the phone says after a hello, a quality or a size: the picture it sends now. */
      video() {
        const [w, h] = PICTURE[c.quality] || PICTURE.medium;
        const even = (v) => Math.max(2, Math.round((v * c.scale) / 2) * 2);
        c.send(JSON.stringify({ t: 'video', dw: 1440, dh: 3120, cw: even(w), ch: even(h), quality: c.quality, scale: c.scale, source: 'screen', codec: 'test', hw: true }));
      },
      binary(payload) {
        const n = payload.length;
        let head;
        if (n < 126) head = Buffer.from([0x82, n]);
        else if (n <= 0xffff) head = Buffer.from([0x82, 126, n >> 8, n & 0xff]);
        else {
          head = Buffer.alloc(10);
          head[0] = 0x82;
          head[1] = 127;
          head.writeBigUInt64BE(BigInt(n), 2);
        }
        if (!sock.destroyed) sock.write(Buffer.concat([head, payload]));
      },
      /** The picture, 30 frames a second from the stand-in's H.264, as PhoneStream sends its encoder's (0x01). */
      pump() {
        if (c.pumping || !UNITS.length) return;
        c.pumping = true;
        let i = 0;
        const t = setInterval(() => {
          if (c.closed || sock.destroyed) {
            clearInterval(t);
            return;
          }
          const au = UNITS[i++ % UNITS.length];
          const head = Buffer.alloc(10);
          head[0] = 0x01;
          head[1] = isKey(au) ? 1 : 0;
          head.writeBigUInt64BE(BigInt(Date.now()) * 1000n, 2);
          c.binary(Buffer.concat([head, au]));
        }, 33);
      },
      /** The phone's sound as PhoneStream sends it: its form, then 10 ms runs of PCM (0x03, codec 0). */
      sound(ms, hz = 440) {
        c.send(JSON.stringify({ t: 'sound', codec: 'pcm', rate: 48000, channels: 2, kbps: 1536, mode: 'high' }));
        let k = 0;
        const runs = ms / 10;
        const t = setInterval(() => {
          if (c.closed || k >= runs) {
            clearInterval(t);
            return;
          }
          const pcm = Buffer.alloc(480 * 4);
          for (let s = 0; s < 480; s++) {
            const v = Math.round(Math.sin((2 * Math.PI * hz * (k * 480 + s)) / 48000) * 9000);
            pcm.writeInt16LE(v, s * 4);
            pcm.writeInt16LE(v, s * 4 + 2);
          }
          const head = Buffer.alloc(10);
          head[0] = 0x03;
          head[1] = 0;
          head.writeBigUInt64BE(BigInt(Date.now()) * 1000n, 2);
          c.binary(Buffer.concat([head, pcm]));
          k++;
        }, 10);
      },
      /** The network gone: the connection cut, nothing said. */
      drop() {
        c.closed = true;
        sock.destroy();
      },
      /** Closed with a close frame, and no state message (so not "told why"). */
      close() {
        c.closed = true;
        if (!sock.destroyed) sock.end(Buffer.from([0x88, 2, 0x03, 0xe9]));
      },
    };
    conns.push(c);
    let buf = Buffer.alloc(0);
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      for (;;) {
        if (buf.length < 2) return;
        const op = buf[0] & 0x0f;
        const masked = (buf[1] & 0x80) !== 0;
        let len = buf[1] & 0x7f;
        let off = 2;
        if (len === 126) {
          if (buf.length < 4) return;
          len = buf.readUInt16BE(2);
          off = 4;
        } else if (len === 127) {
          if (buf.length < 10) return;
          len = Number(buf.readBigUInt64BE(2));
          off = 10;
        }
        const maskAt = off;
        if (masked) off += 4;
        if (buf.length < off + len) return;
        let p = Buffer.from(buf.subarray(off, off + len));
        if (masked) for (let i = 0; i < p.length; i++) p[i] ^= buf[maskAt + (i & 3)];
        buf = buf.subarray(off + len);
        if (op === 1) {
          const text = p.toString('utf8');
          c.texts.push(text);
          let m = null;
          try {
            m = JSON.parse(text);
          } catch { /* not ours */ }
          if (m && m.t === 'hello') {
            c.hello = m;
            c.quality = m.quality || 'medium';
            c.scale = m.scale || 1;
            c.video();
            c.send(JSON.stringify({ t: 'phone', control: true, locked: false }));
          } else if (m && m.t === 'quality') {
            c.quality = m.mode;
            c.video();
          } else if (m && m.t === 'size') {
            c.scale = m.scale;
            c.video();
          }
        } else if (op === 2) {
          c.binaries++;
          // A key (0x11): down, then its X11 keysym.
          if (p[0] === 0x11 && p.length >= 6) (c.keys = c.keys || []).push([p[1], p.readUInt32BE(2)]);
          // The wheel (0x13): x, y, and how far the page scrolls there, in the phone's pixels.
          if (p[0] === 0x13 && p.length >= 9) (c.scrolls = c.scrolls || []).push([p.readUInt16BE(1), p.readUInt16BE(3), p.readInt16BE(5), p.readInt16BE(7)]);
          // The mouse (0x10): its buttons, where the wheel's notches would be bits 3-6.
          if (p[0] === 0x10 && p.length >= 6) (c.pointers = c.pointers || []).push(p[1]);
        } else if (op === 8) {
          c.closed = true;
          sock.end();
        }
      }
    });
    sock.on('close', () => {
      c.closed = true;
    });
    sock.on('error', () => {});
  });
  return srv;
}
/** The window's own connection now: the newest one open that said hello (React's StrictMode opens and closes one first). */
const current = () => [...conns].reverse().find((c) => !c.closed && c.hello) || null;

// ── DevTools ──
async function devtools(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => {
    ws.onopen = r;
    ws.onerror = j;
  });
  let id = 0;
  const waiting = new Map();
  const errors = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    if (m.id && waiting.has(m.id)) {
      waiting.get(m.id)(m);
      waiting.delete(m.id);
    }
  };
  const call = (method, params = {}) => new Promise((r) => {
    const n = ++id;
    waiting.set(n, r);
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const js = async (e) => {
    const r = await call('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) return { error: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
    return r.result?.result?.value;
  };
  await call('Runtime.enable');
  await call('Page.enable');
  return { ws, call, js, errors };
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-phone-window-'));
UNITS = makeUnits(tmp);
const ends = [];
async function end(code) {
  for (const e of ends.reverse()) {
    try {
      await e();
    } catch { /* gone */ }
  }
  try {
    fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 40, retryDelay: 250 });
  } catch (e) {
    console.log(`note: ${tmp} is left behind (${e.code})`);
  }
  process.exitCode = code;
}

const wsPort = await freePort();
const phoneStream = wsServer();
await new Promise((r) => phoneStream.listen(wsPort, '127.0.0.1', r));
ends.push(() => new Promise((r) => {
  for (const c of conns) c.drop();
  phoneStream.close(r);
}));

// The app's page, as `tauri dev` serves it.
const vitePort = await freePort();
const vite = spawn(process.execPath, [path.join(APP, 'node_modules/vite/bin/vite.js'), '--port', String(vitePort), '--strictPort', '--host', '127.0.0.1'], { cwd: APP, stdio: ['ignore', 'pipe', 'pipe'] });
let viteOut = '';
vite.stdout.on('data', (d) => (viteOut += d));
vite.stderr.on('data', (d) => (viteOut += d));
ends.push(() => {
  if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(vite.pid), '/T', '/F'], { stdio: 'ignore' });
  else vite.kill();
});
const BASE = `http://127.0.0.1:${vitePort}`;
const served = await until(async () => {
  try {
    return (await fetch(`${BASE}/`)).ok;
  } catch {
    return false;
  }
}, 30000, 250);
check("the app's page is served (Vite)", !!served, viteOut);
if (!served) {
  await end(1);
  process.exit();
}

// The app's side of the window, answered here.
const STUB = `(() => {
  window.__calls = [];
  let n = 1;
  window.__TAURI_OS_PLUGIN_INTERNALS__ = { platform: 'windows', arch: 'x86_64', family: 'windows', os_type: 'windows', version: '10.0', eol: '\\r\\n', exe_extension: 'exe' };
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: 'phone-test' }, currentWebview: { windowLabel: 'phone-test', label: 'phone-test' } },
    transformCallback(cb, once) { const id = n++; window['_' + id] = (x) => { if (once) delete window['_' + id]; if (cb) cb(x); }; return id; },
    unregisterCallback(id) { delete window['_' + id]; },
    convertFileSrc: (p) => p,
    async invoke(cmd, args) {
      let a = null;
      try { a = JSON.parse(JSON.stringify(args ?? null)); } catch { a = String(args); }
      window.__calls.push([cmd, a]);
      if (cmd === 'remote_phones') return [{ id: 'test-phone', name: 'Test phone' }];
      if (cmd === 'remote_phone_stream') return { url: 'ws://127.0.0.1:${wsPort}/phone/test', route: 'Wi-Fi' };
      return null;
    },
  };
})();`;

const edge = await launchEdge(EDGE, { port: await freePort(), profile: path.join(tmp, 'profile'), args: ['--window-size=760,900', '--window-position=80,60'] });
ends.push(() => edge.close());
const d = await devtools(edge.page.webSocketDebuggerUrl);
ends.push(() => d.ws.close());
await d.call('Page.addScriptToEvaluateOnNewDocument', { source: STUB });

const bar = () => d.js(`(() => {
  const lit = (t) => { const b = document.querySelector('button[title="' + t + '"]'); return b ? getComputedStyle(b).backgroundColor === 'rgb(74, 222, 128)' : null; };
  const s = document.querySelector('select[aria-label="Picture size"]');
  return { low: lit('Low: fastest'), medium: lit('Medium'), high: lit('High: sharpest'), size: s ? s.value : null, hash: location.hash };
})()`);
const litOnly = (b, q) => b && b[q] === true && ['low', 'medium', 'high'].filter((x) => x !== q).every((x) => b[x] === false);
async function press(title) {
  const at = await d.js(`(() => { const b = document.querySelector('button[title="${title}"]'); if (!b) return null; const r = b.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; })()`);
  if (!at) return false;
  await d.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: at.x, y: at.y });
  await d.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: at.x, y: at.y, button: 'left', buttons: 1, clickCount: 1 });
  await d.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: at.x, y: at.y, button: 'left', buttons: 0, clickCount: 1 });
  return true;
}
/** A choice in the Picture size list, as the list makes it (a native select's own list cannot be driven over DevTools). */
const choose = (v) => d.js(`(() => { const s = document.querySelector('select[aria-label="Picture size"]');
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(s, '${v}'); s.dispatchEvent(new Event('change', { bubbles: true })); return s.value; })()`);
/** A new connection's hello, after {@code before} connections. */
const nextHello = async (before, ms = 8000) => until(() => {
  const c = current();
  return c && c.n > before ? c : null;
}, ms);
const heard = (c, pred) => until(() => c.texts.some((t) => {
  try {
    return pred(JSON.parse(t));
  } catch {
    return false;
  }
}), 4000);
const kept = (pred) => d.js(`window.__calls.filter((c) => c[0] === 'remote_phone_view_set').map((c) => c[1])`).then((l) => (Array.isArray(l) ? l.some(pred) : false));

console.log('The phone window, its choices and a link that goes');
try {
  // Opened as the app opens it: the choices from last time in the address.
  await d.call('Page.navigate', { url: `${BASE}/#phone=test-phone&quality=medium&scale=1` });
  const a = await nextHello(0, 15000);
  check('opened: the window asks the phone for the address\'s choices (Medium, Full size)',
    !!a && a.hello.quality === 'medium' && a.hello.scale === 1, JSON.stringify({ hello: a && a.hello, errors: d.errors, vite: viteOut.slice(-400) }));
  if (!a) throw new Error('no window');

  // Low and Half size, chosen in the window.
  const pressedLow = await press('Low: fastest');
  await choose('0.5');
  const sent = !!(await heard(a, (m) => m.t === 'quality' && m.mode === 'low')) && !!(await heard(a, (m) => m.t === 'size' && m.scale === 0.5));
  const keptBoth = (await kept((x) => x.id === 'test-phone' && x.quality === 'low')) && (await kept((x) => x.id === 'test-phone' && x.scale === 0.5));
  check('Low and Half size chosen: told to the phone and kept for next time', pressedLow && sent && keptBoth,
    JSON.stringify({ pressedLow, sent, keptBoth, texts: a.texts.filter((t) => !t.includes('"hello"')).slice(-4) }));

  // The link goes (the connection cut): the window comes back by itself.
  const n1 = conns.length;
  a.drop();
  const b = await nextHello(n1);
  const barB = await bar();
  check('the link cut: the window comes back by itself and asks for Low and Half size, not what it was opened with',
    !!b && b.hello.quality === 'low' && b.hello.scale === 0.5, JSON.stringify({ hello: b && b.hello }));
  check('…and its bar shows Low and Half size', litOnly(barB, 'low') && barB.size === '0.5', JSON.stringify(barB));
  check('…and its address carries them, for a reload', /(^|[#&])quality=low(&|$)/.test(barB.hash) && /(^|[#&])scale=0\.5(&|$)/.test(barB.hash), barB.hash);

  // High and Three quarters; then the link closed (a close frame, no reason): back with them.
  if (b) {
    await press('High: sharpest');
    await choose('0.75');
    await heard(b, (m) => m.t === 'size' && m.scale === 0.75);
    const n2 = conns.length;
    b.close();
    const c = await nextHello(n2);
    check('High and Three quarters, then the link closed: back with High and Three quarters',
      !!c && c.hello.quality === 'high' && c.hello.scale === 0.75, JSON.stringify({ hello: c && c.hello }));
  }

  // The window reloaded: its address has the choices.
  const n3 = conns.length;
  await d.call('Page.reload', { ignoreCache: false });
  const r = await nextHello(n3, 15000);
  const barR = await bar();
  check('the window reloaded: High and Three quarters still', !!r && r.hello.quality === 'high' && r.hello.scale === 0.75
    && litOnly(barR, 'high') && barR.size === '0.75', JSON.stringify({ hello: r && r.hello, bar: barR }));

  // A window opened anew, with other choices in its address: those, as before.
  // (Through about:blank: a new address that differs only after its # is
  // the same page, not a new one.)
  const n4 = conns.length;
  await d.call('Page.navigate', { url: 'about:blank' });
  await sleep(300);
  await d.call('Page.navigate', { url: `${BASE}/#phone=test-phone&quality=low&scale=0.333` });
  const f = await nextHello(n4, 15000);
  const barF = await bar();
  check('opened anew with Low and A third in its address: those', !!f && f.hello.quality === 'low' && f.hello.scale === 0.333
    && litOnly(barF, 'low') && barF.size === '0.333', JSON.stringify({ hello: f && f.hello, bar: barF }));
  // ── the phone's sound, its lock screen's pad, and the window going ──
  console.log("The phone's sound, its lock screen's pad, and the window going");
  check('a black H.264 picture for the window (ffmpeg)', UNITS.length > 10, `${UNITS.length} access units`);
  if (f) {
    f.pump();
    const drawn = await until(() => d.js(`(() => { const c = document.querySelector('canvas');
      return !!c && c.width > 0 && !document.body.innerText.includes("Opening the phone's screen"); })()`), 10000);
    check("the window shows the phone's picture", !!drawn);
    // The wheel and a touchpad: one message for each wheel event (0x13), with as far
    // as a page in this window would scroll, in the phone's pixels.
    {
      const g = await d.js(`(() => { const c = document.querySelector('canvas'), r = c.getBoundingClientRect();
        const s = Math.min(r.width / c.width, r.height / c.height);
        return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), k: 1440 / (c.width * s) }; })()`);
      const wheel = async (dx, dy, modifiers = 0) => d.call('Input.dispatchMouseEvent', { type: 'mouseWheel', x: g.x, y: g.y, deltaX: dx, deltaY: dy, modifiers });
      f.scrolls = [];
      f.pointers = [];
      await wheel(0, 100);
      const one = await until(() => (f.scrolls.length ? f.scrolls[0] : null), 3000);
      const want = Math.trunc(100 * g.k);
      check('a notch of the wheel: one scroll message with what a page here would scroll, at the middle of the phone',
        !!one && one[2] === 0 && Math.abs(one[3] - want) <= 1 && Math.abs(one[0] - 720) <= 8 && Math.abs(one[1] - 1560) <= 8,
        JSON.stringify({ one, want, k: g.k }));
      f.scrolls = [];
      await wheel(0, -100, 8);
      const side = await until(() => (f.scrolls.length ? f.scrolls[0] : null), 3000);
      check('Shift and the wheel: sideways', !!side && side[3] === 0 && Math.abs(side[2] + want) <= 1, JSON.stringify(side));
      f.scrolls = [];
      for (let i = 0; i < 12; i++) await wheel(0, 2.5);
      await sleep(400);
      const sum = f.scrolls.reduce((a, s) => a + s[3], 0);
      check("a touchpad's small steps add up, the parts of a pixel kept", Math.abs(sum - 30 * g.k) <= 1, JSON.stringify({ sum, want: 30 * g.k, messages: f.scrolls.length }));
      f.scrolls = [];
      await wheel(0, 100, 2);
      await sleep(400);
      check('Ctrl and the wheel (a pinch) sends nothing to the phone', f.scrolls.length === 0, JSON.stringify(f.scrolls));
      check('…and no wheel notches went as mouse buttons', !(f.pointers || []).some((b) => b & 0x78), JSON.stringify(f.pointers));
    }
    // Sound on this computer, chosen in the window. A browser starts sound only
    // after a click or a key in the page: a person opening the list clicks, and
    // here a real click on the status line stands in for that (the list itself
    // cannot be driven over DevTools).
    const st = await d.js(`(() => { const s = [...document.querySelectorAll('span')].find((x) => /Wi-Fi/.test(x.textContent));
      const r = s.getBoundingClientRect(); return { x: Math.round(r.right + 20), y: Math.round(r.top + r.height / 2) }; })()`);
    await d.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: st.x, y: st.y, button: 'left', buttons: 1, clickCount: 1 });
    await d.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: st.x, y: st.y, button: 'left', buttons: 0, clickCount: 1 });
    await d.js(`(() => { const s = document.querySelector('select[aria-label="Sound"]');
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(s, 'here'); s.dispatchEvent(new Event('change', { bubbles: true })); return s.value; })()`);
    const asked = await heard(f, (m) => m.t === 'audio' && m.on === true && m.phone === false);
    check('Sound on computer: the window asks the phone for its sound, and for the phone to be quiet', !!asked,
      JSON.stringify(f.texts.filter((t) => t.includes('"audio"')).slice(-3)));
    check('…kept for next time', await kept((x) => x.id === 'test-phone' && x.sound === 'here'));
    const hashS = await d.js('location.hash');
    check("…and in the window's address, for a link that comes back", /(^|[#&])sound=here(&|$)/.test(hashS), hashS);
    const before = await d.js('window.__phoneSound ? window.__phoneSound().loudRuns : -1');
    f.sound(1500);
    await sleep(2300);
    const after = await d.js('window.__phoneSound ? window.__phoneSound() : null');
    check("the phone's sound plays in the window", !!after && after.loudRuns - before >= 100, JSON.stringify({ before, after }));
    // The lock screen's PIN pad as the phone describes it (PhoneLock.java), over the black picture.
    const keys = [];
    ['1', '2', '3', '4', '5', '6', '7', '8', '9'].forEach((k, i) => keys.push({ k, x: [150, 610, 1070][i % 3], y: 1490 + Math.floor(i / 3) * 300, w: 220, h: 220 }));
    keys.push({ k: 'del', x: 150, y: 2390, w: 220, h: 220 }, { k: '0', x: 610, y: 2390, w: 220, h: 220 }, { k: 'ok', x: 1070, y: 2390, w: 220, h: 220, on: false });
    f.send(JSON.stringify({ t: 'lock', pad: 'pin', locked: true, w: 1440, h: 3120, keys,
      field: { x: 210, y: 1290, w: 1020, h: 150, n: 2 }, words: [{ s: 'Enter PIN', x: 84, y: 1210, w: 1272, h: 90 }] }));
    const drawnPad = await until(() => d.js(`(() => { const t = document.body.innerText;
      const digits = [...document.querySelectorAll('div')].filter((e) => /^[0-9]$/.test(e.textContent)).length;
      return t.includes('hides its PIN pad') && t.includes('Enter PIN') && t.includes('●●') && digits >= 10 ? digits : 0; })()`), 4000);
    check('the PIN pad the phone hides: drawn in the window, with its words, its keys and two dots', !!drawnPad, String(drawnPad));
    // The computer's number keys go to the phone as keys: the phone presses its pad with them.
    f.keys = [];
    await d.call('Input.dispatchKeyEvent', { type: 'keyDown', key: '7', code: 'Digit7', windowsVirtualKeyCode: 55, text: '7' });
    await d.call('Input.dispatchKeyEvent', { type: 'keyUp', key: '7', code: 'Digit7', windowsVirtualKeyCode: 55 });
    const typed = await until(() => (f.keys || []).some(([down, sym]) => down === 1 && sym === 0x37), 3000);
    check('…and a number typed in the window goes to the phone', !!typed, JSON.stringify(f.keys));
    f.send(JSON.stringify({ t: 'lock', pad: '' }));
    const gone = await until(() => d.js(`!document.body.innerText.includes('hides its PIN pad')`), 3000);
    check('…and the drawing goes when the phone says the pad is gone', !!gone);
    // Screen off (PhoneHelperLink.java): no helper on the phone, then one.
    const press = async (x, y) => {
      await d.call('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 });
      await d.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: 1 });
    };
    const buttonAt = (label) => d.js(`(() => { const b = [...document.querySelectorAll('button')].find((x) => x.textContent === ${JSON.stringify(label)});
      if (!b) return null; const r = b.getBoundingClientRect(); return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) }; })()`);
    const START = 'adb shell "CLASSPATH=/data/app/test/base.apk setsid nohup app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper com.ccrstech.linuxondroid 43000 k &"';
    f.send(JSON.stringify({ t: 'phone', control: true, locked: false, panel: 'none', panelWanted: false, panelStart: START }));
    await sleep(300);
    const off1 = await buttonAt('Screen off');
    if (off1) await press(off1.x, off1.y);
    const note = await until(() => d.js(`document.body.innerText.includes('Two things need a small helper') && document.body.innerText.includes(${JSON.stringify(START)})`), 3000);
    check('Screen off with no helper on the phone: the window says how to start one, with the phone\'s own command', !!off1 && !!note);
    check('…and asks the phone for nothing', !f.texts.some((t) => t.includes('"t":"panel"')), JSON.stringify(f.texts.slice(-2)));
    const close = await buttonAt('Close');
    if (close) await press(close.x, close.y);
    f.send(JSON.stringify({ t: 'phone', control: true, locked: false, panel: 'on', panelWanted: false }));
    await sleep(300);
    const off2 = await buttonAt('Screen off');
    if (off2) await press(off2.x, off2.y);
    const askedOff = await heard(f, (m) => m.t === 'panel' && m.on === false);
    check('with the helper: Screen off asks the phone for its own screen off', !!askedOff, JSON.stringify(f.texts.slice(-2)));
    check("…and the window's address keeps it, for a link that comes back", /(^|[#&])panel=off(&|$)/.test(await d.js('location.hash')), await d.js('location.hash'));
    // A phone that starts its helper itself (PhoneHelperStart.java) says what it needs ("panelHow").
    const panelAsks = () => f.texts.filter((t) => t.includes('"t":"panel"')).length;
    f.send(JSON.stringify({ t: 'phone', control: true, locked: false, panel: 'none', panelWanted: true, panelStart: START, panelHow: 'devoff', app: 'LinuxOnDroid' }));
    await sleep(300);
    let asks = panelAsks();
    const off3 = await buttonAt('Screen off');
    if (off3) await press(off3.x, off3.y);
    const devoff = await until(() => d.js(`document.body.innerText.includes('Developer options are off on the phone') && document.body.innerText.includes(${JSON.stringify(START)})`), 3000);
    check('Developer options off on the phone: the window says so, with a computer\'s command beside it', !!devoff);
    check('…and asks the phone for nothing', panelAsks() === asks, JSON.stringify(f.texts.slice(-2)));
    const close2 = await buttonAt('Close');
    if (close2) await press(close2.x, close2.y);
    f.send(JSON.stringify({ t: 'phone', control: true, locked: false, panel: 'none', panelWanted: true, panelStart: START, panelHow: 'waiting', app: 'LinuxOnDroid' }));
    await sleep(300);
    asks = panelAsks();
    const off4 = await buttonAt('Screen off');
    if (off4) await press(off4.x, off4.y);
    const starting = await until(() => d.js(`document.body.innerText.includes('The phone is starting its helper')`), 3000);
    const askedAgain = await until(() => panelAsks() > asks, 3000);
    check('a phone set up to start its helper: Screen off asks it to, and says so', !!starting && !!askedAgain, JSON.stringify(f.texts.slice(-2)));
    check('…with no computer\'s command shown', !(await d.js(`document.body.innerText.includes(${JSON.stringify(START)})`)));
    const close3 = await buttonAt('Close');
    if (close3) await press(close3.x, close3.y);
    f.send(JSON.stringify({ t: 'protected', what: 'permission' }));
    const prot = await until(() => d.js(`document.body.innerText.includes('Android lets only someone at the phone press the buttons on a permission question')`), 3000);
    check('a press on a screen Android keeps for a person: the window says so in plain words', !!prot);
    // The window going: the phone is told at once, before the link closes.
    await d.call('Page.navigate', { url: 'about:blank' });
    const bye = await heard(f, (m) => m.t === 'bye');
    check('the window going: it says goodbye to the phone', !!bye, JSON.stringify(f.texts.slice(-2)));
  }
  const pageErrors = d.errors.filter((e) => !/WebSocket/.test(e));
  check('no errors in the page', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));
} catch (e) {
  check('the run', false, e && e.stack);
}
console.log(`\n${passed} passed, ${failed} failed`);
await end(failed ? 1 : 0);
