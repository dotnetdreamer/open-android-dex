// Click to picture in the viewer itself: a browser page (the door's
// phone.html, the Cloudflare tunnel's, the page from anywhere) or the desktop
// app's phone window, driven over the DevTools protocol.
//
// One tap at a time, through the page's own input: a real mouse press on the
// canvas (Input.dispatchMouseEvent), at the test pad's square
// (PhoneControlService#testPad, debuggable builds only). t0 is when the page
// got the pointerdown, which is when it sends the touch to the phone. t1 is
// the first frame the page DREW (its drawImage, in the page's own clock) whose
// square changed. The whole loop: the page to the phone, the touch into
// Android, the phone drawing it, screen sharing, the encoder, the network
// back, decoding and drawing. Not in it: the compositor showing the canvas
// (up to a display frame more) and the mouse to the browser.
//
// Closed loop: the next tap goes only after this one's change was drawn (or
// it timed out, counted as lost), and each change must be exactly one flip
// the way the tap sent it. A change no tap explains is reported. An earlier
// harness (latency.mjs, at the encoded frame's arrival) sent taps on a timer
// and could credit one change to the wrong tap: its numbers are withdrawn.
//
//   node page-latency.mjs launch <edge.exe> <url> [--trials N] [--code C] [--port P] [--quality q] [--scale part] [--busy] [--relay] [--profile dir] [--json out]
//   node page-latency.mjs attach <devtools port> <url part> [--trials N] [--quality q] [--scale part] [--busy] [--json out]
//
// --busy: each tap also draws the phone's whole screen anew (the test pad's
// busy mode: an app opening, a page turned), not just the square.
// --scale: the Picture size, the part of the phone's screen the picture is at
// most ({"t":"size","scale":..}, as the page's dropdown sends it; 1 is all of
// it). The summary also has the phone's own numbers over the taps: the
// encoder's time a frame, the bitrate.
//
// launch: a visible Edge with a profile of its own (deleted after); --code
// pairs the page (6 digits for the door, 8 characters for the page from
// anywhere); --relay takes WebRTC away from the page, so the page from
// anywhere uses its relay. attach: a page already open (the desktop app's
// window, started with WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
// --remote-debugging-port), whose socket is found at its next send.
//
// Needs screen sharing (the slow view is three pictures a second): the
// phone's unattended access (PROJECT_MEDIA app-op), which starts it silently.
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const mode = argv[0];
const opt = (name, def) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : def; };
const flag = (name) => argv.includes(name);
const trials = Number(opt('--trials', 30));
/** How long each click holds the button down, ms (a person's click is about 100). */
const holdMs = Number(opt('--hold', 30));
const quality = opt('--quality', 'medium');
const scale = opt('--scale', null) == null ? null : Number(opt('--scale', 1));
// --sound: the page's Sound choice (phone, here, both, off), set before it loads; --tone keeps
// the phone playing a tone through the taps ({"t":"testtone"}, the test pad's mark allows it),
// so that the sound is really read, encoded and sent while the picture is measured.
const soundChoice = opt('--sound', null);
const tone = flag('--tone');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

