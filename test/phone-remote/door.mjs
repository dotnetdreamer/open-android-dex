// Remote desktop in a browser, through the phone's door ("Open in a browser"'s
// HTTPS server): pair with the code the phone shows, check the page is
// served, and open /phone as the page does, for a few seconds.
//
//   NODE_TLS_REJECT_UNAUTHORIZED=0 node door.mjs https://<phone>:7443 <code|token> [seconds] [quality]
//
// A six-digit argument is a code (paired here, the token printed); anything
// longer is a token from before. The phone's certificate is its own, hence
// the environment variable.
import { connect, sleep } from './client.mjs';

const [base, secret, secs = '6', quality = 'medium'] = process.argv.slice(2);
const origin = base.replace(/\/$/, '');
let token = secret;
if (/^\d{6}$/.test(secret)) {
  const r = await fetch(origin + '/pair', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin },
    body: new URLSearchParams({ code: secret }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.token) { console.log('pairing refused:', r.status, JSON.stringify(j)); process.exit(1); }
  token = j.token;
  console.log('token', token);
}
const page = await fetch(origin + '/phone.html');
console.log('phone.html', page.status, page.headers.get('content-type'), (await page.text()).length, 'bytes');
const js = await fetch(origin + '/phone.js');
console.log('phone.js', js.status, (await js.text()).length, 'bytes');

const url = origin.replace(/^http/, 'ws') + '/phone?t=' + encodeURIComponent(token);
const t0 = performance.now();
const c = await connect(0, { url, origin, quality, wake: true });
await sleep(Number(secs) * 1000);
c.close();
await sleep(300);
for (const m of c.texts) {
  if (m.t === 'stats') continue;
  const { _at, ...rest } = m;
  console.log(((m._at - t0) / 1000).toFixed(2), JSON.stringify(rest));
}
for (const s of c.texts.filter(m => m.t === 'stats').slice(-2)) {
  const { _at, ...rest } = s;
  console.log('stats', JSON.stringify(rest));
}
const bytes = c.units.reduce((a, u) => a + u.bytes, 0);
console.log(`frames ${c.frames} (key ${c.keys}), ${(bytes / 1024).toFixed(0)} KiB, closed ${JSON.stringify(c.closeInfo)}`);
