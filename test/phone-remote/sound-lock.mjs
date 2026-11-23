// Remote desktop on a real phone, through the door's phone.html in a visible
// Edge: the phone's sound (each of the four choices: what is sent, what the
// page plays, whether the phone itself is quiet), the lock screen's PIN pad
// (drawn on the page where screen sharing is black, the computer's number
// keys pressing it), a click waking a phone that sleeps, and closing the
// page (asked first; the phone's session ends at once and Remote desktop
// stays on).
//
//   node sound-lock.mjs <edge.exe> <adb serial> <door address> [--only sound|lock|close]
//
// Needs a debuggable build (run-as reads the door's code and opens its
// sign-in window for ten minutes), Remote desktop on with unattended access
// (screen sharing) and control. The phone's sound is a test tone the phone
// plays itself ({"t":"testtone"}, allowed only while the test pad's mark is
// in the app's files, as for page-latency.mjs). On the lock screen the test
// types at most two digits and deletes them: it never presses OK and never
// types a whole PIN. At the end: the test browser signs itself out (its own
// token), the sign-in window and the test pad's mark are put back, and the
// phone's media sound is checked to be as it was.
import { execFileSync } from 'node:child_process';
import https from 'node:https';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const [edgeExe, serial, door] = argv;
const only = argv.includes('--only') ? argv[argv.indexOf('--only') + 1] : null;
const shots = argv.includes('--shots') ? argv[argv.indexOf('--shots') + 1] : null;
if (shots) mkdirSync(shots, { recursive: true });
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
const music = () => {
  const s = sh('dumpsys audio | grep -A6 "^- STREAM_MUSIC:"');
  return { muted: /Muted: true/.test(s), volume: Number((s.match(/streamVolume:(\d+)/) || [])[1]) };
};
const power = () => (sh('dumpsys power | grep -E "mWakefulness=" | head -1').match(/mWakefulness=(\w+)/) || [])[1];
const keyguard = () => /isKeyguardShowing=true/.test(sh('dumpsys window | grep isKeyguardShowing | head -1'));
// The app's own lines only: the phone logs hundreds of others a second, and
// the last few hundred lines lose ours within moments.
const appLog = () => sh('logcat -d -v time -s OpenDeX:I');
const phoneLog = () => appLog().split('\n').filter((l) => /\[phone\]/.test(l) && !/keeps up|is behind|is full|frame is held/.test(l)).join('\n');
/** The door's own sign-out for one browser (its token), from here: the page that had it may be gone. */
const forget = (tok) => new Promise((res) => {
  const body = new URLSearchParams({ token: tok }).toString();
  const req = https.request(`${door}/forget`, {
    method: 'POST', rejectUnauthorized: false,
    // The door takes a form only from its own page (the same origin), as a browser sends it.
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body), Origin: door },
  }, (r) => { r.resume(); res(r.statusCode); });
  req.on('error', (e) => res(String(e)));
  req.setTimeout(8000, () => { req.destroy(); res('no answer'); });
  req.end(body);
});
const granted = () => /android.permission.RECORD_AUDIO: granted=true/.test(sh(`dumpsys package ${PKG} | grep "RECORD_AUDIO"; true`));

// ── the page ──
const PROBE = `(() => {
  if (window.__t) return;
  const P = window.__t = { audio: 0, video: 0, texts: [], sock: null };
  const WS = window.WebSocket;
  window.WebSocket = class extends WS {
    constructor(...a) {
      super(...a);
      if (!String(a[0]).includes('/phone')) return;
      P.sock = this;
      this.addEventListener('message', (ev) => {
        if (typeof ev.data === 'string') {
          try { const m = JSON.parse(ev.data); if (['audio', 'sound', 'lock', 'state'].includes(m.t)) P.texts.push(m); } catch { /* not ours */ }
          return;
        }
        const b = new Uint8Array(ev.data);
        if (b[0] === 3) P.audio++; else if (b[0] === 1) P.video++;
      });
    }
  };
})();`;

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  const events = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
    if (m.method) events.push(m);
  };
  // A call the page never answers (a dialog up, the page gone) fails after a while instead of holding the test.
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
  return { ws, call, evaluate, events };
}

