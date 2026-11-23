// An email's sign-in link ("Email me when an address changes", doc/address-email.md), opened in a
// real browser against the launcher's own door on this computer: the door's classes compiled on
// this JVM with test/linux-browser/DexLog.java (as LinkCheck and linux-browser-test.mjs do), and
// Edge or Chrome, headless, with a profile and a debugging port of its own, as
// linux-browser-test.mjs runs one. The browser reaches the door under the tunnel's name; every
// other name resolves to nothing, so nothing leaves this computer.
//
//   node test/linux-browser/signin-check.mjs
//
// Checks the page's own rules: the secret off the address bar at once, nothing used before Sign in
// is pressed, the token kept where the phone's page reads it, the secret in no request's address
// and only in the POST's body, the words for a used link, a browser signed in already going
// straight on without using the link, a second link opened in the same tab, and the plain http page.
import { spawn, spawnSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const LAUNCHER = path.join(ROOT, 'openandroiddex-launcher/app/src/main/java/com/ccrstech/openandroiddex/launcher');
const ASSETS = path.join(ROOT, 'openandroiddex-launcher/app/src/main/assets/linux/browser');
const JDK = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : '';
const JAVAC = JDK ? path.join(JDK, 'javac') : 'javac';
const JAVA = JDK ? path.join(JDK, 'java') : 'java';
const BROWSERS = process.platform === 'win32'
  ? ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe']
  : ['/usr/bin/microsoft-edge', '/usr/bin/google-chrome', '/usr/bin/chromium'];
const BROWSER = BROWSERS.find((b) => fs.existsSync(b));
const CDP_PORT = 9468;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0, failed = 0;
const check = (what, ok, detail) => {
  if (ok) { passed++; console.log(`  ok   ${what}`); } else { failed++; console.log(`  FAIL ${what}  (${JSON.stringify(detail)})`); }
};
if (!BROWSER) { console.log('no Edge or Chrome on this computer'); process.exit(1); }

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'oadx-signin-'));
const classes = path.join(tmp, 'classes');
fs.mkdirSync(classes);
const built = spawnSync(JAVAC, ['-nowarn', '-d', classes,
  ...['LinuxBrowser', 'LinuxBrowserCert', 'LinuxBrowserServer', 'LinuxStreamSocket', 'LinuxStreamAudio', 'LinuxAudioLag',
    'LinuxFiles', 'LinuxStreamRate'].map((c) => path.join(LAUNCHER, `${c}.java`)),
  path.join(HERE, 'DexLog.java'), path.join(HERE, 'SigninDoor.java')], { encoding: 'utf8' });
check("the door's classes compile on this JVM", built.status === 0, built.stderr);
if (built.status !== 0) process.exit(1);

// ── the door ──
const door = spawn(JAVA, ['-cp', classes, 'com.ccrstech.openandroiddex.launcher.SigninDoor', ASSETS], { stdio: ['pipe', 'pipe', 'pipe'] });
door.stderr.on('data', () => {});
const waiting = [];
createInterface({ input: door.stdout }).on('line', (l) => { const w = waiting.shift(); if (w) w(l); });
const nextLine = () => new Promise((r) => waiting.push(r));
const ask = async (cmd) => { const p = nextLine(); door.stdin.write(cmd + '\n'); return JSON.parse(await p); };
const first = JSON.parse(await nextLine());
const origin = `https://quiet-river-fox.trycloudflare.com:${first.port}`;

// ── the browser ──
const profile = path.join(tmp, 'profile');
const browser = spawn(BROWSER, ['--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-background-networking', '--disable-component-update',
  '--disable-sync', '--no-pings', '--ignore-certificate-errors',
  '--host-resolver-rules=MAP quiet-river-fox.trycloudflare.com 127.0.0.1, MAP * ~NOTFOUND', 'about:blank'], { stdio: 'ignore' });
let target;
for (let i = 0; i < 80 && !target; i++) {
  try { target = (await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()).find((t) => t.type === 'page'); } catch { await sleep(250); }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let id = 0;
const pending = new Map();
const requests = [];
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === 'Network.requestWillBeSent') requests.push(m.params.request);
});
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  return r.result && r.result.result ? r.result.result.value : undefined;
};
const until = async (expr, ms = 8000) => {
  for (let t = 0; t < ms; t += 100) { if (await evaluate(expr)) return true; await sleep(100); }
  return false;
};
await send('Network.enable');
await send('Page.enable');