// The probe: in the page before its own scripts (launch), or into it as it runs (attach).
const PROBE = `(() => {
  if (window.__probe) return 'already';
  const P = window.__probe = { sock: null, send: null, pad: null, video: null, stats: null, samples: [], downs: [], on: false, msgs: 0 };
  const onText = (data) => {
    if (typeof data !== 'string') return;
    let m; try { m = JSON.parse(data); } catch { return; }
    P.msgs++;
    if (m.t !== 'stats') { (P.texts = P.texts || []).push(data.slice(0, 200)); if (P.texts.length > 20) P.texts.shift(); }
    if (m.t === 'testpad') P.pad = { x: m.x, y: m.y, size: m.size };
    else if (m.t === 'padtouch') (P.held = P.held || []).push({ ms: m.heldMs, moves: m.moves, cancelled: m.cancelled });
    else if (m.t === 'video') P.video = { dw: m.dw, dh: m.dh, cw: m.cw, ch: m.ch, scale: m.scale, source: m.source, quality: m.quality, codec: m.codec };
    else if (m.t === 'stats') { P.stats = m; if (P.on) (P.allStats = P.allStats || []).push(m); }
  };
  P.sent = 0;
  const capture = (s, sendFn) => { P.sent++; if (P.sock !== s) { P.sock = s; P.send = (x) => { P.mine = (P.mine || 0) + 1; return sendFn(x); }; } };
  // Only the phone's own WebSocket (the door's /phone, the desktop app's
  // /phone/<token>): the page from anywhere also has the WebAssembly's
  // socket to its relay, which must never be written to from here.
  const wsSend = WebSocket.prototype.send;
  const hooked = new WeakSet();
  WebSocket.prototype.send = function (m) {
    if (/\\/phone([/?]|$)/.test(new URL(this.url).pathname + (new URL(this.url).search ? '?' : ''))) {
      if (!hooked.has(this)) { hooked.add(this); this.addEventListener('message', (ev) => onText(ev.data)); }
      capture(this, (x) => wsSend.call(this, x));
    }
    return wsSend.call(this, m);
  };
  // The page from anywhere makes its socket with window.PHONE_SOCKET: wrapped as it is set.
  let real = null;
  const wrapped = () => {
    const s = real();
    const own = s.send.bind(s);
    s.send = (m) => { capture(s, own); return own(m); };
    let h = null;
    const tee = (ev) => { onText(ev.data); if (h) h(ev); };
    Object.defineProperty(s, 'onmessage', { configurable: true, get() { return h ? tee : null; }, set(v) { h = v; } });
    return s;
  };
  try {
    Object.defineProperty(window, 'PHONE_SOCKET', { configurable: true, get() { return real ? wrapped : undefined; }, set(v) { real = v; } });
  } catch {}
  const draw = CanvasRenderingContext2D.prototype.drawImage;
  CanvasRenderingContext2D.prototype.drawImage = function (...a) {
    const r = draw.apply(this, a);
    if (P.on && P.pad && P.video && P.video.dw > 0 && this.canvas.width > 0) {
      const sx = this.canvas.width / P.video.dw, sy = this.canvas.height / P.video.dh;
      const half = Math.max(2, Math.floor(P.pad.size * sx * 0.15));
      const cx = Math.round(P.pad.x * sx), cy = Math.round(P.pad.y * sy);
      const d = this.getImageData(cx - half, cy - half, half * 2, half * 2).data;
      let sum = 0;
      for (let i = 0; i < d.length; i += 4) sum += d[i] * 0.299 + d[i + 1] * 0.587 + d[i + 2] * 0.114;
      P.samples.push([performance.now(), Math.round(sum / (d.length / 4))]);
      if (P.samples.length > 20000) P.samples.splice(0, 5000);
      P.canvas = this.canvas;
    }
    return r;
  };
  addEventListener('pointerdown', () => P.downs.push(performance.now()), true);
  return 'in';
})()`;

