// Screens Android keeps for a person at the phone, red then green, on a real
// phone (doc/phone-remote.md, "The phone's helper"): a computer's tap through
// the accessibility service, then the same tap through the phone's helper.
//
//   node protected-tap.mjs <edge.exe> <adb serial> <door address> [--port 9613]
//
// - Android's own permission question (the app's microphone, asked over the
//   phone's screen when the page chooses Sound in browser): "Don't allow",
//   pressed through the page;
// - a sensitive Settings switch (the app's All files access).
// Each target is found with uiautomator's dump (the shell's, which sees what
// the service may not), pressed at its middle through the page's own input,
// and the result read back from Android (the dialog still in front or gone,
// the app-op). At the end every grant and flag is put back, the helper told
// to go, the test browser signed out.
//
// Needs a debuggable build, Remote desktop on with control, the phone
// unlocked and nobody using it (real input stops the test).
import { execFileSync, spawn } from 'node:child_process';
import https from 'node:https';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const [edgeExe, serial, door] = argv;
const PORT = Number(argv.includes('--port') ? argv[argv.indexOf('--port') + 1] : 9613);
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
async function until(fn, ms = 8000, every = 200) {
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
const keyguard = () => /isKeyguardShowing=true/.test(sh('dumpsys window | grep -m1 isKeyguardShowing; true'));
const front = () => (sh('dumpsys activity activities | grep -m1 topResumedActivity; true').match(/u0 ([^ ]+) /) || [])[1] || '';
const helperPids = () => sh('ps -A -o PID,ARGS | grep PhoneHelper | grep -v grep; true').split('\n').filter(Boolean);
const helperState = () => sh(`CLASSPATH=$(pm path --user 0 ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --state ${PKG} 2>&1; true`).split('\n').slice(-1)[0];
const quitHelper = () => sh(`CLASSPATH=$(pm path --user 0 ${PKG} | grep base.apk | head -1 | cut -d: -f2) app_process / com.ccrstech.openandroiddex.launcher.PhoneHelper --quit ${PKG} 2>&1; true`).split('\n').slice(-1)[0];
const audio = () => sh(`dumpsys package ${PKG} | grep -m1 "android.permission.RECORD_AUDIO: granted"; true`);
const modes = (t) => [...t.matchAll(/MANAGE_EXTERNAL_STORAGE: (\w+)/g)].map((m) => m[1]).join(',');
const allFiles = () => `package ${modes(sh(`appops get ${PKG} MANAGE_EXTERNAL_STORAGE; true`))} uid ${modes(sh(`appops get --uid ${PKG} MANAGE_EXTERNAL_STORAGE; true`))}`;
/** Where a node is, from the shell's own dump of the screen: the first whose text or class matches. */
function nodeAt(match) {
  sh('uiautomator dump /sdcard/oadx-ui.xml >/dev/null 2>&1; true');
  const xml = sh('cat /sdcard/oadx-ui.xml; rm -f /sdcard/oadx-ui.xml');
  for (const n of xml.matchAll(/<node [^>]*>/g)) {
    const a = n[0];
    if (!match(a)) continue;
    const b = a.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (b) return { x: (Number(b[1]) + Number(b[3])) / 2, y: (Number(b[2]) + Number(b[4])) / 2, node: a.slice(0, 260) };
  }
  return null;
}
let human = [];
function watchHuman() {
  const devs = sh('getevent -pl 2>/dev/null | grep -E "add device|name:"').split('\n');
  const paths = [];
  for (let i = 0; i < devs.length; i++) {
    const dev = (devs[i].match(/add device \d+: (\S+)/) || [])[1];
    const name = (devs[i + 1] || '').match(/name:\s+"(.*)"/)?.[1] || '';
    if (dev && /touch|pon|gpio[-_]keys|pwrkey|resin|power/i.test(name) && !/uinput|fpc|goodix|proximity/i.test(name)) paths.push(dev);
  }
  const w = spawn(ADB, ['-s', serial, 'shell', `getevent -t ${paths.join(' ')}`], { stdio: ['ignore', 'pipe', 'ignore'] });
  w.stdout.on('data', (d) => { for (const l of d.toString().split('\n')) if (l.trim()) human.push(l.trim()); });
  return w;
}
function safeToTap(what) {
  if (human.length) throw new Error(`a person is using the phone (real input), before ${what}`);
  if (keyguard()) throw new Error(`the phone is locked, before ${what}`);
}

// ── the page ──
const PROBE = `(() => {
  if (window.__t) return;
  const P = window.__t = { texts: [], sock: null };
  const WS = window.WebSocket;
  window.WebSocket = class extends WS {
    constructor(...a) {
      super(...a);
      if (!String(a[0]).includes('/phone')) return;
      P.sock = this;
      this.addEventListener('message', (ev) => {
        if (typeof ev.data === 'string') { try { const m = JSON.parse(ev.data); if (['video', 'protected', 'audio', 'phone'].includes(m.t)) P.texts.push(m); } catch { /* not ours */ } }
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
    if (m.method === 'Page.javascriptDialogOpening') ws.send(JSON.stringify({ id: ++id, method: 'Page.handleJavaScriptDialog', params: { accept: true } }));
  };
  const call = (method, params = {}) => new Promise((r) => {
    const n = ++id;
    const timer = setTimeout(() => { waiting.delete(n); r({ error: { message: `${method}: no answer` } }); }, 15000);
    waiting.set(n, (m) => { clearTimeout(timer); r(m); });
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const evaluate = async (e) => {
    const r = await call('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true });
    return r.result?.result?.value;
  };
  return { call, evaluate };
}
let page = null;
const lodPhone = () => page.evaluate('window.lodPhone && window.lodPhone()');
async function click(id) {
  const at = await page.evaluate(`(() => { const r = document.getElementById('${id}').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
  for (const type of ['mousePressed', 'mouseReleased']) await page.call('Input.dispatchMouseEvent', { type, x: at[0], y: at[1], button: 'left', buttons: type === 'mousePressed' ? 1 : 0, clickCount: 1 });
}
/** A press at the phone's own pixel (x, y), through the page's picture (the page's own input). */
async function tapPhone(x, y) {
  const vid = await page.evaluate(`window.__t.texts.filter((m) => m.t === 'video').slice(-1)[0] || null`);
  const d = await page.evaluate(`(() => { const c = document.getElementById('screen'); const r = c.getBoundingClientRect();
    const s = Math.min(r.width / c.width, r.height / c.height);
    return { cw: c.width, s, ox: r.left + (r.width - c.width * s) / 2, oy: r.top + (r.height - c.height * s) / 2 }; })()`);
  const k = d.cw / vid.dw;
  const px = d.ox + x * k * d.s, py = d.oy + y * k * d.s;
  await page.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: px, y: py, button: 'left', buttons: 1, clickCount: 1 });
  await sleep(70);
  await page.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: px, y: py, button: 'left', buttons: 0, clickCount: 1 });
}
async function chooseSound(v) {
  await page.evaluate(`(() => { const s = document.getElementById('sound'); s.value = '${v}'; s.dispatchEvent(new Event('change', { bubbles: true })); return s.value; })()`);
}
const forget = (tok) => new Promise((res) => {
  const body = new URLSearchParams({ token: tok }).toString();
  const req = https.request(`${door}/forget`, {
    method: 'POST', rejectUnauthorized: false,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body), Origin: door },
  }, (r) => { r.resume(); res(r.statusCode); });
  req.on('error', (e) => res(String(e)));
  req.end(body);
});
/** What the phone said about the press, for the layer that drops it: input's recent events and the log around it. */
function evidence(since) {
  const input = sh('dumpsys input | grep -A12 -E "RecentQueue" | head -14; true');
  const lines = sh(`logcat -d -v time -t '${since}' 2>/dev/null | grep -i -E "InputDispatcher|filterTouch|obscured|sensitive|accessibility.*(drop|block|tool)|ViewRootImpl.*drop" | tail -8; true`);
  return { input: input.split('\n').slice(0, 6).join(' | '), log: lines };
}
const logTime = () => sh("date '+%m-%d %H:%M:%S.000'");

