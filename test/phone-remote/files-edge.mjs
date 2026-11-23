// On a real phone, in a visible Edge: a file sent to Remote desktop's Files
// and then the same file again, the way the page sends it (a PUT with the
// whole file as its body), through Edge's own network code, which is where a
// phone that answers before it has read the body and then hangs up shows: the
// answer is lost to the reset and the page sees "The phone stopped answering".
// Everything happens in /Download/oadx-files-test, which goes at the end.
//
//   node files-edge.mjs <msedge.exe> https://<phone>:7443 <token> [--size <MiB>] [--port 9441]
import { mkdtempSync, rmSync } from 'node:fs';
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
const mib = Number(opt('--size', 3));
const port = Number(opt('--port', 9441));
const [edge, base, token] = argv;
if (!edge || !base || !token) {
  console.log('usage: files-edge.mjs <msedge.exe> https://<phone>:7443 <token> [--size <MiB>] [--port 9441]');
  process.exit(2);
}
const origin = base.replace(/\/$/, '');

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
  const evaluate = async (expr) => {
    const r = await call('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text);
    return r.result?.result?.value;
  };
  return { ws, call, evaluate };
}

// In the page: every request as the page makes them, the token in its header.
const IN_PAGE = (tok, size) => `(async () => {
  const DIR = '/Download/oadx-files-test', NAME = 'hello.bin';
  const auth = { Authorization: 'Bearer ' + ${JSON.stringify(tok)} };
  const out = [];
  const say = (what) => out.push(what);
  const req = async (method, op, query, body, type) => {
    const url = '/pfs/' + op + (query ? '?' + new URLSearchParams(query) : '');
    try {
      const r = await fetch(url, { method, headers: type ? { ...auth, 'Content-Type': type } : auth, body });
      const buf = new Uint8Array(await r.arrayBuffer());
      let json = null;
      try { json = JSON.parse(new TextDecoder().decode(buf)); } catch {}
      return { status: r.status, json, buf };
    } catch (e) {
      return { status: 0, error: String(e) };
    }
  };
  const hex = async (b) => [...new Uint8Array(await crypto.subtle.digest('SHA-256', b))].map((x) => x.toString(16).padStart(2, '0')).join('');
  const top = await req('GET', 'list', { path: '/Download' });
  if (top.status !== 200) return { out: ['list /Download: ' + top.status], failed: 1 };
  if (top.json.entries.some((e) => e.n === 'oadx-files-test')) return { out: [DIR + ' is already there: not mine to touch'], failed: 1 };
  let failed = 0;
  const check = (ok, what) => { say((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failed++; };
  try {
    const mk = await req('POST', 'mkdir', null, new URLSearchParams({ path: DIR }).toString(), 'application/x-www-form-urlencoded');
    check(mk.status === 200, 'mkdir: ' + mk.status);
    const bytes = new Uint8Array(${mib} * 1024 * 1024);
    for (let i = 0; i < bytes.length; i += 65536) crypto.getRandomValues(bytes.subarray(i, i + 65536));
    const sha = await hex(bytes);
    const q = { path: DIR + '/' + NAME, size: bytes.length, off: 0, mtime: 1763740000000, on: 'replace' };
    let t = performance.now();
    const a = await req('PUT', 'put', q, bytes, 'application/octet-stream');
    check(a.status === 200 && a.json && a.json.done === true, 'put ${mib} MiB: ' + a.status + ' ' + JSON.stringify(a.json || a.error) + ' (' + Math.round(performance.now() - t) + ' ms)');
    t = performance.now();
    const b = await req('PUT', 'put', q, bytes, 'application/octet-stream');
    check(b.status === 200 && b.json && b.json.same === true, 'the same again: ' + b.status + ' ' + JSON.stringify(b.json || b.error) + ' (' + Math.round(performance.now() - t) + ' ms)');
    const g = await req('GET', 'get', { path: DIR + '/' + NAME });
    check(g.status === 200 && (await hex(g.buf)) === sha, 'get: ' + g.status + ', same bytes: ' + (g.buf ? (await hex(g.buf)) === sha : false));
  } finally {
    const d = await req('POST', 'delete', null, new URLSearchParams({ p: DIR }).toString(), 'application/x-www-form-urlencoded');
    check(d.status === 200 && JSON.stringify(d.json && d.json.deleted) === JSON.stringify([DIR]), 'delete: ' + d.status + ' ' + JSON.stringify(d.json || d.error));
  }
  return { out, failed };
})()`;

const profile = mkdtempSync(join(tmpdir(), 'files-edge-'));
let browser = null;
let failed = 1;
try {
  browser = await launchEdge(edge, { port, profile, args: ['--ignore-certificate-errors', '--window-size=700,600', '--window-position=60,40'] });
  const page = await cdp(browser.page.webSocketDebuggerUrl);
  await page.call('Page.enable');
  await page.call('Page.navigate', { url: origin + '/' });
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 250));
    if ((await page.evaluate('location.origin + document.readyState')) === origin + 'complete') break;
  }
  console.log('Edge', (await page.evaluate('navigator.userAgent')).match(/Edg\/[\d.]+/)?.[0], 'on', await page.evaluate('location.origin'));
  const r = await page.evaluate(IN_PAGE(token, mib));
  for (const line of r.out) console.log(line);
  failed = r.failed;
  console.log(failed ? `${failed} FAILED` : 'all passed');
  page.ws.close();
} finally {
  if (browser) await browser.close();
  rmSync(profile, { recursive: true, force: true });
}
process.exitCode = failed ? 1 : 0;
