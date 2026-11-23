// On a real phone, in a visible Edge: Remote desktop's page and its Files,
// the way a person uses them. Let in with the door's code; Files; Downloads;
// into /Download/oadx-files-test (made for this, gone at the end); a 12 MiB
// file sent with Upload, Files…; then a different file with the same name,
// size and time, Replace: sent (one PUT), and the phone has its bytes (the
// same size and time is only a quick check; until 2026-11-21 Replace kept
// the first, without a word). With --shot, a picture
// of Files at the end, of that folder alone (the phone's screen is never
// pictured: Files covers it, and the phone sends no picture while Files are up).
//
//   node files-page.mjs <msedge.exe> https://<phone>:7443 <door code> <token> [--adb <serial>] [--shot <png>] [--port 9442]
//
// <token>: a browser already let in (files-check.mjs prints one), for making
// and removing the test folder outside the page.
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, statSync, utimesSync, writeFileSync } from 'node:fs';
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
const shotTo = opt('--shot', null);
const port = Number(opt('--port', 9442));
const [edge, base, code, token] = argv;
if (!edge || !base || !code || !token) {
  console.log('usage: files-page.mjs <msedge.exe> https://<phone>:7443 <door code> <token> [--adb <serial>] [--shot <png>] [--port 9442]');
  process.exit(2);
}
const origin = base.replace(/\/$/, '');
const DIR = '/Download/oadx-files-test';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const check = (what, ok) => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`);
  if (!ok) failed++;
};
async function until(fn, ms, every = 150) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return null;
    await sleep(every);
  }
}

// Outside the page: the test folder made, and later removed.
const api = async (method, op, query, form) => {
  const r = await fetch(`${origin}/pfs/${op}${query ? '?' + new URLSearchParams(query) : ''}`, {
    method,
    headers: { Authorization: 'Bearer ' + token, ...(form ? { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin } : {}) },
    body: form ? new URLSearchParams(form).toString() : undefined,
  });
  return { status: r.status, json: await r.json().catch(() => null) };
};

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  };
  const call = (method, params = {}) => new Promise((r) => { const n = ++id; waiting.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
  const js = async (expr) => {
    const r = await call('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };
  return { ws, call, js };
}

const top = await api('GET', 'list', { path: '/Download' });
if (top.status !== 200) {
  console.log(`list /Download: ${top.status}`);
  process.exit(1);
}
if (top.json.entries.some((e) => e.n === 'oadx-files-test')) {
  console.log(`${DIR} is already there: not mine to touch, so nothing is done`);
  process.exit(1);
}
const made = await api('POST', 'mkdir', null, { path: DIR });
check(`the test folder made: ${made.status}`, made.status === 200);

const local = mkdtempSync(join(tmpdir(), 'files-page-'));
const profile = mkdtempSync(join(tmpdir(), 'files-page-edge-'));
const file = join(local, 'big.bin');
const bytes = randomBytes(12 * 1024 * 1024);
writeFileSync(file, bytes);
const sha = createHash('sha256').update(bytes).digest('hex');
// The other one: the same name, size and time, different bytes.
mkdirSync(join(local, 'other'));
const other = join(local, 'other', 'big.bin');
const otherBytes = randomBytes(bytes.length);
writeFileSync(other, otherBytes);
const firstTimes = statSync(file);
utimesSync(other, firstTimes.atime, firstTimes.mtime);
const otherSha = createHash('sha256').update(otherBytes).digest('hex');
let browser = null;
try {
  browser = await launchEdge(edge, { port, profile, args: ['--ignore-certificate-errors', '--window-size=1200,860', '--window-position=60,30'] });
  const P = await cdp(browser.page.webSocketDebuggerUrl);
  await P.call('Page.enable');
  await P.call('Page.navigate', { url: `${origin}/phone.html` });
  const pairUp = await until(() => P.js(`!!document.getElementById('pair') && !document.getElementById('pair').hidden`).catch(() => false), 15000);
  check('the phone\'s page asks for the code', !!pairUp);
  await P.js(`(() => { document.getElementById('code').value = ${JSON.stringify(code)}; document.getElementById('pair').requestSubmit(); })()`);
  const btn = await until(() => P.js(`document.getElementById('files-btn').hidden === false`), 15000);
  check('let in: a Files button in the bar', !!btn);

  await P.js(`document.getElementById('files-btn').click()`);
  const opened = await until(() => P.js(`!!document.querySelector('#files.on') && document.querySelectorAll('#files .place').length > 0`), 10000);
  const places = await P.js(`[...document.querySelectorAll('#files .place span')].map((e) => e.textContent)`);
  check(`Files open, the phone's places down the side: ${JSON.stringify(places)}`, !!opened && places[0] === 'Phone' && places.includes('Downloads'));
  await P.js(`[...document.querySelectorAll('#files .place')].find((x) => x.textContent === 'Downloads').click()`);
  const row = await until(() => P.js(`(() => { const r = [...document.querySelectorAll('#files .fx-row, #files .fx-tile')].find((x) => (x.querySelector('.n') || x.querySelector('.fx-tname')).textContent === 'oadx-files-test');
    if (!r) return null; const b = r.getBoundingClientRect(); return { x: Math.round(b.left + Math.min(160, b.width / 2)), y: Math.round(b.top + b.height / 2) }; })()`), 10000);
  check('Downloads listed, the test folder in it', !!row);
  if (!row) throw new Error('no test folder row');
  // A double-click, as a person opens a folder.
  await P.call('Input.dispatchMouseEvent', { type: 'mouseMoved', x: row.x, y: row.y });
  for (const c of [1, 2]) {
    await P.call('Input.dispatchMouseEvent', { type: 'mousePressed', x: row.x, y: row.y, button: 'left', buttons: 1, clickCount: c });
    await P.call('Input.dispatchMouseEvent', { type: 'mouseReleased', x: row.x, y: row.y, button: 'left', buttons: 0, clickCount: c });
  }
  const inside = await until(() => P.js(`(document.querySelector('#files .crumb.here') || {}).textContent === 'oadx-files-test'`), 8000);
  const rowsNow = () => P.js(`[...document.querySelectorAll('#files .fx-row .fx-name .n, #files .fx-tile .fx-tname')].map((e) => e.textContent)`);
  const empty = await until(async () => (await rowsNow()).length === 0, 5000);
  check(`a double-click opens the test folder, empty: here ${JSON.stringify(await P.js(`(document.querySelector('#files .crumb.here') || {}).textContent`))}, rows ${JSON.stringify(await rowsNow())}`,
    !!inside && !!empty);

  await P.js(`(() => { window.__puts = []; const o = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u, ...rest) { if (m === 'PUT' && /pfs\\/put/.test(u)) window.__puts.push(String(u)); return o.call(this, m, u, ...rest); }; })()`);
  const input = await P.call('Runtime.evaluate', { expression: `document.querySelectorAll('#files input[type=file]')[0]` });
  const node = await P.call('DOM.describeNode', { objectId: input.result.result.objectId });
  const pick = (path = file) => P.call('DOM.setFileInputFiles', { files: [path], backendNodeId: node.result.node.backendNodeId });
  const lastJob = (n) => P.js(`(() => { const all = [...document.querySelectorAll('.tray .job')]; if (all.length < ${n}) return null;
    const j = all.find((x) => !x.dataset.seenByTest); if (!j || ['running', 'queued', 'preparing'].includes(j.dataset.state)) return null;
    j.dataset.seenByTest = '1'; return j.dataset.state + ' · ' + j.querySelector('.job-status').textContent; })()`);

  // Upload, Files…: 12 MiB, one PUT (a piece is 32 MiB).
  let t0 = Date.now();
  await pick();
  const first = await until(() => lastJob(1), 60000);
  const firstPuts = await P.js('window.__puts.length');
  check(`Upload, Files…: 12 MiB sent: "${first}" in ${Date.now() - t0} ms, ${firstPuts} PUT`, /^done · /.test(first || '') && firstPuts === 1);
  if (serial) {
    const onDisk = execFileSync('adb', ['-s', serial, 'shell', `sha256sum /sdcard${DIR}/big.bin`], { encoding: 'utf8' }).trim().split(/\s+/)[0];
    check(`…on the phone's own disk, byte for byte: ${onDisk.slice(0, 16)}…`, onDisk === sha);
  }
  const listed = await until(() => P.js(`[...document.querySelectorAll('#files .fx-row .fx-name .n, #files .fx-tile .fx-tname')].map((e) => e.textContent).join()`), 8000);
  check(`…and the folder shows it: ${listed}`, listed === 'big.bin');

  // A different file with the same name, size and time: the page asks,
  // Replace, and the phone gets its bytes.
  await P.js('window.__puts.length = 0');
  await P.js(`document.querySelectorAll('#files input[type=file]')[0].value = ''`);
  t0 = Date.now();
  await pick(other);
  const asked = await until(() => P.js(`(() => { const d = document.querySelector('dialog.fx-dialog[open]'); return d ? d.querySelector('h2').textContent : null; })()`), 8000);
  check(`a different file with the same name, size and time: the page asks: ${asked}`, asked === '“big.bin” is already in oadx-files-test');
  await P.js(`[...document.querySelectorAll('dialog.fx-dialog[open] button')].find((b) => b.textContent === 'Replace').click()`);
  const second = await until(() => lastJob(2), 60000);
  const secondPuts = await P.js('window.__puts.length');
  check(`…Replace: "${second}" in ${Date.now() - t0} ms, ${secondPuts} PUT`, /^done · /.test(second || '') && !/already there/.test(second || '') && secondPuts === 1);
  if (serial) {
    const onDisk = execFileSync('adb', ['-s', serial, 'shell', `sha256sum /sdcard${DIR}/big.bin`], { encoding: 'utf8' }).trim().split(/\s+/)[0];
    check(`…and the phone's own disk has the new bytes: ${onDisk.slice(0, 16)}…`, onDisk === otherSha);
  }

  if (shotTo) {
    await sleep(400);
    const s = await P.call('Page.captureScreenshot', { format: 'png' });
    writeFileSync(shotTo, Buffer.from(s.result.data, 'base64'));
    console.log('picture:', shotTo);
  }
  P.ws.close();
} finally {
  if (browser) await browser.close();
  const gone = await api('POST', 'delete', null, { p: DIR });
  check(`the test folder removed: ${gone.status} ${JSON.stringify(gone.json)}`, gone.status === 200 && JSON.stringify(gone.json?.deleted) === JSON.stringify([DIR]));
  rmSync(local, { recursive: true, force: true });
  rmSync(profile, { recursive: true, force: true });
}
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exitCode = failed ? 1 : 0;
