// On a real phone, in a visible Edge: Cancel, and Pause then Resume, pressed
// in the transfers' panel while Remote desktop's page asks the phone about a
// big file (the `part` question before its PUT). Nothing is sent after either
// button, and the phone's file stays as it was until Resume. DevTools holds
// the question in the browser until the button has been pressed with the
// mouse, then lets the phone's own answer through. Until 2026-11-22 the page
// sent the PUT once that answer came, whatever had been pressed: a cancelled
// Replace still replaced the phone's file (test/linux-files-test.mjs has the
// same against the launcher's server on this computer).
//
//   node files-cancel.mjs <msedge.exe> https://<phone>:7443 <signed-in profile dir> --adb <serial> [--port 9443]
//
// The profile is a browser already let in (no new sign-in is made). The test
// folder, /Download/oadx-files-test, is made and removed over adb, and must
// not be there before. Needs All files access for the app.
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const opt = (name, d) => {
  const i = argv.indexOf(name);
  if (i < 0) return d;
  const v = argv[i + 1];
  argv.splice(i, 2);
  return v;
};
const serial = opt('--adb', null);
const port = Number(opt('--port', 9443));
const [edge, base, profile] = argv;
if (!edge || !base || !profile || !serial) {
  console.log('usage: files-cancel.mjs <msedge.exe> https://<phone>:7443 <signed-in profile dir> --adb <serial> [--port 9443]');
  process.exit(2);
}
const ADB = process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, 'Android/Sdk/platform-tools/adb.exe') : 'adb';
const adb = (...a) => execFileSync(ADB, ['-s', serial, ...a], { encoding: 'utf8' }).replace(/\r/g, '').trim();
const origin = base.replace(/\/$/, '');
const DIR = '/sdcard/Download/oadx-files-test';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const check = (what, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${!ok && detail ? ` — ${detail}` : ''}`);
  if (!ok) failed++;
};
async function until(fn, ms, every = 120) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return null;
    await sleep(every);
  }
}
async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  const held = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Fetch.requestPaused') held.push(m.params);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  };
  const call = (method, params = {}) => new Promise((r) => { const n = ++id; waiting.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
  const js = async (expr) => {
    const r = await call('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };
  return { ws, call, js, held };
}
const shaOnPhone = () => adb('shell', `sha256sum ${DIR}/film.bin 2>/dev/null`).split(/\s+/)[0] || null;

if (adb('shell', `ls -d ${DIR} 2>/dev/null; echo`).includes('oadx-files-test')) {
  console.log(`${DIR} is already there: not mine to touch, so nothing is done`);
  process.exit(1);
}
const local = mkdtempSync(join(tmpdir(), 'files-cancel-'));
const mine = join(local, 'phone-film.bin');
const theirs = randomBytes(20 * 1024 * 1024);
writeFileSync(mine, theirs);
const firstSha = createHash('sha256').update(theirs).digest('hex');
adb('shell', `mkdir -p ${DIR}`);
execFileSync(ADB, ['-s', serial, 'push', mine, `${DIR}/film.bin`], { stdio: 'ignore' });
check('the test folder made over adb, a 20 MiB film.bin in it', shaOnPhone() === firstSha);
const other = join(local, 'film.bin');
const otherBytes = randomBytes(theirs.length);
writeFileSync(other, otherBytes);
const otherSha = createHash('sha256').update(otherBytes).digest('hex');

let browser = null;
try {
  browser = await launchEdge(edge, { port, profile, args: ['--ignore-certificate-errors', '--window-size=1200,860', '--window-position=60,30'] });
  const P = await cdp(browser.page.webSocketDebuggerUrl);
  await P.call('Page.enable');
  await P.call('Runtime.enable');
  await P.call('Page.navigate', { url: `${origin}/phone.html` });
  const btn = await until(() => P.js(`!!document.getElementById('files-btn') && document.getElementById('files-btn').hidden === false`).catch(() => false), 20000);
  check('signed in already: a Files button in the bar', !!btn);
  await P.js(`document.getElementById('files-btn').click()`);
  await until(() => P.js(`!!document.querySelector('#files.on') && document.querySelectorAll('#files .place').length > 0`), 10000);
  await P.js(`[...document.querySelectorAll('#files .place')].find((x) => x.textContent === 'Downloads').click()`);
  const row = await until(() => P.js(`(() => { const r = [...document.querySelectorAll('#files .fx-row, #files .fx-tile')].find((x) => (x.querySelector('.n') || x.querySelector('.fx-tname')).textContent === 'oadx-files-test');
    if (!r) return null; const b = r.getBoundingClientRect(); return { x: Math.round(b.left + Math.min(160, b.width / 2)), y: Math.round(b.top + b.height / 2) }; })()`), 10000);
  if (!row) throw new Error('no test folder row');
  const mouse = async (x, y, count = 1) => {
    await P.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
    for (let c = 1; c <= count; c++) {
      await P.call('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: c });
      await P.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: c });
    }
  };
  await mouse(row.x, row.y, 2);
  const inside = await until(() => P.js(`(document.querySelector('#files .crumb.here') || {}).textContent === 'oadx-files-test'
    && [...document.querySelectorAll('#files .fx-row .fx-name .n, #files .fx-tile .fx-tname')].some((e) => e.textContent === 'film.bin')`), 8000);
  check('into the test folder with a double-click, film.bin listed', !!inside);

  await P.js(`(() => { window.__puts = 0; const o = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u, ...rest) { if (m === 'PUT' && /pfs\\/put/.test(u)) window.__puts++; return o.call(this, m, u, ...rest); }; })()`);
  const input = await P.call('Runtime.evaluate', { expression: `document.querySelectorAll('#files input[type=file]')[0]` });
  const node = await P.call('DOM.describeNode', { objectId: input.result.result.objectId });
  const pick = async () => {
    await P.js(`document.querySelectorAll('#files input[type=file]')[0].value = ''`);
    await P.call('DOM.setFileInputFiles', { files: [other], backendNodeId: node.result.node.backendNodeId });
  };
  const fold = async () => {
    if (await P.js(`!!document.querySelector('.tray.collapsed')`)) {
      const f = await P.js(`(() => { const b = document.querySelector('.tray .tray-fold').getBoundingClientRect(); return { x: Math.round(b.left + b.width / 2), y: Math.round(b.top + b.height / 2) }; })()`);
      await mouse(f.x, f.y);
    }
  };
  /** The film's job's button, where the mouse would press it once the panel stops moving. */
  const panelButton = async (label) => {
    await fold();
    let last = null;
    return until(async () => {
      const at = await P.js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === 'film.bin' && !x.dataset.seenByTest);
        const b = j && j.querySelector('button[aria-label="${label}"]'); if (!b) return null; const r = b.getBoundingClientRect();
        return r.width > 0 && r.height > 0 ? { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) } : null; })()`);
      const still = at && last && at.x === last.x && at.y === last.y;
      last = at;
      return still ? at : null;
    }, 4000);
  };
  const jobState = () => P.js(`(() => { const j = [...document.querySelectorAll('.tray .job')].find((x) => x.querySelector('.job-name').textContent === 'film.bin' && !x.dataset.seenByTest);
    return j ? j.dataset.state + ' · ' + j.querySelector('.job-status').textContent : 'gone'; })()`);
  await P.call('Fetch.enable', { patterns: [{ urlPattern: '*/pfs/part*', requestStage: 'Request' }] });
  /** Replace with the other film; once its question is held, {@code label} pressed; then the phone's answer let through. */
  const pressWhileAsked = async (label) => {
    P.held.length = 0;
    await P.js('window.__puts = 0');
    await pick();
    const asked = await until(() => P.js(`(() => { const d = document.querySelector('dialog.fx-dialog[open]'); return d ? d.querySelector('h2').textContent : null; })()`), 8000);
    await P.js(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Replace').click()`);
    const q = await until(() => P.held.find((p) => p.request.url.includes('/pfs/part')), 8000);
    const at = await panelButton(label);
    if (at) await mouse(at.x, at.y);
    if (q) await P.call('Fetch.continueRequest', { requestId: q.requestId });
    await sleep(3000);
    return { asked, held: !!q, pressed: !!at, puts: await P.js('window.__puts'), now: await jobState() };
  };
  const c = await pressWhileAsked('Cancel');
  const afterCancel = shaOnPhone();
  check('Cancel pressed while the phone is asked about the film: nothing sent, the phone\'s film as it was',
    c.asked === '“film.bin” is already in oadx-files-test' && c.held && c.pressed && c.puts === 0 && afterCancel === firstSha, JSON.stringify({ ...c, afterCancel: afterCancel && afterCancel.slice(0, 16) }));
  await until(async () => (await jobState()) === 'gone', 6000);
  const p = await pressWhileAsked('Pause');
  const afterPause = shaOnPhone();
  check('…Pause pressed then: nothing sent while paused, the phone\'s film as it was',
    p.held && p.pressed && p.puts === 0 && /^paused/.test(p.now) && afterPause === firstSha, JSON.stringify({ ...p, afterPause: afterPause && afterPause.slice(0, 16) }));
  P.held.length = 0;
  const play = await panelButton('Resume');
  if (play) await mouse(play.x, play.y);
  const again = await until(() => P.held.find((x) => x.request.url.includes('/pfs/part')), 8000);
  if (again) await P.call('Fetch.continueRequest', { requestId: again.requestId });
  await P.call('Fetch.disable');
  const resumed = await until(async () => { const s = await jobState(); return /^(done|failed)/.test(s) ? s : null; }, 60000);
  const afterResume = shaOnPhone();
  check('…Resume: asked again from what the phone has, then sent, and the phone has the new film',
    !!play && !!again && !again.request.url.includes('fresh=1') && /^done/.test(resumed || '') && (await P.js('window.__puts')) === 1 && afterResume === otherSha,
    JSON.stringify({ play: !!play, again: again && again.request.url.replace(/^.*\?/, '').slice(0, 120), resumed, afterResume: afterResume && afterResume.slice(0, 16) }));
  P.ws.close();
} catch (e) {
  check('the run', false, e && e.stack);
} finally {
  if (browser) await browser.close();
  adb('shell', `rm -rf ${DIR}`);
  check('the test folder removed over adb', !adb('shell', `ls -d ${DIR} 2>/dev/null; echo`).includes('oadx-files-test'));
  rmSync(local, { recursive: true, force: true });
}
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exitCode = failed ? 1 : 0;