try {
  console.log('a link from the email, in a browser that never signed in');
  const link = `${origin}/signin.html#k=${first.secret}&to=phone`;
  await send('Page.navigate', { url: link });
  check('the page loads and offers Sign in', await until(`!document.getElementById('go').hidden`), await evaluate('document.body.innerText'));
  check('the secret is off the address bar at once', await evaluate('location.href') === `${origin}/signin.html`, await evaluate('location.href'));
  check('the page says what it signs in to', await evaluate(`document.getElementById('title').textContent`) === "Sign in to the phone's screen",
    await evaluate(`document.getElementById('title').textContent`));
  check('nothing used before the press', (await ask('state')).links === 1, null);
  await evaluate(`document.getElementById('go').click()`);
  check('pressed: the phone page opens', await until(`location.pathname === '/phone.html'`), await evaluate('location.href'));
  const token = await evaluate(`localStorage.getItem('lod.token')`);
  check('with a token kept where the phone page reads it', typeof token === 'string' && token.length > 20, token);
  const hello = await evaluate(`fetch('hello', { headers: { Authorization: 'Bearer ' + localStorage.getItem('lod.token') } }).then((r) => r.json())`);
  check('a token the door knows', hello && hello.name === 'Test phone', hello);
  const st = await ask('state');
  check('the door: one browser let in, the link used up', st.tokens === 1 && st.links === 0, st);
  check('no request carried the secret in its address', !requests.some((r) => r.url.includes(first.secret)), requests.map((r) => r.url));
  const post = requests.find((r) => r.method === 'POST' && r.url.endsWith('/link'));
  check('only the POST to /link carried the secret, in its body', post && (post.postData || '').includes(first.secret), post);
  check('nothing went anywhere but the phone', requests.every((r) => r.url.startsWith(origin) || r.url.startsWith('data:')),
    requests.map((r) => r.url));

  console.log('the same link again, in a browser that has no token');
  await evaluate('localStorage.clear()');
  await send('Page.navigate', { url: link });
  await until(`!document.getElementById('go').hidden`);
  await evaluate(`document.getElementById('go').click()`);
  check('says the link was used, in plain words', await until(`document.getElementById('error').textContent.startsWith('This link was used already')`),
    await evaluate(`document.getElementById('error').textContent`));
  check('and offers the code instead', await evaluate(`!document.getElementById('code').hidden && document.getElementById('code').getAttribute('href') === 'phone.html'`), null);

  console.log('a second link, in the same tab, in a browser signed in already');
  const fresh = (await ask('link')).secret;
  await evaluate(`localStorage.setItem('lod.token', ${JSON.stringify(token)})`);
  // The tab shows signin.html already: only what follows the # changes.
  await send('Page.navigate', { url: `${origin}/signin.html#k=${fresh}&to=phone` });
  check('goes straight to the phone page', await until(`location.pathname === '/phone.html'`), await evaluate('location.href'));
  check('leaving the new link unused', (await ask('state')).links === 1, null);

  console.log('the page with no link');
  await evaluate('localStorage.clear()');
  await send('Page.navigate', { url: `${origin}/signin.html` });
  check('asks for the link from the email', await until(`document.getElementById('title').textContent === 'Open the link from the email'`),
    await evaluate(`document.getElementById('title').textContent`));

  console.log('the plain http door');
  await send('Page.navigate', { url: `http://quiet-river-fox.trycloudflare.com:${first.port}/signin.html#k=${fresh}&to=phone` });
  check('says to open the secure link, and sends nothing', await until(`document.getElementById('title').textContent === 'Open the secure link'`),
    await evaluate(`document.getElementById('title').textContent`));
  check('the link still unused', (await ask('state')).links === 1, null);
} finally {
  // Closed through the browser's own endpoint (a page's session has no Browser.close).
  try {
    const v = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json();
    const b = new WebSocket(v.webSocketDebuggerUrl);
    await new Promise((r) => b.addEventListener('open', r));
    b.send(JSON.stringify({ id: 1, method: 'Browser.close' }));
    for (let i = 0; i < 50 && browser.exitCode === null; i++) await sleep(100);
    b.close();
  } catch { /* gone already */ }
  try { ws.close(); } catch { /* closed with the browser */ }
  door.stdin.end();
  for (let i = 0; i < 50 && door.exitCode === null; i++) await sleep(100);
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* the browser's profile may linger a moment */ }
}
console.log(`\n${passed} passed, ${failed} failed`);
process.exitCode = failed ? 1 : 0;
