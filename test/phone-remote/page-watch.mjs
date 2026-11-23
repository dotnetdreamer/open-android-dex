// What a viewer page says over time: its status line, the route, what came
// in, every few seconds, in a visible Edge with a profile kept between runs.
//
//   node page-watch.mjs <edge.exe> <url> <profile dir> [seconds] [--relay] [--code C] [--port P] [--shot out.png]
import { writeFileSync } from 'node:fs';
import { launchEdge } from './edge.mjs';

const argv = process.argv.slice(2);
const [edge, url, profile, secs = '60'] = argv;
const opt = (name, def) => { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : def; };
const port = Number(opt('--port', 9338));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await launchEdge(edge, { port, profile, args: ['--ignore-certificate-errors', '--window-size=520,1040', '--window-position=600,10'] });
const ws = new WebSocket(browser.page.webSocketDebuggerUrl);
await new Promise((r) => { ws.onopen = r; });
let id = 0;
const waiting = new Map();
const logs = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.method === 'Runtime.consoleAPICalled') logs.push(m.params.args.map((a) => a.value ?? a.description).join(' '));
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
};
const call = (method, params = {}) => new Promise((r) => { const n = ++id; waiting.set(n, r); ws.send(JSON.stringify({ id: n, method, params })); });
const evaluate = async (expr) => (await call('Runtime.evaluate', { expression: expr, returnByValue: true })).result?.result?.value;
await call('Runtime.enable');
await call('Page.enable');
if (argv.includes('--relay')) await call('Page.addScriptToEvaluateOnNewDocument', { source: 'delete window.RTCPeerConnection; window.RTCPeerConnection = undefined;' });
await call('Page.navigate', { url });
// --code: pair on the way in (the door's six digits, or the page from anywhere's eight characters).
const code = opt('--code', null);
if (code) {
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    const which = await evaluate(`(() => { const a = document.getElementById('apair'); const p = document.getElementById('pair');
      return a && !a.hidden ? 'apair' : p && !p.hidden ? 'pair' : ''; })()`);
    if (which) {
      const input = which === 'apair' ? 'acode' : 'code';
      await evaluate(`document.getElementById('${input}').value = ${JSON.stringify(code)}; document.getElementById('${which}').requestSubmit(); 1`);
      console.log('paired through', which);
      break;
    }
  }
}
const t0 = Date.now();
while (Date.now() - t0 < Number(secs) * 1000) {
  await sleep(5000);
  const s = await evaluate(`JSON.stringify({ status: (document.getElementById('status') || {}).textContent, route: window.PHONE_ROUTE,
    cover: document.getElementById('cover') && !document.getElementById('cover').hidden ? document.getElementById('cover-words').textContent : '' })`);
  console.log(`${((Date.now() - t0) / 1000).toFixed(0)} s`, s);
}
const shot = opt('--shot', null);
if (shot) { const r = await call('Page.captureScreenshot', { format: 'png' }); writeFileSync(shot, Buffer.from(r.result.data, 'base64')); }
if (logs.length) console.log('console:\n  ' + logs.slice(-8).join('\n  '));
ws.close();
await browser.close();
