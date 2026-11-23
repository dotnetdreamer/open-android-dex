// Screen off (doc/phone-remote.md, "The phone's screen off") on a real phone,
// through the door's phone.html in a visible Edge: the phone's own screen dark
// while the phone runs on for the page, as scrcpy's screen off does.
//
//   node screen-off.mjs <edge.exe> <adb serial> <door address> [--port 9611]
//       [--measure] [--lock-key] [--power-key] [--keep-helper] [--self]
//
// --self: the phone is set up to start its helper itself (paired with its own
// Wireless debugging: PhoneHelperStart, doc/phone-remote.md "Without a
// computer"). Nothing is run from here then: the page's arrival starts the
// helper (as the shell, `--register`, no key on its command line); and a
// helper gone, Screen off pressed starts it again and the screen goes off once
// it is up. Run with the shared adb server's own adb (ADB=...), never another
// version.
//
// The parts, in order:
// - no helper: the button says so, and its note gives the phone's own start
//   command (the adb line for this install's APK);
// - that command run here, as a person would paste it: the page finds the
//   helper by itself, and the note goes;
// - the button: the panel dark (SurfaceFlinger's powerMode 0) with the phone
//   still awake for Android, the picture still coming and the computer's
//   taps still landing (the test pad, PhoneControlService#testPad); the
//   button again: lit (powerMode 2);
// - every end lights it: the page closed; the app's process killed (the
//   helper's socket closes with it); and the choice kept by this browser
//   darkens the phone again when the page comes back;
// - --measure: the battery's current with the panel lit and dark, the page
//   watching the same still screen, interleaved, and again with a fixed CPU
//   load that holds a USB port's input at its limit (with the battery full
//   on a cable, the cable covers the phone's draw and the battery's current
//   shows nothing; see the doc for what the numbers can and cannot say);
// - --lock-key: the computer's own lock key while dark: the phone sleeps and
//   the helper does not wake it; then {"t":"key","key":"wake"} wakes it
//   (no tap: the lock screen is up);
// - --power-key: a press of the phone's power button while dark (injected
//   with adb's input, KEYCODE_POWER): the helper wakes the phone, the panel is
//   lit, and the page's choice is dropped. Both leave the phone at its lock
//   screen, which this test never dismisses.
//
// Needs a debuggable build (run-as reads the door's code and opens its
// sign-in window), Remote desktop on with unattended access and control.
// At the end: the test browser signs itself out (its own token), the sign-in
// window and the test pad's mark are put back, and the helper is told to go
// (quit) unless --keep-helper.
import { execFileSync, spawn } from 'node:child_process';
import https from 'node:https';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const [edgeExe, serial, door] = argv;
const opt = (name, dflt) => (argv.includes(name) ? argv[argv.indexOf(name) + 1] : dflt);
const has = (name) => argv.includes(name);
const PORT = Number(opt('--port', '9611'));
const ADB = process.env.ADB || join(process.env.LOCALAPPDATA || '', 'Android/Sdk/platform-tools/adb.exe');
const PKG = process.env.PKG || 'com.ccrstech.linuxondroid';
const sh = (cmd) => execFileSync(ADB, ['-s', serial, 'shell', cmd], { encoding: 'utf8' }).replace(/\r/g, '').trim();
const runas = (cmd) => sh(`run-as ${PKG} sh -c '${cmd}'`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
const log = (...a) => console.log(`${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s`, ...a);
let failed = 0, passed = 0;
const check = (what, ok, detail = '') => {
  if (ok) passed++; else failed++;
  log(`${ok ? 'ok  ' : 'FAIL'} ${what}${detail ? ` (${detail})` : ''}`);
};
async function until(fn, ms = 8000, every = 150) {
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
/**
 * SurfaceFlinger's power mode for the built-in panel: 2 lit, 0 dark. The line
 * after the primary DisplayDevice: with screen sharing on, the sharing's own
 * virtual display is listed too, always on (the first powerMode in the dump
 * was that one, which read 2 while the panel was dark). Newer Android names
 * the modes (On, Off).
 */
const powerMode = () => {
  const num = (v) => (/^(2|on)$/i.test(v) ? 2 : /^(0|off)$/i.test(v) ? 0 : Number(v));
  // Android 17: `--displays` lists each display as a block; the panel's says connectionType=Internal.
  const blocks = sh('dumpsys SurfaceFlinger --displays 2>/dev/null; true').split(/\n(?=Display [0-9])/);
  const internal = blocks.find((b) => b.includes('connectionType=Internal'));
  const m17 = internal && internal.match(/powerMode=(\w+)/);
  if (m17) return num(m17[1]);
  const lines = sh('dumpsys SurfaceFlinger | grep -A1 -E "DisplayDevice\\{.*(primary|internal)"; true').split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = (lines[i + 1] || '').match(/powerMode=(\w+)/);
    if (/DisplayDevice\{/.test(lines[i]) && m) return num(m[1]);
  }
  return NaN;
};
/** Real input only (the touchscreen, the power key): what getevent sees is never injected. Watched for the whole test. */
let human = [];
let watcher = null;
function watchHuman() {
  const devs = sh('getevent -pl 2>/dev/null | grep -E "add device|name:"').split('\n');
  const paths = [];
  for (let i = 0; i < devs.length; i++) {
    const dev = (devs[i].match(/add device \d+: (\S+)/) || [])[1];
    const name = (devs[i + 1] || '').match(/name:\s+"(.*)"/)?.[1] || '';
    if (dev && /touch|pon|gpio[-_]keys|pwrkey|resin|power/i.test(name) && !/uinput|fpc|goodix|proximity/i.test(name)) paths.push(dev);
  }
  watcher = spawn(ADB, ['-s', serial, 'shell', `getevent -t ${paths.join(' ')}`], { stdio: ['ignore', 'pipe', 'ignore'] });
  watcher.stdout.on('data', (d) => { for (const l of d.toString().split('\n')) if (l.trim()) human.push(l.trim()); });
  return paths;
}
/** A person touched the phone or pressed a key since the test began: stop, and say so. */
function personAtThePhone() {
  if (!human.length) return false;
  log(`a person is using the phone (real input: ${human.slice(0, 2).join(' | ')}): stopping here`);
  return true;
}
const wakefulness = () => (sh('dumpsys power | grep -m1 mWakefulness=').match(/mWakefulness=(\w+)/) || [])[1];
const keyguard = () => /isKeyguardShowing=true|mShowingLockscreen=true/.test(sh('dumpsys window | grep -E "isKeyguardShowing|mShowingLockscreen" | head -2'));
const helperPids = () => sh('ps -A -o PID,ARGS | grep PhoneHelper | grep -v grep; true').split('\n').filter(Boolean);
/** The helper's own word on itself (its socket, from the shell): the panel, and what it has put in. */
const helperState = () => sh(`CLASSPATH=$(pm path ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --state ${PKG} 2>&1; true`).split('\n').slice(-1)[0];
const countOf = (s, what) => Number((String(s).match(new RegExp(`${what} (\\d+)`)) || [])[1]);
const appPid = () => sh(`pidof ${PKG}; true`).split(/\s+/).filter(Boolean);
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

/**
 * The battery's current, sampled on the phone for `secs`, in mA, as the phone
 * reports it. Android says positive is into the battery; phones differ (on the
 * Redmi Note 7 a CPU load made it more positive: there positive is out).
 */
function current(secs) {
  const n = Math.max(1, Math.round(secs / 0.25));
  const out = sh(`i=0; while [ $i -lt ${n} ]; do service call batteryproperties 1 i32 2; i=$((i+1)); sleep 0.2; done`);
  const vals = [];
  for (const m of out.matchAll(/0x00000000: (\w{8}) (\w{8}) (\w{8}) (\w{8})[^\n]*\n?\s*0x00000010: (\w{8})/g)) {
    if (m[2] !== '00000000' || m[3] !== '00000001') continue;
    let v = BigInt(`0x${m[5]}${m[4]}`);
    if (v >= 1n << 63n) v -= 1n << 64n;
    vals.push(Number(v) / 1000);
  }
  vals.sort((a, b) => a - b);
  const mean = vals.reduce((a, b) => a + b, 0) / (vals.length || 1);
  return { n: vals.length, mean, median: vals[vals.length >> 1], min: vals[0], max: vals[vals.length - 1] };
}
const battery = () => {
  const s = sh('dumpsys battery');
  // A field's own line ("voltage:", not "Max charging voltage:").
  const g = (k) => Number((s.match(new RegExp(`(?:^|\\n)\\s*${k}: (-?\\d+)`)) || [])[1]);
  return { level: g('level'), voltage: g('voltage'), temp: g('temperature') / 10, usb: /USB powered: true/.test(s), status: g('status') };
};
const backlight = () => sh('dumpsys display | grep -m3 -E "mScreenBrightness|mPendingScreenBrightness|mAppliedAutoBrightness|mActualBacklight"; true').replace(/\s+/g, ' ');

// ── the page ──
const PROBE = `(() => {
  if (window.__t) return;
  const P = window.__t = { video: 0, texts: [], sock: null };
  const WS = window.WebSocket;
  window.WebSocket = class extends WS {
    constructor(...a) {
      super(...a);
      if (!String(a[0]).includes('/phone')) return;
      P.sock = this;
      const said = [];
      P.sent = said;
      const send = this.send.bind(this);
      this.send = (m) => { if (typeof m === 'string') said.push(m); return send(m); };
      this.addEventListener('message', (ev) => {
        if (typeof ev.data === 'string') {
          try { const m = JSON.parse(ev.data); if (['phone', 'state', 'testpad', 'padtouch', 'stats', 'video'].includes(m.t)) P.texts.push(m); } catch { /* not ours */ }
          return;
        }
        if (new Uint8Array(ev.data)[0] === 1) P.video++;
      });
    }
  };
})();`;

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
    // Leaving asks first ("Leave site?"): answered yes, as a person closing the tab does.
    if (m.method === 'Page.javascriptDialogOpening') {
      ws.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
    }
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
const lastSaid = (t) => T(`P.texts.filter((m) => m.t === '${t}').slice(-1)[0] || null`);
const lodPhone = () => page.evaluate('window.lodPhone && window.lodPhone()');
const statusText = () => page.evaluate(`document.getElementById('status').textContent`);
/** A real click on an element of the page (the mouse over DevTools, as a person's). */
async function click(id) {
  const at = await page.evaluate(`(() => { const r = document.getElementById('${id}').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
  for (const type of ['mousePressed', 'mouseReleased']) {
    await page.call('Input.dispatchMouseEvent', { type, x: at[0], y: at[1], button: 'left', clickCount: 1 });
  }
}

// ── set up ──
const last = sh(`logcat -d -v time | grep -E "OpenDeX.*\\[phone\\].*(opened|closed) the screen" | tail -1`);
// A session line from a process that is gone (killed: a permission revoked, an update) is no open session.
const lastPid = (last.match(/\(\s*(\d+)\)/) || [])[1];
if (/opened the screen/.test(last) && sh(`pidof ${PKG}; true`).split(/\s+/).includes(lastPid)) { console.log('someone has the phone open now:', last); process.exit(2); }
const untilBefore = runas('cat files/browser/pairing-until 2>/dev/null; true');
const padMarkBefore = runas('[ -e files/phone-remote/testpad ] && echo yes || echo no') === 'yes';
const helpersBefore = helperPids();
log(`before: ${wakefulness()}, keyguard ${keyguard()}, powerMode ${powerMode()}, helper ${helpersBefore.length ? helpersBefore.join(' | ') : 'none'}, app ${appPid().join(',')}`);
if (keyguard()) { console.log('the phone shows its lock screen: this test never dismisses one; unlock it first'); process.exit(2); }
log(`watching real input (a person at the phone stops the test) on ${watchHuman().join(' ')}`);
const code = runas('cat files/browser/code').split(' ')[0];
const phoneNow = Number(sh('date +%s%3N'));
if (!(phoneNow > 1.6e12)) throw new Error(`the phone's clock reads ${phoneNow}`);
runas(`echo ${phoneNow + 900000} > files/browser/pairing-until; mkdir -p files/phone-remote; touch files/phone-remote/testpad`);

const profile = mkdtempSync(join(tmpdir(), 'screen-off-'));
const browser = await launchEdge(edgeExe, { port: PORT, profile, args: ['--ignore-certificate-errors', '--window-size=600,1080', '--window-position=60,10', '--disable-sync', '--disable-extensions'] });

async function openPage(first) {
  page = await cdp(browser.page.webSocketDebuggerUrl);
  await page.call('Runtime.enable');
  await page.call('Page.enable');
  await page.call('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });
  await page.call('Page.navigate', { url: `${door}/phone.html` });
  if (first) {
    await until(() => page.evaluate(`!document.getElementById('pair').hidden`), 15000);
    await page.evaluate(`document.getElementById('code').value = ${JSON.stringify(code)}; document.getElementById('pair').requestSubmit(); 1`);
  }
  return !!(await until(async () => / × /.test(await statusText()), 25000));
}
/** The phone's picture size and the test pad's square, from the page's last messages. */
async function pad() {
  return T(`(() => { const v = P.texts.filter((m) => m.t === 'testpad').slice(-1)[0]; return v || null; })()`);
}
/** A tap on the test pad's square through the page's own input; the pad's word for it, or null. */
async function tapPad() {
  if (personAtThePhone()) throw new Error('a person is using the phone');
  const sq = await pad();
  const vid = await T(`P.texts.filter((m) => m.t === 'video').slice(-1)[0] || null`);
  if (!sq || !vid || !vid.dw) return null;
  const before = await T(`P.texts.filter((m) => m.t === 'padtouch').length`);
  // The phone's pixels (dw wide) to the canvas's (cw wide), then to where the canvas is drawn.
  const d = await page.evaluate(`(() => { const c = document.getElementById('screen'); const r = c.getBoundingClientRect();
    const s = Math.min(r.width / c.width, r.height / c.height);
    return { cw: c.width, s, ox: r.left + (r.width - c.width * s) / 2, oy: r.top + (r.height - c.height * s) / 2 }; })()`);
  const k = d.cw / vid.dw;
  const x = d.ox + (sq.x + sq.size / 2) * k * d.s, y = d.oy + (sq.y + sq.size / 2) * k * d.s;
  await page.call('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 });
  await sleep(60);
  await page.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: 1 });
  return until(async () => ((await T(`P.texts.filter((m) => m.t === 'padtouch').length`)) > before
    ? T(`P.texts.filter((m) => m.t === 'padtouch').slice(-1)[0]`) : null), 4000);
}

let token = null;
let helperStarted = false;
try {
  if (has('--self') && helpersBefore.length) {
    // The start must be the phone's own: a running helper goes first.
    sh(`CLASSPATH=$(pm path ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --quit ${PKG}; true`);
    await until(() => (helperPids().length === 0 ? 1 : null), 5000);
  }
  const up = await openPage(true);
  check('the page signs in and the phone\'s picture comes up', up);
  if (!up) throw new Error('no picture: the rest would only fail');
  token = await page.evaluate(`localStorage.getItem('lod.token')`);
  check('…at full speed (screen sharing), as the battery numbers need', /^Full speed/.test(await statusText()), await statusText());

  // ── no helper ──
  log('— without the helper —');
  if (has('--self')) {
    // ── the phone starts its own helper ──
    if (helpersBefore.length) log('a helper was running before: it was told to go first, so the start is the phone\'s own');
    const started = await until(async () => ((await lodPhone())?.panel === 'on' ? lodPhone() : null), 25000, 250);
    const ms = Date.now() - t0;
    check('the page\'s arrival starts the phone\'s helper, no command run here', !!started, JSON.stringify(await lodPhone()));
    const pids = helperPids();
    check('…one helper, as the shell, started with --register (no key on its command line)',
      pids.length === 1 && / --register/.test(pids[0]) && !/[0-9a-f]{64}/.test(pids[0]), pids.join(' | '));
    const said = sh('logcat -d -v time -s OpenDeX:I | grep -E "helper (start over|handed over)|started without a computer|helper start:" | tail -4');
    check('…through Wireless debugging (the app\'s log)', /started without a computer/.test(said), said.split('\n').slice(-2).join(' / '));
    log(`helper up ${ms} ms after the test began`);
    helperStarted = true;
    // Gone, then asked for: Screen off starts it again, and the screen goes off once it is up.
    sh(`CLASSPATH=$(pm path ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --quit ${PKG}; true`);
    const gone = await until(async () => ((await lodPhone())?.panel === 'none' ? lodPhone() : null), 8000);
    check('the helper told to go: the page says none', !!gone, JSON.stringify(gone));
    // The worker's own retry is the next computer's: this page says what the phone needs.
    const how = gone && gone.panelHow;
    check('…and that the phone can start it again ("waiting" or "starting")', how === 'waiting' || how === 'starting', String(how));
    const asked = Date.now();
    await click('panel-btn');
    const dark = await until(() => (powerMode() === 0 ? 1 : null), 25000, 250);
    check('Screen off pressed with no helper: the phone starts one and its screen goes off', !!dark, `${Date.now() - asked} ms, powerMode ${powerMode()}`);
    check('…Android still has the phone awake', wakefulness() === 'Awake', wakefulness());
    await click('panel-btn');
    const lit = await until(() => (powerMode() === 2 ? 1 : null), 5000, 150);
    check('…and lit again from the page', !!lit, String(powerMode()));
    await page.evaluate(`localStorage.removeItem('lod.phonePanel')`);
  } else if (helpersBefore.length) {
    log('a helper is already running: the "no helper" part is skipped');
  } else {
    const p0 = await until(async () => ((await lodPhone())?.panel === 'none' ? lodPhone() : null), 5000);
    check('the phone says its screen cannot go off yet ("none")', !!p0, JSON.stringify(p0));
    const btnClass = await page.evaluate(`document.getElementById('panel-btn').className`);
    check('…the button says so (dimmed)', /unavailable/.test(btnClass), btnClass);
    await click('panel-btn');
    await sleep(300);
    const note = await page.evaluate(`({ shown: !document.getElementById('panel-note').hidden, cmd: document.getElementById('panel-cmd').textContent, words: document.getElementById('panel-words').textContent })`);
    check('…a click shows how to start the helper', note.shown && /adb/.test(note.words), note.words.slice(0, 80));
    const apk = sh(`pm path ${PKG} | grep base.apk | head -1`).replace(/^package:/, '');
    check('…with the command for this install\'s own APK', note.cmd.startsWith('adb shell "CLASSPATH=' + apk + ' ') && note.cmd.includes('PhoneHelper ' + PKG), note.cmd);
    check('…and the button sent nothing to the phone', !(await T(`(P.sent || []).some((m) => m.includes('"t":"panel"'))`)));
    // Without the helper the computer's taps are the accessibility service's, as before.
    await click('panel-close');
    await say({ t: 'testpad', on: true });
    await until(() => pad(), 4000);
    await sleep(600);
    const t0a11y = await tapPad();
    check('without the helper a tap still lands (the accessibility service\'s gesture)', !!t0a11y && !t0a11y.cancelled, JSON.stringify(t0a11y));
    await say({ t: 'testpad', on: false });
    await sleep(500);
    await click('panel-btn');
    await sleep(300);

    // The command as a person pastes it: "adb shell" then one quoted line; here for this phone (-s).
    const m = note.cmd.match(/^adb shell "(.*)"$/);
    check('the command is one adb shell line', !!m);
    if (m) {
      execFileSync(ADB, ['-s', serial, 'shell', m[1]], { encoding: 'utf8', timeout: 15000 });
      helperStarted = true;
      log('ran the page\'s command here');
      await sleep(1000);
      const pids = helperPids();
      check('…the helper runs, as the shell, on its own (its parent is init)', pids.length === 1, pids.join(' | '));
      const found = await until(async () => ((await lodPhone())?.panel === 'on' ? lodPhone() : null), 8000);
      check('…the page finds the helper by itself (the helper looks for the app every second)', !!found, JSON.stringify(await lodPhone()));
      check('…and the note goes', !(await lodPhone())?.panelNote);
    }
  }

  // ── off and on ──
  log('— off and on —');
  const hsBar = helperState();
  const mode0 = powerMode();
  check('the panel is lit to begin with (powerMode 2)', mode0 === 2, String(mode0));
  let tOff = Date.now();
  await click('panel-btn');
  const dark = await until(async () => ((await lodPhone())?.panel === 'off' ? true : null), 5000);
  const darkMs = Date.now() - tOff;
  check('the button turns the phone\'s screen off', !!dark, `${darkMs} ms to the page`);
  check('…SurfaceFlinger has the panel off (powerMode 0)', powerMode() === 0, String(powerMode()));
  check('…and Android still has the phone awake', wakefulness() === 'Awake', wakefulness());
  check('…the page says so', /screen is off/.test(await statusText()), await statusText());
  check('…and keeps the choice (this browser)', (await page.evaluate(`localStorage.getItem('lod.phonePanel')`)) === 'off');
  // The picture and the taps, while dark: the test pad's square flips with each tap.
  await say({ t: 'testpad', on: true });
  await until(() => pad(), 4000);
  await sleep(600);
  const hs0 = helperState();
  check('the bar\'s button is no press on the phone (the helper put in nothing for it)', countOf(hs0, 'touches') === countOf(hsBar, 'touches'), `${hsBar} → ${hs0}`);
  const v0 = await T('P.video');
  let touches = 0;
  for (let i = 0; i < 4; i++) {
    const t = await tapPad();
    if (t && !t.cancelled) touches++;
    await sleep(400);
  }
  await sleep(500);
  const v1 = await T('P.video');
  const hs1 = helperState();
  check('while dark the computer\'s taps land (the test pad felt them)', touches === 4, `${touches} of 4`);
  check('…put in by the helper (its count of presses), not the accessibility service', countOf(hs1, 'touches') - countOf(hs0, 'touches') === 4, `${hs0} → ${hs1}`);
  check('…and the picture keeps coming (frames after each tap)', v1 - v0 >= 4, `${v1 - v0} video messages`);
  check('…with the panel still off', powerMode() === 0);
  await say({ t: 'testpad', on: false });
  await sleep(500);
  await click('panel-btn');
  const lit = await until(async () => ((await lodPhone())?.panel === 'on' ? true : null), 5000);
  check('the button again turns the phone\'s screen back on', !!lit && powerMode() === 2, `powerMode ${powerMode()}`);
  check('…and forgets the choice', (await page.evaluate(`localStorage.getItem('lod.phonePanel')`)) === null);

  // ── every end lights it ──
  log('— the page closed —');
  await click('panel-btn');
  await until(async () => ((await lodPhone())?.panel === 'off' ? true : null), 5000);
  check('dark again', powerMode() === 0);
  const tClose = Date.now();
  await page.call('Page.navigate', { url: 'about:blank' });
  const litAfterClose = await until(() => (powerMode() === 2 ? true : null), 5000, 100);
  check('closing the page lights the phone\'s screen', !!litAfterClose, `${Date.now() - tClose} ms (by adb)`);

  log('— the choice kept —');
  const up2 = await openPage(false);
  check('the page again (this browser kept the choice "off")', up2);
  const darkAgain = await until(() => (powerMode() === 0 ? true : null), 8000, 200);
  check('…darkens the phone by itself', !!darkAgain, `powerMode ${powerMode()}`);
  check('…and its hello said so', await T(`(P.sent || []).some((m) => m.includes('"t":"hello"') && m.includes('"panel":false'))`));

  log('— the app\'s process gone while dark —');
  const pids = appPid();
  const main = sh(`ps -A -o PID,NAME | grep -E " ${PKG}$" | head -1`).trim().split(/\s+/)[0];
  if (personAtThePhone()) throw new Error('a person is using the phone');
  if (main) {
    const tKill = Date.now();
    // A crash of the app's main process (run-as may not kill an app's process on Android 10: SELinux).
    sh(`am crash ${main}`);
    const litAfterKill = await until(() => (powerMode() === 2 ? true : null), 5000, 100);
    check('the app\'s process crashed (am crash): the helper lights the phone\'s screen', !!litAfterKill, `${Date.now() - tKill} ms; app was ${pids.join(',')}, now ${appPid().join(',')}`);
    const back = await until(async () => / × /.test(await statusText()) && (await lodPhone())?.panel !== 'none', 60000, 500);
    check('…the page comes back to the restarted app', !!back, await statusText());
    const darkAfterBack = await until(() => (powerMode() === 0 ? true : null), 10000, 200);
    check('…and the kept choice darkens the phone again', !!darkAfterBack, `powerMode ${powerMode()}`);
  } else {
    check('the app\'s main process found', false);
  }

  // ── the battery ──
  if (has('--measure')) {
    if (personAtThePhone()) throw new Error('a person is using the phone');
    log('— the battery —');
    const b0 = battery();
    log(`battery ${b0.level}% ${b0.voltage} mV ${b0.temp} °C, USB ${b0.usb}, status ${b0.status}; ${backlight()}`);
    const runs = [];
    const measure = async (label, secs = 10) => {
      await sleep(2000);
      const c = current(secs);
      const row = { label, ...c, mode: powerMode() };
      runs.push(row);
      log(`${label.padEnd(26)} n=${String(c.n).padStart(3)} mean ${c.mean.toFixed(1).padStart(7)} mA  median ${c.median.toFixed(1).padStart(7)}  [${c.min.toFixed(1)} .. ${c.max.toFixed(1)}]  powerMode ${row.mode}`);
    };
    const setDark = async (d) => {
      if (((await lodPhone())?.panel === 'off') === d) return;
      await click('panel-btn');
      await until(async () => ((await lodPhone())?.panel === (d ? 'off' : 'on') ? true : null), 5000);
    };
    for (let i = 0; i < 3; i++) {
      await setDark(false);
      await measure('lit, no load');
      await setDark(true);
      await measure('dark, no load');
    }
    // A fixed load, so the USB port's input stays at its limit and the battery gives the rest.
    sh('for i in 1 2 3 4; do setsid nohup sh -c "while :; do :; done" >/dev/null 2>&1 </dev/null & echo $! >> /data/local/tmp/oadx-load.pids; done; true');
    log(`load on: ${sh('cat /data/local/tmp/oadx-load.pids').split('\n').length} busy shells`);
    try {
      await sleep(3000);
      for (let i = 0; i < 3; i++) {
        await setDark(false);
        await measure('lit, load');
        await setDark(true);
        await measure('dark, load');
      }
      // The page out of sight: the picture stops ({"t":"picture","on":false}) and the
      // Wi-Fi's keep-awake byte goes from every 50 ms to every 2 s.
      await say({ t: 'picture', on: false });
      await measure('dark, load, no picture');
      await say({ t: 'picture', on: true });
    } finally {
      sh('kill $(cat /data/local/tmp/oadx-load.pids) 2>/dev/null; rm -f /data/local/tmp/oadx-load.pids; true');
      log(`load off; left: ${sh('ps -A -o ARGS | grep -c "while :; do :; done" ; true')} busy shells (the grep itself counts one)`);
    }
    const avg = (l) => { const r = runs.filter((x) => x.label === l); return r.reduce((a, b) => a + b.mean, 0) / (r.length || 1); };
    const b1 = battery();
    log(`battery after ${b1.level}% ${b1.voltage} mV ${b1.temp} °C; ${backlight()}`);
    log(`means: lit ${avg('lit, no load').toFixed(1)} / dark ${avg('dark, no load').toFixed(1)} mA without load; lit ${avg('lit, load').toFixed(1)} / dark ${avg('dark, load').toFixed(1)} mA with load`);
    // Each dark run against the lit runs either side of it: a drift (the battery filling, the CPU
    // slowing as the phone warms) moves both, so a run is only set against its neighbours.
    for (const what of ['no load', 'load']) {
      const seq = runs.filter((x) => x.label === `lit, ${what}` || x.label === `dark, ${what}`);
      const d = [];
      for (let i = 1; i < seq.length; i++) {
        const lit = seq[i].label.startsWith('lit') ? seq[i] : seq[i - 1];
        const darkRun = seq[i].label.startsWith('lit') ? seq[i - 1] : seq[i];
        d.push(lit.mean - darkRun.mean);
      }
      log(`lit against dark, neighbour by neighbour (${what}): ${d.map((x) => x.toFixed(0)).join(', ')} mA (a lit run's reading minus a dark one's, in the phone's own sign)`);
    }
    log(`battery voltage ~${((b0.voltage + b1.voltage) / 2 / 1000).toFixed(2)} V`);
    await setDark(true);
  }

  // ── the computer's lock key, then the phone's power button ──
  if (has('--lock-key')) {
    if (personAtThePhone()) throw new Error('a person is using the phone');
    log('— the computer\'s lock key while dark —');
    if ((await lodPhone())?.panel !== 'off') {
      await click('panel-btn');
      await until(async () => ((await lodPhone())?.panel === 'off' ? true : null), 5000);
    }
    await say({ t: 'key', key: 'lock' });
    await sleep(2500);
    check('the phone sleeps as the computer asked, and is not woken again', wakefulness() !== 'Awake', wakefulness());
    check('…and the page\'s choice goes (the phone is Android\'s)', (await until(async () => ((await lodPhone())?.panelOff === false ? true : null), 4000)) === true, JSON.stringify(await lodPhone()));
    await say({ t: 'key', key: 'wake' });
    const woke = await until(() => (wakefulness() === 'Awake' ? true : null), 5000, 200);
    check('…the computer\'s wake key wakes it (no tap: its lock screen is up)', !!woke, `keyguard ${keyguard()}`);
  }
  if (has('--power-key')) {
    if (personAtThePhone()) throw new Error('a person is using the phone');
    log('— the phone\'s power button while dark —');
    await sleep(1500);
    await click('panel-btn');
    const d = await until(async () => ((await lodPhone())?.panel === 'off' ? true : null), 5000);
    check('dark again from the page (the lock screen may be up: no tap on the phone)', !!d && powerMode() === 0, `powerMode ${powerMode()}`);
    const tKey = Date.now();
    sh('input keyevent 26');
    const back = await until(() => (wakefulness() === 'Awake' && powerMode() === 2 ? true : null), 5000, 100);
    check('one press of the power button (KEYCODE_POWER from adb) brings the screen back: the helper woke the phone', !!back, `${Date.now() - tKey} ms, ${wakefulness()}, powerMode ${powerMode()}`);
    const dropped = await until(async () => ((await lodPhone())?.panelOff === false ? true : null), 4000);
    check('…and the page\'s choice is dropped (the person at the phone took the screen)', !!dropped, JSON.stringify(await lodPhone()));
    check('…kept dropped: this browser will not darken the phone again', (await page.evaluate(`localStorage.getItem('lod.phonePanel')`)) === null);
  }
} catch (e) {
  check(`the test ran to its end (${e.message})`, false);
} finally {
  try {
    if (page) await page.call('Page.navigate', { url: 'about:blank' });
  } catch { /* gone */ }
  await sleep(800);
  if (token) log(`this browser signed out: ${await forget(token)}`);
  try { await browser.close(); } catch { /* gone */ }
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* Edge's files */ }
  runas(`echo ${untilBefore} > files/browser/pairing-until${padMarkBefore ? '' : '; rm -f files/phone-remote/testpad'}`);
  if (helperStarted && !has('--keep-helper')) {
    // The helper's own goodbye, from its own socket: the shell may talk to it.
    const r = sh(`CLASSPATH=$(pm path ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --quit ${PKG} 2>&1; true`);
    log(`helper told to go: ${r.split('\n').slice(-1)[0]}`);
  }
  try { if (watcher) watcher.kill(); } catch { /* gone */ }
  log(`real input during the test: ${human.length ? `${human.length} events (a person used the phone)` : 'none'}`);
  log(`after: ${wakefulness()}, keyguard ${keyguard()}, powerMode ${powerMode()}, helper ${helperPids().join(' | ') || 'none'}, sign-in window ${runas('cat files/browser/pairing-until')}, test pad mark ${runas('[ -e files/phone-remote/testpad ] && echo left || echo gone')}`);
  log(`${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}