let page = null;
const T = (expr) => page.evaluate(`(() => { const P = window.__t; return ${expr}; })()`);
const say = (obj) => T(`P.sock && P.sock.readyState === 1 && (P.sock.send(${JSON.stringify(JSON.stringify(obj))}), true)`);
const phoneSays = (t) => T(`P.texts.filter((m) => m.t === '${t}').slice(-1)[0] || null`);
const lodPhone = () => page.evaluate('window.lodPhone && window.lodPhone()');
/** A real click (the page's sticky activation, which lets its sound start) on the status line, not on the phone's picture. */
async function clickStatus() {
  const at = await page.evaluate(`(() => { const r = document.querySelector('.status').getBoundingClientRect(); return [r.left + r.width - 30, r.top + r.height / 2]; })()`);
  for (const type of ['mousePressed', 'mouseReleased']) {
    await page.call('Input.dispatchMouseEvent', { type, x: at[0], y: at[1], button: 'left', clickCount: 1 });
  }
}
async function choose(sound) {
  await page.evaluate(`(() => { const s = document.getElementById('sound'); s.value = '${sound}'; s.dispatchEvent(new Event('change', { bubbles: true })); return s.value; })()`);
}
async function key(k, code, vk) {
  await page.call('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk, text: k.length === 1 ? k : undefined });
  await page.call('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk });
}
async function shot(name) {
  if (!shots) return;
  const r = await page.call('Page.captureScreenshot', { format: 'png' });
  if (r.result?.data) writeFileSync(join(shots, `${name}.png`), Buffer.from(r.result.data, 'base64'));
}

// ── set up ──
const last = sh(`logcat -d -v time | grep -E "OpenDeX.*\\[phone\\].*(opened|closed) the screen" | tail -1`);
if (/opened the screen/.test(last)) { console.log('someone has the phone open now:', last); process.exit(2); }
const untilBefore = runas('cat files/browser/pairing-until 2>/dev/null');
const padMarkBefore = runas('[ -e files/phone-remote/testpad ] && echo yes || echo no') === 'yes';
const musicBefore = music();
const grantedBefore = granted();
log(`before: media ${musicBefore.muted ? 'muted' : 'not muted'} at ${musicBefore.volume}, sound permission ${grantedBefore ? 'granted' : 'not granted'}, keyguard ${keyguard()}, ${power()}`);
const code = runas('cat files/browser/code').split(' ')[0];
// The phone's own clock, in ms, for ten minutes on: computed here, since the phone's shell counts in 32 bits.
const phoneNow = Number(sh('date +%s%3N'));
if (!(phoneNow > 1.6e12)) throw new Error(`the phone's clock reads ${phoneNow}`);
runas(`echo ${phoneNow + 600000} > files/browser/pairing-until; mkdir -p files/phone-remote; touch files/phone-remote/testpad`);

const profile = mkdtempSync(join(tmpdir(), 'sound-lock-'));
const browser = await launchEdge(edgeExe, { port: 9338, profile, args: ['--ignore-certificate-errors', '--window-size=560,1080', '--window-position=40,10', '--disable-sync', '--disable-extensions'] });

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
  const up = await until(async () => / × /.test(await page.evaluate(`document.getElementById('status').textContent`)), 20000);
  return !!up;
}

let token = null;
try {
  const pictureUp = await openPage(true);
  check('the page signs in and the phone\'s picture comes up', pictureUp);
  if (!pictureUp) throw new Error('no picture: the rest would only fail');
  token = await page.evaluate(`localStorage.getItem('lod.token')`);

  // ── the sound ──
  if (!only || only === 'sound') try {
    log('— the sound —');
    await clickStatus();
    const first = await until(() => phoneSays('audio'), 4000);
    check('sound on the phone (the default): the phone says off, and the page asked for no sound', first && first.state === 'off');
    await say({ t: 'testtone', hz: 440, ms: 1200 });
    await sleep(1800);
    check('…a tone on the phone sends nothing to the page', (await T('P.audio')) === 0, `${await T('P.audio')} sound messages`);
    check('…and the phone is not made quiet', !music().muted);

    await choose('here');
    if (!grantedBefore) {
      const asked = await until(async () => (await phoneSays('audio'))?.state === 'permission', 5000);
      check('sound in this browser without the permission: the phone says it needs a yes on the phone', !!asked);
      const notif = sh(`dumpsys notification | grep -E "pkg=${PKG}.*id=97|NotificationRecord.*${PKG}.*0x61" | head -2`);
      check('…and the phone shows the question as a notification', /id=97|0x61/.test(notif), notif.slice(0, 120));
      check('…the page says so in its status line', /allow it on the phone/.test(await page.evaluate(`document.getElementById('status').textContent`)));
      // A build without sound does not ask for the permission at all, and pm refuses it.
      sh(`pm grant ${PKG} android.permission.RECORD_AUDIO 2>&1; true`);
      log('granted the permission with adb (pm grant), as a yes on the phone would');
      await choose('both');
    }
    const on = await until(async () => (await phoneSays('audio'))?.state === 'on', 8000);
    check(`sound ${grantedBefore ? 'in this browser' : 'on both'}: the phone says on`, !!on, JSON.stringify(await phoneSays('audio')));
    const fmt = await until(() => phoneSays('sound'), 5000);
    check('…and what it sends: Opus, as the picture\'s quality has it', fmt && fmt.codec === 'opus', JSON.stringify(fmt));
    await choose('both');
    await sleep(800);
    let a0 = await T('P.audio');
    let p0 = (await lodPhone()).player;
    await say({ t: 'testtone', hz: 660, ms: 1500 });
    await sleep(2200);
    let a1 = await T('P.audio');
    let p1 = (await lodPhone()).player;
    check('sound on both: the tone comes to the page', a1 - a0 >= 40, `${a1 - a0} sound messages`);
    check('…and is played there, loud', p1.loudRuns - p0.loudRuns >= 30, `${p1.loudRuns - p0.loudRuns} loud runs, page state ${p1.state}`);
    check('…the phone plays it too (not quiet)', !music().muted);

    await choose('here');
    const quiet = await until(() => music().muted, 4000, 300);
    check('sound in this browser: the phone\'s own media sound is muted', !!quiet);
    a0 = await T('P.audio');
    p0 = (await lodPhone()).player;
    await say({ t: 'testtone', hz: 880, ms: 1500 });
    await sleep(2200);
    a1 = await T('P.audio');
    p1 = (await lodPhone()).player;
    check('…and the page still hears the tone at full level (the capture is before the volume)', p1.loudRuns - p0.loudRuns >= 30, `${a1 - a0} messages, ${p1.loudRuns - p0.loudRuns} loud runs`);
    check('…the status line says what plays', /sound \d+ kb\/s/.test(await page.evaluate(`document.getElementById('status').textContent`)));

    await choose('off');
    await sleep(1200);
    a0 = await T('P.audio');
    await say({ t: 'testtone', hz: 440, ms: 1200 });
    await sleep(1800);
    a1 = await T('P.audio');
    check('sound off: nothing is sent while the phone plays', a1 - a0 <= 2, `${a1 - a0} messages (one in flight may land)`);
    check('…and the phone stays quiet', music().muted);

    await choose('phone');
    const back = await until(() => !music().muted, 4000, 300);
    check('sound on the phone: the phone\'s own sound is back', !!back);
    a0 = await T('P.audio');
    await say({ t: 'testtone', hz: 440, ms: 1000 });
    await sleep(1500);
    check('…and nothing is sent', (await T('P.audio')) - a0 <= 1);
    check('the phone\'s media volume is as it was', music().volume === musicBefore.volume, `${music().volume} vs ${musicBefore.volume}`);
  } catch (e) {
    check('the sound part ran to its end', false, String(e).slice(0, 200));
  }

  // ── the lock screen ──
  if (!only || only === 'lock') try {
    log('— the lock screen —');
    if (!keyguard()) {
      log('the phone is unlocked: the lock screen part needs it locked (KEYCODE_SLEEP would lock it, not unlock it)');
      sh('input keyevent KEYCODE_SLEEP');
      await sleep(1500);
    }
    sh('input keyevent KEYCODE_WAKEUP');
    await sleep(1500);
    check('the phone is locked and awake', keyguard() && power() === 'Awake', `${power()}`);
    sh('wm dismiss-keyguard');
    const pad = await until(async () => { const s = await lodPhone(); return s && s.lock === 'pin' && s.lockShown ? s : null; }, 8000);
    const lk = await phoneSays('lock');
    check('the PIN pad up: the phone says where its keys are', lk && lk.pad === 'pin' && (lk.keys || []).length >= 11, `${(lk?.keys || []).length} keys`);
    check('…and the page draws them where the picture is black', !!pad);
    const words = (lk?.words || []).map((w) => w.s);
    check('…with what the pad says, and its OK key', words.length >= 1 && (lk?.keys || []).some((k) => k.k === 'ok'),
      `${JSON.stringify(words)}, keys ${(lk?.keys || []).map((k) => k.k).join('')}`);
    await shot('lock-1-pad');
    await key('1', 'Digit1', 49);
    await key('2', 'Digit2', 50);
    const two = await until(async () => (await phoneSays('lock'))?.field?.n === 2, 4000);
    check('two digits typed on the computer\'s keyboard go into the phone\'s PIN field', !!two, JSON.stringify((await phoneSays('lock'))?.field));
    await shot('lock-2-two-digits');
    await key('Backspace', 'Backspace', 8);
    await key('Backspace', 'Backspace', 8);
    const none = await until(async () => (await phoneSays('lock'))?.field?.n === 0, 4000);
    check('…and Backspace takes them out again (nothing was ever submitted)', !!none);
    sh('input keyevent KEYCODE_BACK');
    const gone = await until(async () => { const s = await lodPhone(); return s && !s.lockShown; }, 5000);
    check('the PIN pad put away: the page stops drawing it', !!gone);
    await sleep(800);
    await key('3', 'Digit3', 51);
    const upAgain = await until(async () => (await phoneSays('lock'))?.field?.n === 1, 6000);
    check('a digit typed on the lock screen brings the PIN pad up and goes in', !!upAgain, JSON.stringify((await phoneSays('lock'))?.field));
    await key('Backspace', 'Backspace', 8);
    await until(async () => (await phoneSays('lock'))?.field?.n === 0, 4000);
    sh('input keyevent KEYCODE_BACK');
    await sleep(800);
    sh('input keyevent KEYCODE_SLEEP');
    await until(() => power() !== 'Awake', 5000, 300);
    const asleep = power();
    const at = await page.evaluate(`(() => { const r = document.getElementById('screen').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
    for (const type of ['mousePressed', 'mouseReleased']) await page.call('Input.dispatchMouseEvent', { type, x: at[0], y: at[1], button: 'left', clickCount: 1 });
    const awake = await until(() => power() === 'Awake', 5000, 300);
    check('a click on a phone that sleeps wakes it', asleep !== 'Awake' && !!awake, `${asleep} → ${power()}`);
    check('…and does no more: the lock screen is there, without its PIN pad', keyguard() && (await phoneSays('lock'))?.pad === '');
  } catch (e) {
    check('the lock screen part ran to its end', false, String(e).slice(0, 200));
  }

  // ── closing the page ──
  if (!only || only === 'close') try {
    log('— closing the page —');
    // Sound in this browser first, so the phone is quiet when the page goes: its own
    // sound coming back is then something the close did (a phone without the
    // permission is quiet all the same, for the session).
    await clickStatus();
    await choose('here');
    await until(() => music().muted, 4000, 300);
    const mutedBefore = music().muted;
    check('before the close: the page plays the phone\'s sound, and the phone is quiet', mutedBefore);
    const marker = `close-${Date.now()}`;
    sh(`log -t OpenDeX "[test] ${marker}"`);
    const closing = page.call('Page.close');
    const dialog = await until(() => page.events.find((e) => e.method === 'Page.javascriptDialogOpening'), 5000);
    check('closing the tab while the phone is on screen asks first', dialog && dialog.params.type === 'beforeunload', dialog ? dialog.params.type : 'no question');
    const asked = Date.now();
    if (dialog) await page.call('Page.handleJavaScriptDialog', { accept: true });
    await Promise.race([closing, sleep(3000)]);
    const ended = await until(() => {
      const all = appLog();
      const at = all.indexOf(marker);
      const lines = at < 0 ? '' : all.slice(all.lastIndexOf('\n', at) + 1);
      return /closed the screen/.test(lines) ? lines : null;
    }, 8000, 200);
    if (ended) {
      // How long the phone took, by its own clock: from the test's mark to the session's end.
      const ms = (line) => { const m = line.match(/(\d\d):(\d\d):(\d\d)\.(\d\d\d)/); return m ? ((+m[1] * 60 + +m[2]) * 60 + +m[3]) * 1000 + +m[4] : NaN; };
      const lines = ended.split('\n');
      const closedLine = lines.find((l) => /closed the screen/.test(l)) || '';
      log(`the phone, by its own clock: goodbye ${ms(lines.find((l) => /said goodbye/.test(l)) || '') - ms(lines[0])} ms and the session's end ${ms(closedLine) - ms(lines[0])} ms after the close began`);
    }
    check('…and once it is closed, the phone ends that browser\'s session at once', !!ended, `${((Date.now() - asked) / 1000).toFixed(1)} s`);
    check('…having been told goodbye', ended && /said goodbye/.test(ended));
    check('…its sound stops and the phone\'s own sound comes back', !music().muted, `muted before the close: ${mutedBefore}`);
    check('Remote desktop stays on at the phone, listening for the next one', runas('[ -e files/phone-remote/on ] && echo on') === 'on' && /listening|opened|closed/.test(phoneLog()));
    page = null;
  } catch (e) {
    check('the closing part ran to its end', false, String(e).slice(0, 200));
  }
} finally {
  if (token) log('this test browser signed out:', await forget(token));
  await browser.close();
  rmSync(profile, { recursive: true, force: true });
  runas(`echo ${untilBefore} > files/browser/pairing-until${padMarkBefore ? '' : '; rm -f files/phone-remote/testpad'}`);
  const after = music();
  log(`after: media ${after.muted ? 'MUTED' : 'not muted'} at ${after.volume} (before: ${musicBefore.muted ? 'muted' : 'not muted'} at ${musicBefore.volume}); sign-in window ${runas('cat files/browser/pairing-until')}; test pad mark ${runas('[ -e files/phone-remote/testpad ] && echo left || echo gone')}`);
  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}