// ── DevTools ──
async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  const logs = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled') logs.push(m.params.args.map((a) => a.value ?? a.description).join(' '));
    if (m.method === 'Runtime.exceptionThrown') logs.push('exception: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  };
  const call = (method, params = {}) => new Promise((r) => { const n = ++id; waiting.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
  const evaluate = async (expr) => {
    const r = await call('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };
  return { ws, call, evaluate, logs };
}

async function targets(port) {
  for (let i = 0; i < 60; i++) {
    try { return await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch { await sleep(250); }
  }
  throw new Error(`no DevTools on ${port}`);
}

let browser = null, profile = null, page, keepProfile = false;
if (mode === 'launch') {
  const [edge, url] = [argv[1], argv[2]];
  const port = Number(opt('--port', 9336));
  // --profile: a profile kept between runs (the page from anywhere keeps its pairing there).
  const kept = opt('--profile', null);
  profile = kept || mkdtempSync(join(tmpdir(), 'phone-latency-'));
  if (kept) keepProfile = true;
  browser = await launchEdge(edge, { port, profile, args: ['--ignore-certificate-errors', '--window-size=520,1040', '--window-position=40,10'] });
  page = await cdp(browser.page.webSocketDebuggerUrl);
  await page.call('Runtime.enable');
  await page.call('Page.enable');
  let pre = PROBE;
  // No WebRTC in the page: the page from anywhere then takes its relay.
  if (flag('--relay')) pre = `delete window.RTCPeerConnection; window.RTCPeerConnection = undefined; ${PROBE}`;
  await page.call('Page.addScriptToEvaluateOnNewDocument', { source: pre });
  if (soundChoice) await page.call('Page.addScriptToEvaluateOnNewDocument', { source: `try { localStorage.setItem('lod.phoneSound', ${JSON.stringify(soundChoice)}); } catch {}` });
  await page.call('Page.navigate', { url });
  await sleep(1500);
  const code = opt('--code', null);
  if (code) {
    // The door's six digits (#pair) or the page from anywhere's eight characters (#apair).
    for (let i = 0; i < 60; i++) {
      const which = await page.evaluate(`(() => { const a = document.getElementById('apair'); const p = document.getElementById('pair');
        return a && !a.hidden ? 'apair' : p && !p.hidden ? 'pair' : ''; })()`);
      if (which) {
        const input = which === 'apair' ? 'acode' : 'code';
        await page.evaluate(`document.getElementById('${input}').value = ${JSON.stringify(code)}; document.getElementById('${which}').requestSubmit(); 1`);
        log('pairing with the code through', which);
        break;
      }
      await sleep(500);
    }
  }
} else if (mode === 'attach') {
  const [port, part] = [Number(argv[1]), argv[2]];
  const t = (await targets(port)).find((x) => x.type === 'page' && x.url.includes(part));
  if (!t) throw new Error(`no page at ${port} with ${part}`);
  page = await cdp(t.webSocketDebuggerUrl);
  await page.call('Runtime.enable');
  log('attached:', t.url, await page.evaluate(PROBE));
} else {
  console.log('usage: page-latency.mjs launch <edge> <url> … | attach <port> <url part> …');
  process.exit(2);
}

const P = (expr) => page.evaluate(`(() => { const P = window.__probe; return ${expr}; })()`);
const say = (obj) => P(`P.send && P.send(${JSON.stringify(JSON.stringify(obj))}), !!P.send`);
const done = async (code) => {
  try { await say({ t: 'testpad', on: false }); } catch { /* gone */ }
  if (page.logs.length) console.log('page console (last 10):\n  ' + page.logs.slice(-10).join('\n  '));
  try { page.ws.close(); } catch { /* gone */ }
  if (browser) {
    await sleep(300); // the pad's off on its way
    await browser.close();
    if (!keepProfile) try { rmSync(profile, { recursive: true, force: true }); } catch { /* Edge holds a file */ }
  }
  process.exit(code);
};

// The socket, then a picture at the quality asked for.
let ok = false;
for (let i = 0; i < 120 && !ok; i++) { await sleep(500); ok = await P('!!P.send'); }
if (!ok) { log('the page never sent on a socket'); await done(1); }
// The phone describes the picture whenever its encoder starts again: a page
// joined while it runs (attach) is told nothing new by the quality it already
// has, so another quality first, and then the one asked for.
if (mode === 'attach') {
  await say({ t: 'quality', mode: quality === 'low' ? 'medium' : 'low' });
  await sleep(1500);
}
await say({ t: 'quality', mode: quality });
for (let i = 0; i < 40; i++) { await sleep(250); if (await P('!!P.video && P.video.quality === ' + JSON.stringify(quality))) break; }
if (scale != null && (await P('P.video && P.video.scale')) !== scale) {
  await say({ t: 'size', scale });
  for (let i = 0; i < 40; i++) { await sleep(250); if (await P('!!P.video && P.video.scale === ' + scale)) break; }
}
let video = await P('P.video');
if (!video) { log('no picture description from the phone'); await done(1); }
if (scale != null && video.scale !== scale) { log(`the phone did not take the Picture size ${scale}: ${JSON.stringify(video)}`); await done(1); }
if (video.source !== 'screen') {
  await say({ t: 'share' });
  for (let i = 0; i < 60 && video.source !== 'screen'; i++) { await sleep(250); video = await P('P.video'); }
}
if (video.source !== 'screen') { log('no screen sharing (only the slow view): is unattended access set up?'); await done(1); }
// The phone answers only when it puts a pad up: one left by a session that is
// still ending (a run just before this one) is taken down first, and asked again.
for (let attempt = 0; attempt < 4 && !(await P('!!P.pad')); attempt++) {
  if (attempt) { await say({ t: 'testpad', on: false }); await sleep(1500); }
  await say({ t: 'testpad', on: true, busy: flag('--busy') });
  for (let i = 0; i < 16; i++) { await sleep(250); if (await P('!!P.pad')) break; }
}
const pad = await P('P.pad');
if (!pad) {
  log('no test pad: is files/phone-remote/testpad there?');
  log('what the page heard:', JSON.stringify(await P('({ msgs: P.msgs, texts: P.texts, video: P.video, stats: P.stats })')));
  log('what it sent:', JSON.stringify(await P('({ sent: P.sent, mine: P.mine, state: P.sock && P.sock.readyState, kind: P.sock && P.sock.constructor && P.sock.constructor.name })')));
  await done(1);
}
await P('(P.on = true, P.samples.length = 0, true)');
await sleep(1500);
// A key frame now: the pad's first picture may be behind a few deltas.
await say({ t: 'keyframe' });
await sleep(700);
const route = await page.evaluate(`typeof window.PHONE_ROUTE === 'string' ? window.PHONE_ROUTE : ''`);
log('picture', JSON.stringify(video), 'pad', JSON.stringify(pad), route ? `route ${route}` : '');
// The sound chosen: a real click (a page may start sound only after one), on the status line
// rather than the phone's picture, then the choice again, as a person picking it would.
let toneTimer = null;
if (soundChoice) {
  const st = await page.evaluate(`(() => { const r = document.querySelector('.status').getBoundingClientRect(); return [r.right - 20, r.top + r.height / 2]; })()`);
  for (const type of ['mousePressed', 'mouseReleased']) await page.call('Input.dispatchMouseEvent', { type, x: st[0], y: st[1], button: 'left', clickCount: 1 });
  await page.evaluate(`(() => { const s = document.getElementById('sound'); s.value = ${JSON.stringify(soundChoice)}; s.dispatchEvent(new Event('change', { bubbles: true })); return 1; })()`);
  await sleep(1500);
  log('sound:', JSON.stringify(await page.evaluate('window.lodPhone && window.lodPhone()')));
}
if (tone) {
  await say({ t: 'testtone', hz: 440, ms: 9500 });
  toneTimer = setInterval(() => { say({ t: 'testtone', hz: 440, ms: 9500 }).catch(() => {}); }, 9000);
  await sleep(1000);
}

/** Where the square is on the page, in CSS pixels (the canvas drawn contain-fit, as phone.js lays it out). */
async function where() {
  return P(`(() => {
    const c = P.canvas || document.querySelector('canvas');
    const r = c.getBoundingClientRect();
    const cw = c.width, ch = c.height;
    const s = Math.min(r.width / cw, r.height / ch);
    const ox = r.left + (r.width - cw * s) / 2, oy = r.top + (r.height - ch * s) / 2;
    return { x: ox + P.pad.x * cw / P.video.dw * s, y: oy + P.pad.y * ch / P.video.dh * s };
  })()`);
}

const DARK = 128;
const last = async () => (await P('P.samples.length ? P.samples[P.samples.length - 1] : null'));
let lastSample = await last();
if (!lastSample) { log('nothing drawn with the pad up'); await done(1); }
let white = lastSample[1] >= DARK;
const results = [];
let lost = 0, strays = 0;
for (let i = 0; i < trials; i++) {
  const at = await where();
  const downsBefore = await P('P.downs.length');
  const seenBefore = await P('P.samples.length');
  await page.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: at.x, y: at.y });
  await page.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: at.x, y: at.y, button: 'left', buttons: 1, clickCount: 1 });
  await sleep(holdMs);
  await page.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: at.x, y: at.y, button: 'left', buttons: 0, clickCount: 1 });
  let t0 = null;
  for (let k = 0; k < 100 && t0 == null; k++) { t0 = await P(`P.downs.length > ${downsBefore} ? P.downs[${downsBefore}] : null`); if (t0 == null) await sleep(5); }
  if (t0 == null) { log(`tap ${i + 1}: the page got no pointerdown`); lost++; continue; }
  // The first frame drawn after t0 whose square is the other colour.
  const want = !white;
  let hit = null;
  const deadline = Date.now() + 5000;
  while (!hit && Date.now() < deadline) {
    hit = await P(`(() => { for (let j = ${seenBefore}; j < P.samples.length; j++) { const s = P.samples[j];
      if (s[0] > ${t0} && (s[1] >= ${DARK}) === ${want}) return s; } return null; })()`);
    if (!hit) await sleep(10);
  }
  if (!hit) {
    lost++;
    lastSample = await last();
    white = lastSample ? lastSample[1] >= DARK : white;
    log(`tap ${i + 1}: no change within 5 s (lost)`);
    continue;
  }
  white = want;
  const ms = hit[0] - t0;
  results.push(ms);
  // Settle, then check nothing flipped by itself: one tap, one change.
  await sleep(250 + Math.random() * 250);
  const flips = await P(`(() => { let n = 0, w = null; for (let j = 0; j < P.samples.length; j++) { const s = P.samples[j];
      if (s[0] < ${hit[0]}) continue; const b = s[1] >= ${DARK}; if (w === null) w = b; else if (b !== w) { n++; w = b; } } return n; })()`);
  if (flips) { strays += flips; log(`tap ${i + 1}: ${flips} change(s) after its own that no tap explains`); }
  if ((i + 1) % 10 === 0) log(`${i + 1} taps, median so far ${median(results).toFixed(0)} ms`);
}
function median(a) { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : NaN; }
const sorted = [...results].sort((a, b) => a - b);
const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
const stats = await P('P.stats');
// How long the phone held each click (the pad's own touch, down to up or to
// a cancel), how often the finger moved, and whether Android cancelled the
// touch instead of lifting it (ACTION_CANCEL: a long press the phone never
// finished), if the build says so. Each touch is kept whole: until
// 2026-11-22 only the time was, and a cancelled touch has a time too.
await sleep(800);
const touches = (await P('P.held || []')).slice();
const held = touches.map((t) => t.ms);
const heldSorted = [...held].sort((a, b) => a - b);
const hp = (p) => heldSorted[Math.min(heldSorted.length - 1, Math.floor(p * heldSorted.length))];
const longPresses = held.filter((h) => h >= 500).length;
const cancelled = touches.filter((t) => t.cancelled === true).length;
const unknown = touches.filter((t) => typeof t.cancelled !== 'boolean').length;
if (held.length) {
  log(`held on the phone for ${holdMs} ms clicks, ${held.length} touches: median ${hp(0.5)} ms, shortest ${heldSorted[0]}, p90 ${hp(0.9)}, longest ${heldSorted[heldSorted.length - 1]}; at or past 500 ms (a long press on this phone): ${longPresses}; cancelled by Android: ${cancelled}${unknown ? ` (${unknown} with no word on it)` : ''}; moves: ${touches.map((t) => t.moves).join(' ')}`);
}
// The phone's own numbers, a second at a time over the taps: the encoder's
// time a frame (seconds that had frames), the bitrate, the round trip.
if (toneTimer) clearInterval(toneTimer);
const perSecond = await P('P.allStats || []');
const mid = (k, keep = () => true) => {
  const v = perSecond.filter(keep).map((x) => x[k]).filter((x) => typeof x === 'number' && x >= 0).sort((a, b) => a - b);
  return v.length ? v[Math.floor(v.length / 2)] : null;
};
const phone = { seconds: perSecond.length, encodeMs: mid('encodeMs', (x) => x.fps > 0), kbps: mid('kbps'), fps: mid('fps'), rttMs: mid('rttMs'), targetKbps: mid('targetKbps'), soundKbps: mid('soundKbps') };
const soundHeard = soundChoice ? await page.evaluate('window.lodPhone && window.lodPhone()') : null;
if (soundHeard) log('sound in the page after the taps:', JSON.stringify(soundHeard));
const summary = {
  mode, url: mode === 'launch' ? argv[2] : argv[2], quality, scale: scale ?? undefined, busy: flag('--busy'), picture: `${video.cw}x${video.ch}`, phone,
  route: route || undefined, taps: trials, timed: sorted.length, lost, strays,
  median: Math.round(pct(0.5)), best: Math.round(sorted[0]), p90: Math.round(pct(0.9)), worst: Math.round(sorted[sorted.length - 1]),
  all: sorted.map((x) => Math.round(x)), video, phoneStats: stats,
  holdMs, heldOnPhone: held, longPresses, touches, cancelled, cancelUnknown: unknown, sound: soundChoice || undefined, tone, soundHeard,
};
log(`click to picture drawn, ${sorted.length} of ${trials} taps (${quality}, ${video.cw}x${video.ch}${route ? ', ' + route : ''}): median ${summary.median} ms, best ${summary.best}, p90 ${summary.p90}, worst ${summary.worst}; lost ${lost}, unexplained changes ${strays}`);
log('phone, a second at a time:', JSON.stringify(phone));
if (stats) log('phone stats', JSON.stringify(stats));
const out = opt('--json', null);
if (out) writeFileSync(out, JSON.stringify(summary, null, 2));
await done(sorted.length ? 0 : 1);
