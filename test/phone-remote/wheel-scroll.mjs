// The wheel and a touchpad on the phone's picture (Remote desktop), on a real
// phone, through the door's phone.html in a visible Edge.
//
//   node wheel-scroll.mjs <edge.exe> <adb serial> <door address> [--pad square|scroll] [--app] [--only scroll,clicks,drag,app] [--shots dir] [--json out]
//
// The parts: scroll (notches, a fast spin, touchpad swipes both ways and
// sideways, Shift), clicks (alone, right after a notch, during a touchpad's
// momentum), drag (the left button's finger, raw: a move to the same pixel),
// app (Settings, with --app).
//
// The page's own wheel listener gets trusted wheel events (DevTools'
// Input.dispatchMouseEvent, mouseWheel, deltaMode 0): a mouse's notch is
// 100 px, as Edge on Windows gives it; a touchpad is a stream of small deltas
// and then the momentum Windows adds after the fingers lift, as Edge passes
// it on. What the phone did is read from:
// - the test pad (PhoneControlService#testPad), over the page's own socket:
//   each touch's time down, its moves, and whether Android cancelled it
//   (padtouch). With --pad scroll (the mark file says "scroll"), the pad is
//   a page that scrolls both ways and logs where it is (OpenDeXPad in
//   logcat): how far each wheel input scrolled it, and whether it went on
//   after the finger lifted (a fling);
// - --app: Android's own Settings app, its screen read with adb's
//   screencap before and after, and the shift between the two found.
//
// Needs a debuggable build (run-as reads the door's code and opens its
// sign-in window), Remote desktop on with unattended access and control. At
// the end: this test browser signs itself out, the sign-in window and the
// test pad's mark are put back, and the phone is left on its home screen.
import { execFileSync, spawn } from 'node:child_process';
import https from 'node:https';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const [edgeExe, serial, door] = argv;
const opt = (n, d) => (argv.includes(n) ? argv[argv.indexOf(n) + 1] : d);
const padKind = opt('--pad', 'square');
const withApp = argv.includes('--app');
const only = opt('--only', null) ? opt('--only', '').split(',') : null;
const want = (part) => !only || only.includes(part);
const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
const PKG = process.env.PKG || 'com.ccrstech.linuxondroid';
const sh = (cmd) => execFileSync(ADB, ['-s', serial, 'shell', cmd], { encoding: 'utf8' }).replace(/\r/g, '').trim();
const runas = (cmd) => sh(`run-as ${PKG} sh -c '${cmd}'`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (...a) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s`, ...a);
const results = {};
async function until(fn, ms = 8000, every = 100) {
  const end = Date.now() + ms;
  let v;
  while (Date.now() < end) {
    v = await fn();
    if (v) return v;
    await sleep(every);
  }
  return v;
}

// ── the phone ──
const power = () => (sh('dumpsys power | grep -E "mWakefulness=" | head -1').match(/mWakefulness=(\w+)/) || [])[1];
const keyguard = () => /isKeyguardShowing=true/.test(sh('dumpsys window | grep isKeyguardShowing | head -1'));
const focus = () => (sh('dumpsys window | grep -E "mCurrentFocus=Window" | head -1').match(/\{[^ ]+ [^ ]+ ([^}]+)\}/) || [])[1] || '';
/** Awake, unlocked, and the window expected in front: the only state any touch is sent in. */
function safe(expect) {
  const p = power(), k = keyguard(), f = focus();
  const ok = p === 'Awake' && !k && (!expect || f.includes(expect));
  if (!ok) log(`not touching the phone: ${p}, keyguard ${k}, focus ${f}`);
  return ok;
}
const forget = (tok) => new Promise((res) => {
  const body = new URLSearchParams({ token: tok }).toString();
  const req = https.request(`${door}/forget`, {
    method: 'POST', rejectUnauthorized: false,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body), Origin: door },
  }, (r) => { r.resume(); res(r.statusCode); });
  req.on('error', (e) => res(String(e)));
  req.setTimeout(8000, () => { req.destroy(); res('no answer'); });
  req.end(body);
});

// The scroll pad's own lines, as they come: "y <scrollY> <uptime>", "x ...", "down|up|cancel <uptime> ...".
const padLines = [];
let logcat = null;
function startLogcat() {
  // From now on only (-T 1): the phone's log is everybody's, and is never cleared here.
  logcat = spawn(ADB, ['-s', serial, 'logcat', '-v', 'raw', '-T', '1', '-s', 'OpenDeXPad:I'], { stdio: ['ignore', 'pipe', 'ignore'] });
  let buf = '';
  logcat.stdout.on('data', (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).replace(/\r/g, '').trim();
      buf = buf.slice(i + 1);
      if (/^(x|y|down|up|cancel|pad) /.test(line)) padLines.push({ line, at: Date.now() });
    }
  });
}

/** The screen as adb's screencap reads it, raw: a row signature for each line (the mean of 48 columns' bands). */
function screen() {
  const b = execFileSync(ADB, ['-s', serial, 'exec-out', 'screencap'], { maxBuffer: 64 << 20 });
  const w = b.readUInt32LE(0), h = b.readUInt32LE(4);
  const head = b.length - w * h * 4;
  const bands = 48;
  const rows = new Float32Array(h * bands);
  for (let y = 0; y < h; y++) {
    for (let k = 0; k < bands; k++) {
      let s = 0, n = 0;
      const x0 = Math.floor((k * w) / bands), x1 = Math.floor(((k + 1) * w) / bands);
      for (let x = x0; x < x1; x += 2) {
        const o = head + (y * w + x) * 4;
        s += b[o] * 0.299 + b[o + 1] * 0.587 + b[o + 2] * 0.114;
        n++;
      }
      rows[y * bands + k] = s / n;
    }
  }
  return { w, h, bands, rows };
}
/**
 * How far the content moved between two screens: the shift d (screen
 * pixels, positive when the content went up, as scrolling down does) that
 * makes the middle of the screen match best. Rows that do not change at
 * all between the two (bars) are left out.
 */
function shift(a, b) {
  const { h, bands } = a;
  const top = Math.floor(h * 0.18), bottom = Math.floor(h * 0.9);
  let best = { d: 0, err: Infinity };
  for (let d = -Math.floor(h * 0.6); d <= Math.floor(h * 0.6); d++) {
    let err = 0, n = 0;
    for (let y = top; y < bottom; y += 2) {
      const ya = y + d; // the row of a that is now at y in b
      if (ya < top || ya >= bottom) continue;
      for (let k = 4; k < bands - 4; k++) err += Math.abs(a.rows[ya * bands + k] - b.rows[y * bands + k]);
      n++;
    }
    if (n < (bottom - top) / 6) continue;
    err /= n;
    if (err < best.err) best = { d, err };
  }
  return best;
}

// ── the page ──
const PROBE = `(() => {
  if (window.__t) return;
  const P = window.__t = { texts: [], touches: [], pad: null, video: null, sock: null, wheels: [] };
  const WS = window.WebSocket;
  window.WebSocket = class extends WS {
    constructor(...a) {
      super(...a);
      if (!String(a[0]).includes('/phone')) return;
      P.sock = this;
      const own = this.send.bind(this);
      P.sent = [];
      this.send = (m) => {
        if (m instanceof Uint8Array && (m[0] === 0x10 || m[0] === 0x13)) P.sent.push([performance.now(), ...m]);
        return own(m);
      };
      this.addEventListener('message', (ev) => {
        if (typeof ev.data !== 'string') return;
        let m; try { m = JSON.parse(ev.data); } catch { return; }
        if (m.t === 'testpad') P.pad = m;
        else if (m.t === 'padtouch') P.touches.push({ ms: m.heldMs, moves: m.moves, cancelled: m.cancelled, at: performance.now() });
        else if (m.t === 'video') P.video = m;
        else if (m.t !== 'stats') { P.texts.push(m); if (P.texts.length > 40) P.texts.shift(); }
      });
    }
  };
  addEventListener('wheel', (e) => P.wheels.push([performance.now(), e.deltaX, e.deltaY, e.deltaMode, e.shiftKey]), true);
})();`;

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  };
  const call = (method, params = {}) => new Promise((r) => {
    const n = ++id;
    const timer = setTimeout(() => { waiting.delete(n); r({ error: { message: `${method}: no answer` } }); }, 15000);
    waiting.set(n, (m) => { clearTimeout(timer); r(m); });
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const evaluate = async (e) => {
    const r = await call('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) return { error: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
    return r.result?.result?.value;
  };
  return { ws, call, evaluate };
}
let page = null;
const T = (expr) => page.evaluate(`(() => { const P = window.__t; return ${expr}; })()`);
const say = (obj) => T(`P.sock && P.sock.readyState === 1 && (P.sock.send(${JSON.stringify(JSON.stringify(obj))}), true)`);

/** Where a phone pixel is on the page, and how many phone pixels one CSS pixel of the page is. */
async function geometry() {
  return page.evaluate(`(() => {
    const P = window.__t, c = document.getElementById('screen'), r = c.getBoundingClientRect();
    const v = P.video, cw = v.cw, ch = v.ch;
    const s = Math.min(r.width / cw, r.height / ch);
    const ox = r.left + (r.width - cw * s) / 2, oy = r.top + (r.height - ch * s) / 2;
    return { ox, oy, s, cw, ch, dw: v.dw, dh: v.dh, k: v.dw / (cw * s), inner: [innerWidth, innerHeight], canvas: [c.width, c.height] };
  })()`);
}
const onPage = (g, px, py) => ({ x: g.ox + (px * g.cw / g.dw) * g.s, y: g.oy + (py * g.ch / g.dh) * g.s });

async function wheelAt(at, dx, dy, shift = false) {
  await page.call('Input.dispatchMouseEvent', { type: 'mouseWheel', x: at.x, y: at.y, deltaX: dx, deltaY: dy, modifiers: shift ? 8 : 0 });
}
async function click(at, holdMs = 100) {
  await page.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: at.x, y: at.y });
  await page.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: at.x, y: at.y, button: 'left', buttons: 1, clickCount: 1 });
  await sleep(holdMs);
  await page.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: at.x, y: at.y, button: 'left', buttons: 0, clickCount: 1 });
}
/**
 * Wheel inputs: [delay before, dx, dy] each. Through DevTools (trusted
 * input, Edge's own pipeline), each waited for: Edge took about 60 ms a
 * call here, so a stream comes slower than a real one.
 */
async function play(at, steps, shiftKey = false) {
  const start = performance.now();
  let due = 0;
  for (const [delay, dx, dy] of steps) {
    due += delay;
    const wait = start + due - performance.now();
    if (wait > 1) await sleep(wait);
    await wheelAt(at, dx, dy, shiftKey);
  }
  return performance.now() - start;
}
/**
 * The same, made in the page at the times given (WheelEvent on the canvas,
 * which the page's listener takes as it takes Edge's own): a touchpad's 60
 * events a second, or a fast spin's notches 18 ms apart, as they come.
 */
async function playInPage(at, steps, shiftKey = false) {
  return page.evaluate(`new Promise((done) => {
    const c = document.getElementById('screen');
    const steps = ${JSON.stringify(steps)};
    const t0 = performance.now();
    let due = 0;
    steps.forEach(([delay, dx, dy], i) => {
      due += delay;
      setTimeout(() => {
        c.dispatchEvent(new WheelEvent('wheel', { clientX: ${at.x}, clientY: ${at.y}, deltaX: dx, deltaY: dy, deltaMode: 0,
          shiftKey: ${shiftKey}, bubbles: true, cancelable: true }));
        if (i === steps.length - 1) done(Math.round(performance.now() - t0));
      }, due);
    });
  })`);
}
const notch = (n, every, dir = 1) => Array.from({ length: n }, (_, i) => [i ? every : 0, 0, 100 * dir]);
/**
 * A two-finger swipe on a Windows touchpad, as Edge passes it on: about 60
 * events a second, the fingers speeding up and slowing, then the momentum
 * Windows adds after they lift, fading over a second. About 350 px in all.
 */
function touchpad(dir = 1, axis = 'y') {
  const fingers = [2, 4, 8, 12, 16, 20, 22, 22, 20, 18, 16, 14];
  const out = fingers.map((d, i) => [i ? 16 : 0, d]);
  for (let d = 14 * 0.92; d >= 0.5; d *= 0.92) out.push([16, Math.round(d * 10) / 10]);
  return out.map(([t, d]) => (axis === 'y' ? [t, 0, d * dir] : [t, d * dir, 0]));
}
const total = (steps) => steps.reduce((a, s) => [a[0] + s[1], a[1] + s[2]], [0, 0]);

/** The scroll pad's place, from its log: the last x and y it said. */
function padAt() {
  let x = null, y = null;
  for (const { line } of padLines) {
    const m = line.match(/^(x|y) (-?\d+) (\d+)/);
    if (m) { if (m[1] === 'x') x = +m[2]; else y = +m[2]; }
  }
  return { x, y };
}
/** After a trial: how far the scroll pad went, when it stopped moving, and what it did after the last finger lifted. */
function padSince(i0, y0, x0) {
  const lines = padLines.slice(i0).map((l) => l.line);
  let y = y0, x = x0, lastMove = null, liftAt = null, yAtLift = null, xAtLift = null, firstDown = null;
  const padTouches = [];
  for (const l of lines) {
    const m = l.match(/^(x|y) (-?\d+) (\d+)/);
    if (m) { if (m[1] === 'x') x = +m[2]; else y = +m[2]; lastMove = +m[3]; continue; }
    const t = l.match(/^(down|up|cancel) (\d+)(.*)$/);
    if (t) {
      padTouches.push(t[1] + t[3]);
      if (t[1] === 'down' && firstDown == null) firstDown = +t[2];
      if (t[1] !== 'down') { liftAt = +t[2]; yAtLift = y; xAtLift = x; }
    }
  }
  return { dy: y - y0, dx: x - x0, afterLiftY: yAtLift == null ? null : y - yAtLift, afterLiftX: xAtLift == null ? null : x - xAtLift,
    settledMsAfterLift: lastMove != null && liftAt != null ? lastMove - liftAt : null,
    firstDownToLastMoveMs: firstDown != null && lastMove != null ? lastMove - firstDown : null, padTouches };
}

// ── set up ──
const last = sh(`logcat -d -v time | grep -E "OpenDeX.*\\[phone\\].*(opened|closed) the screen" | tail -1`);
log('last session line on the phone:', last || '(none in the log)');
const untilBefore = runas('cat files/browser/pairing-until 2>/dev/null');
const padMarkBefore = runas('[ -e files/phone-remote/testpad ] && cat files/phone-remote/testpad && echo "<mark>" || echo "<none>"');
const code = runas('cat files/browser/code').split(' ')[0];
const phoneNow = Number(sh('date +%s%3N'));
if (!(phoneNow > 1.6e12)) throw new Error(`the phone's clock reads ${phoneNow}`);
runas(`echo ${phoneNow + 900000} > files/browser/pairing-until; mkdir -p files/phone-remote; echo ${padKind === 'scroll' ? 'scroll' : ''} > files/phone-remote/testpad`);
const density = Number((sh('wm density').match(/(\d+)\s*$/) || [])[1]) / 160;
log(`phone: density ${density}, ${power()}, keyguard ${keyguard()}, focus ${focus()}`);
startLogcat();

const profile = mkdtempSync(join(tmpdir(), 'wheel-scroll-'));
const browser = await launchEdge(edgeExe, { port: 9341, profile, args: ['--ignore-certificate-errors', '--window-size=560,1080', '--window-position=40,10', '--disable-sync', '--disable-extensions'] });
let token = null;
try {
  page = await cdp(browser.page.webSocketDebuggerUrl);
  await page.call('Runtime.enable');
  await page.call('Page.enable');
  await page.call('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });
  await page.call('Page.navigate', { url: `${door}/phone.html` });
  await until(() => page.evaluate(`!document.getElementById('pair').hidden`), 15000);
  await page.evaluate(`document.getElementById('code').value = ${JSON.stringify(code)}; document.getElementById('pair').requestSubmit(); 1`);
  log(`session asked for at ${new Date().toISOString()}`);
  const upNow = await until(async () => / × /.test(await page.evaluate(`document.getElementById('status').textContent`)), 20000);
  if (!upNow) throw new Error('no picture');
  token = await page.evaluate(`localStorage.getItem('lod.token')`);
  await until(() => T('!!P.video'), 5000);
  let g = await geometry();
  log(`picture ${g.cw}x${g.ch} of a ${g.dw}x${g.dh} screen, drawn at ${(g.cw * g.s).toFixed(0)}x${(g.ch * g.s).toFixed(0)} CSS px in a ${g.inner.join('x')} page: one CSS px is ${g.k.toFixed(2)} phone px`);
  results.geometry = g;
  results.density = density;
  const centre = onPage(g, g.dw / 2, g.dh / 2);

  // ── on the test pad ──
  if (want('scroll') || want('clicks') || want('drag')) {
    for (let attempt = 0; attempt < 4 && !(await T('!!P.pad')); attempt++) {
      if (attempt) { await say({ t: 'testpad', on: false }); await sleep(1200); }
      await say({ t: 'testpad', on: true });
      await until(() => T('!!P.pad'), 4000);
    }
    const pad = await T('P.pad');
    if (!pad) throw new Error('no test pad: is files/phone-remote/testpad there?');
    await sleep(1200);
    if (padKind === 'scroll') await until(() => padLines.some((l) => l.line.startsWith('pad ')) && padAt().y != null, 5000);
    log(`test pad up (${padKind})`, padKind === 'scroll' ? JSON.stringify(padAt()) : '');
    const trial = async (name, fn, settle = 1500) => {
      if (!safe()) { results[name] = { skipped: 'phone not awake or locked' }; return; }
      const n0 = await T('P.touches.length');
      const s0 = await T('P.sent.length');
      const i0 = padLines.length;
      const at0 = padAt();
      const t = performance.now();
      const info = await fn();
      await sleep(settle);
      const touches = (await T(`P.touches.slice(${n0})`)) || [];
      const sent = (await T(`P.sent.slice(${s0})`)) || [];
      const r = { sentMs: Math.round(performance.now() - t - settle), info, touches, messages: sent.length,
        wheelBits: sent.filter((m) => m[1] === 0x10 && (m[2] & 0x78)).length };
      if (padKind === 'scroll') Object.assign(r, padSince(i0, at0.y, at0.x));
      r.wheelMessages = sent.filter((m) => m[1] === 0x13).length;
      results[name] = r;
      const { padTouches, ...rest } = r;
      log(name, JSON.stringify({ ...rest, touches: r.touches.map((x) => `${x.ms}ms/${x.moves}mv${x.cancelled ? '/CANCELLED' : ''}`) }));
      if (padTouches) log('   on the pad:', padTouches.join(' ; '));
    };
    // What a page on this computer would scroll, in the phone's pixels: the CSS pixels times k.
    const expect = (steps) => { const [x, y] = total(steps); return { css: [Math.round(x), Math.round(y)], expectPhonePx: [Math.round(x * g.k), Math.round(y * g.k)] }; };
    const one = notch(1, 0);
    if (want('scroll')) {
    await trial('one notch down (DevTools)', async () => { await play(centre, one); return expect(one); });
    await trial('one notch up (DevTools)', async () => { await play(centre, notch(1, 0, -1)); return expect(notch(1, 0, -1)); });
    const three = notch(3, 120);
    await trial('three notches, 120 ms apart', async () => ({ ...expect(three), ms: await playInPage(centre, three) }));
    await trial('three notches back up', async () => ({ ...expect(notch(3, 120, -1)), ms: await playInPage(centre, notch(3, 120, -1)) }));
    const spin = notch(15, 18);
    await trial('fast spin: 15 notches, 18 ms apart', async () => ({ ...expect(spin), ms: await playInPage(centre, spin) }), 2500);
    await trial('fast spin back up', async () => ({ ...expect(notch(15, 18, -1)), ms: await playInPage(centre, notch(15, 18, -1)) }), 2500);
    const pad1 = touchpad(1);
    await trial('touchpad swipe down (60 events a second)', async () => ({ ...expect(pad1), events: pad1.length, ms: await playInPage(centre, pad1) }), 2000);
    await trial('touchpad swipe up', async () => ({ ...expect(touchpad(-1)), ms: await playInPage(centre, touchpad(-1)) }), 2000);
    const side = touchpad(1, 'x');
    await trial('touchpad swipe sideways', async () => ({ ...expect(side), ms: await playInPage(centre, side) }), 2000);
    await trial('touchpad swipe sideways back', async () => ({ ...expect(touchpad(-1, 'x')), ms: await playInPage(centre, touchpad(-1, 'x')) }), 2000);
    await trial('shift + 3 notches, 150 ms apart (DevTools)', async () => { await play(centre, notch(3, 150), true); return { ...expect(notch(3, 150).map(([t, , d]) => [t, d, 0])), shift: true }; });
    await trial('shift + 3 notches back', async () => { await play(centre, notch(3, 150, -1), true); return { ...expect(notch(3, 150, -1).map(([t, , d]) => [t, d, 0])), shift: true }; });
    // A long way: three touchpad swipes in a row, more than a screen.
    const long = [...touchpad(1), ...touchpad(1).map((s, i) => (i ? s : [120, s[1], s[2]])), ...touchpad(1).map((s, i) => (i ? s : [120, s[1], s[2]]))];
    await trial('three touchpad swipes in a row', async () => ({ ...expect(long), ms: await playInPage(centre, long) }), 2500);
    await trial('the same back up', async () => { const back = long.map(([t, x, y]) => [t, -x, -y]); return { ...expect(back), ms: await playInPage(centre, back) }; }, 2500);
    }
    // Clicks: alone, and right after a wheel input (the wheel's finger may still be down).
    if (want('clicks')) {
    await trial('click alone (100 ms)', async () => { await click(centre, 100); return {}; });
    for (const gap of [30, 80, 200]) {
      await trial(`notch, then a click ${gap} ms later`, async () => { await play(centre, one); await sleep(gap); await click(centre, 100); return { gap }; }, 2000);
    }
    await trial('touchpad swipe, a click during its momentum', async () => {
      const p = playInPage(centre, touchpad(1));
      await sleep(400);
      await click(centre, 100);
      await p;
      return {};
    }, 2000);
    await trial('ten clicks (100 ms), 300 ms apart', async () => { for (let i = 0; i < 10; i++) { await click(centre, 100); await sleep(300); } return {}; }, 1000);
    }
    // The left button's own finger, raw on the page's socket: a press, a move to the
    // same pixel (a pen's pressure changing, a second button), and the release.
    if (want('drag')) {
    const raw = (mask, x, y) => T(`P.sock.send(new Uint8Array([0x10, ${mask}, ${x >> 8}, ${x & 255}, ${y >> 8}, ${y & 255}])), true`);
    const px = Math.round(g.dw / 2), py = Math.round(g.dh / 2);
    await trial('press, a move to the same pixel, release (raw)', async () => { await raw(1, px, py); await sleep(60); await raw(1, px, py); await sleep(60); await raw(0, px, py); return {}; }, 1500);
    await trial('the next click', async () => { await click(centre, 100); return {}; }, 1500);
    await trial('press, a move of one pixel, release (raw)', async () => { await raw(1, px, py); await sleep(60); await raw(1, px, py + 1); await sleep(60); await raw(0, px, py + 1); return {}; }, 1500);
    await trial('a drag up of 300 px in 10 moves, 30 ms apart, then the release (raw)', async () => {
      await raw(1, px, py);
      for (let i = 1; i <= 10; i++) { await sleep(30); await raw(1, px, py - 30 * i); }
      await sleep(30);
      await raw(0, px, py - 300);
      return {};
    }, 1500);
    await trial('the same drag, held still 300 ms at its end with a move to the same pixel, then the release (raw)', async () => {
      await raw(1, px, py - 300);
      for (let i = 1; i <= 10; i++) { await sleep(30); await raw(1, px, py - 300 + 30 * i); }
      await sleep(150);
      await raw(1, px, py);
      await sleep(150);
      await raw(0, px, py);
      return {};
    }, 1500);
    }
    await say({ t: 'testpad', on: false });
    await sleep(800);
  }

  // ── in a real app ──
  if (withApp && want('app')) {
    log('In Settings:');
    if (safe()) {
      sh('am start -W -a android.settings.SETTINGS >/dev/null 2>&1; true');
      await sleep(1800);
      g = await geometry();
      const at = onPage(g, g.dw / 2, g.dh * 0.55);
      const shots = opt('--shots', null);
      let shotN = 0;
      const png = (name) => { if (shots) writeFileSync(join(shots, `${String(++shotN).padStart(2, '0')}-${name}.png`), execFileSync(ADB, ['-s', serial, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 })); };
      const app = async (name, steps, how, settle = 1800, shiftKey = false) => {
        if (!safe('settings')) { results[`app: ${name}`] = { skipped: 'not in Settings' }; return; }
        const a = screen();
        png(`${name}-before`.replace(/[^a-z0-9-]+/gi, '-'));
        const t = performance.now();
        if (how === 'page') await playInPage(at, steps, shiftKey); else await play(at, steps, shiftKey);
        const sentMs = Math.round(performance.now() - t);
        await sleep(settle);
        const b = screen();
        png(`${name}-after`.replace(/[^a-z0-9-]+/gi, '-'));
        const s = shift(a, b);
        const css = total(steps);
        results[`app: ${name}`] = { shiftPx: s.d, err: Math.round(s.err * 10) / 10, css, expectPhonePx: Math.round(css[1] * g.k), sentMs, how };
        log(`Settings, ${name}: the content moved ${s.d} px (match error ${s.err.toFixed(1)}); a page here would move ${Math.round(css[1] * g.k)} phone px`);
      };
      await app('one notch down', notch(1, 0), 'devtools');
      await app('one notch up', notch(1, 0, -1), 'devtools');
      await app('three notches down, 120 ms apart', notch(3, 120), 'page');
      await app('three notches up', notch(3, 120, -1), 'page');
      await app('quick notches down, 3 of them 18 ms apart', notch(3, 18), 'page', 2500);
      await app('quick notches up', notch(3, 18, -1), 'page', 2500);
      await app('touchpad swipe down', touchpad(1), 'page', 2500);
      await app('touchpad swipe up', touchpad(-1), 'page', 2500);
      if (safe('settings')) sh('input keyevent KEYCODE_HOME');
    }
  }
} catch (e) {
  log('FAILED:', e && e.stack ? e.stack : e);
  results.error = String(e);
} finally {
  try { if (page) await say({ t: 'testpad', on: false }); } catch { /* gone */ }
  // The page's own goodbye, so the phone ends this session at once.
  try { if (page) { await sleep(300); await say({ t: 'bye' }); log(`said goodbye at ${new Date().toISOString()}`); await sleep(500); } } catch { /* gone */ }
  if (token) log('this test browser signed out:', await forget(token));
  await browser.close();
  rmSync(profile, { recursive: true, force: true });
  if (logcat) logcat.kill();
  const putBack = padMarkBefore.endsWith('<mark>')
    ? `echo "${padMarkBefore.replace(/\n?<mark>$/, '')}" > files/phone-remote/testpad`
    : 'rm -f files/phone-remote/testpad';
  runas(`echo ${untilBefore} > files/browser/pairing-until; ${putBack}`);
  log(`after: sign-in window ${runas('cat files/browser/pairing-until')} (was ${untilBefore}); test pad mark ${runas('[ -e files/phone-remote/testpad ] && echo left || echo gone')}; focus ${focus()}`);
  const out = opt('--json', null);
  if (out) writeFileSync(out, JSON.stringify(results, null, 2));
  process.exit(results.error ? 1 : 0);
}
