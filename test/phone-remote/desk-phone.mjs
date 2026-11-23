// The desktop app's "Phone screen" window, driven over DevTools: pair the app
// with a phone's eight-character code (what "Add a phone" does), then open the
// phone's own screen in its window (what "Phone screen" does), and say what
// the window shows. The app must run with
// WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=<port>.
//
//   node desk-phone.mjs <devtools port> [--code CODE] [--seconds N] [--shot out.png]
import { writeFileSync } from 'node:fs';

const argv = process.argv.slice(2);
const port = Number(argv[0]);
const opt = (name, def) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : def; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function list() {
  for (let i = 0; i < 120; i++) {
    try { return await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch { await sleep(500); }
  }
  throw new Error(`no DevTools on ${port}`);
}
async function open(wsUrl) {
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
    if (r.result?.exceptionDetails) return { error: r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text };
    return r.result?.result?.value;
  };
  await call('Runtime.enable');
  return { ws, call, evaluate, logs };
}

// The main window: the app's page at its dev address.
let main;
for (let i = 0; i < 60 && !main; i++) {
  main = (await list()).find((t) => t.type === 'page' && /localhost:1431/.test(t.url) && !/#phone=/.test(t.url));
  if (!main) await sleep(500);
}
if (!main) { console.log('no main window'); process.exit(1); }
const m = await open(main.webSocketDebuggerUrl);
console.log('main window:', main.url);
let phones = await m.evaluate(`window.__TAURI_INTERNALS__.invoke('remote_phones')`);
console.log('phones before:', JSON.stringify(phones));
const code = opt('--code', null);
if (code) {
  const paired = await m.evaluate(`window.__TAURI_INTERNALS__.invoke('remote_pair_code', { code: ${JSON.stringify(code)} }).then((p) => p, (e) => ({ error: String(e) }))`);
  console.log('paired:', JSON.stringify(paired));
  phones = await m.evaluate(`window.__TAURI_INTERNALS__.invoke('remote_phones')`);
}
if (!Array.isArray(phones) || !phones.length) { console.log('no phone to open'); process.exit(1); }
const phone = phones[phones.length - 1];
const opened = await m.evaluate(`window.__TAURI_INTERNALS__.invoke('remote_open_phone', { id: ${JSON.stringify(phone.id)} }).then(() => 'opened', (e) => ({ error: String(e) }))`);
console.log('Phone screen:', JSON.stringify(opened));

// Its window, and what it says over time.
let win;
for (let i = 0; i < 40 && !win; i++) {
  win = (await list()).find((t) => t.type === 'page' && /#phone=/.test(t.url));
  if (!win) await sleep(500);
}
if (!win) { console.log('no phone window'); process.exit(1); }
const w = await open(win.webSocketDebuggerUrl);
console.log('phone window:', win.url);
const secs = Number(opt('--seconds', 20));
for (let t = 0; t < secs; t += 4) {
  await sleep(4000);
  const s = await w.evaluate(`JSON.stringify({ text: document.body.innerText.replace(/\\s+/g, ' ').slice(0, 300),
    canvas: (() => { const c = document.querySelector('canvas'); return c ? c.width + 'x' + c.height : null; })() })`);
  console.log(`${t + 4} s`, s);
}
const shot = opt('--shot', null);
if (shot) { const r = await w.call('Page.captureScreenshot', { format: 'png' }); writeFileSync(shot, Buffer.from(r.result.data, 'base64')); console.log('picture:', shot); }
if (w.logs.length) console.log('window console:\n  ' + w.logs.slice(-10).join('\n  '));
w.ws.close();
m.ws.close();
process.exit(0);