// ── set up ──
if (keyguard()) { console.log('the phone is locked: this test never unlocks one'); process.exit(2); }
const watcher = watchHuman();
const audioBefore = audio();
const filesBefore = allFiles();
const helperBefore = helperPids();
log(`before: microphone ${audioBefore}; all files ${filesBefore}; helper ${helperBefore.length ? 'running' : 'none'}; front ${front()}`);
if (helperBefore.length) log('helper told to go first (red needs none):', quitHelper());
const code = runas('cat files/browser/code').split(' ')[0];
const phoneNow = Number(sh('date +%s%3N'));
runas(`echo ${phoneNow + 900000} > files/browser/pairing-until`);
const profile = mkdtempSync(join(tmpdir(), 'protected-'));
const browser = await launchEdge(edgeExe, { port: PORT, profile, args: ['--ignore-certificate-errors', '--window-size=600,1080', '--window-position=700,10', '--disable-sync', '--disable-extensions'] });
let token = null;
let helperStarted = false;
try {
  page = await cdp(browser.page.webSocketDebuggerUrl);
  await page.call('Runtime.enable');
  await page.call('Page.enable');
  await page.call('Page.addScriptToEvaluateOnNewDocument', { source: PROBE });
  await page.call('Page.navigate', { url: `${door}/phone.html` });
  await until(() => page.evaluate(`!document.getElementById('pair').hidden`), 15000);
  await page.evaluate(`document.getElementById('code').value = ${JSON.stringify(code)}; document.getElementById('pair').requestSubmit(); 1`);
  const up = await until(async () => / × /.test(await page.evaluate(`document.getElementById('status').textContent`)), 25000);
  check('the page signs in and the picture comes', !!up);
  token = await page.evaluate(`localStorage.getItem('lod.token')`);

  // ── 1. Android's permission question ──
  log('— Android\'s permission question (the microphone) —');
  await click('status');
  await chooseSound('here');
  const dialog = await until(() => (nodeAt((a) => /text="Don.t allow"|text="Deny"/i.test(a)) ? front() || 'up' : null), 20000, 1000);
  check('choosing Sound in browser brings up Android\'s own microphone question', !!dialog, front());
  const isDeny = (a) => /text="Don.t allow"|text="Deny"/i.test(a);
  let deny = nodeAt(isDeny);
  check('…its "Don\'t allow" button, found by the shell', !!deny, deny ? deny.node.slice(0, 160) : '');
  if (dialog && deny) {
    safeToTap('the red press on the dialog');
    const since = logTime();
    await tapPhone(deny.x, deny.y);
    await sleep(2000);
    const stillThere = !!nodeAt(isDeny);
    check('RED: the accessibility service\'s tap on "Don\'t allow" does nothing: the question is still in front', stillThere, front());
    const ev = evidence(since);
    log('  the phone around that tap:', JSON.stringify(ev));
    const note = await until(async () => (await lodPhone())?.protectedNote || '', 3000);
    check('…and the page says why, in plain words', /Android lets only someone at the phone/.test(note || ''), note);

    // The helper, as the page says to start it.
    await click('panel-btn');
    await sleep(500);
    const cmd = await page.evaluate(`document.getElementById('panel-cmd').textContent`);
    const m = cmd.match(/^adb shell "(.*)"$/);
    if (m) {
      execFileSync(ADB, ['-s', serial, 'shell', m[1]], { encoding: 'utf8', timeout: 15000 });
      helperStarted = true;
    }
    const linked = await until(async () => ((await lodPhone())?.panel === 'on' ? true : null), 8000);
    check('the helper started with the page\'s command, and linked', !!linked);
    await click('panel-close').catch(() => {});
    if (nodeAt(isDeny)) {
      deny = nodeAt(isDeny) || deny;
      safeToTap('the green press on the dialog');
      const hs0 = helperState();
      await tapPhone(deny.x, deny.y);
      const gone = await until(() => (!nodeAt(isDeny) ? front() || 'gone' : null), 6000, 800);
      check('GREEN: the same tap through the helper presses "Don\'t allow": the question is gone', !!gone, `${hs0} → ${helperState()}; front ${front()}`);
      check('…and the microphone is still not granted', /granted=false/.test(audio()), audio());
    }
  }
  await chooseSound('phone');
  await sleep(800);

  // ── 2. A sensitive Settings switch: the app's All files access ──
  log('— a sensitive Settings switch (All files access) —');
  log('helper told to go (red needs none):', quitHelper());
  await until(async () => ((await lodPhone())?.panel === 'none' ? true : null), 8000);
  sh(`am start -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -d package:${PKG} >/dev/null 2>&1; true`);
  await sleep(2500);
  const sw = nodeAt((a) => /checkable="true"/.test(a) && /class="android.widget.(Switch|CompoundButton|CheckBox)"|Switch/.test(a));
  check('Android\'s All files access page for the app, its switch found by the shell', !!sw, sw ? sw.node.slice(0, 200) : front());
  if (sw) {
    const before = allFiles();
    safeToTap('the red press on the switch');
    const since = logTime();
    await tapPhone(sw.x, sw.y);
    await sleep(2000);
    const afterRed = allFiles();
    check('RED: the accessibility service\'s tap leaves the switch as it was', afterRed === before, `${before} → ${afterRed}`);
    log('  the phone around that tap:', JSON.stringify(evidence(since)));
    const cmd = await page.evaluate(`document.getElementById('panel-cmd').textContent || ''`);
    const start = (await lodPhone())?.panelStart || cmd;
    const m = String(start).match(/^adb shell "(.*)"$/);
    if (m) {
      execFileSync(ADB, ['-s', serial, 'shell', m[1]], { encoding: 'utf8', timeout: 15000 });
      helperStarted = true;
    }
    const linked = await until(async () => ((await lodPhone())?.panel === 'on' ? true : null), 8000);
    check('the helper again, linked', !!linked);
    safeToTap('the green press on the switch');
    await tapPhone(sw.x, sw.y);
    const changed = await until(() => (allFiles() !== before ? allFiles() : null), 4000);
    check('GREEN: the same tap through the helper turns the switch', !!changed, `${before} → ${allFiles()}`);
  }
} catch (e) {
  check(`the test ran to its end (${e.message})`, false);
} finally {
  // Every grant and flag back as it was, and Android's page for the switch closed.
  sh(`appops set ${PKG} MANAGE_EXTERNAL_STORAGE default; appops set --uid ${PKG} MANAGE_EXTERNAL_STORAGE default; true`);
  sh(`pm revoke ${PKG} android.permission.RECORD_AUDIO 2>/dev/null; pm clear-permission-flags ${PKG} android.permission.RECORD_AUDIO user-set user-fixed 2>/dev/null; true`);
  if (!keyguard() && /AppManageExternalStorage/.test(front())) sh('input keyevent 4; true');
  try { await page.call('Page.navigate', { url: 'about:blank' }); } catch { /* gone */ }
  await sleep(800);
  if (token) log(`this browser signed out: ${await forget(token)}`);
  try { await browser.close(); } catch { /* gone */ }
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* Edge's files */ }
  if (helperStarted) log('helper told to go:', quitHelper());
  try { watcher.kill(); } catch { /* gone */ }
  log(`real input during the test: ${human.length ? `${human.length} events (a person used the phone)` : 'none'}`);
  log(`after: microphone ${audio()} (before ${audioBefore}); all files ${allFiles()} (before ${filesBefore}); helper ${helperPids().length ? 'running' : 'none'}; front ${front()}`);
  log(`${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}
