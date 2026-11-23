// On a real phone: a browser's phone stream must end when the phone's own
// "Sign them out" is pressed (the token is checked as a stream opens, and a
// stream outlives that check). Lets a browser in with the code, opens the
// phone's screen, prints READY, and waits up to <seconds> for the stream to
// end: then prints when, and what the phone said last. The button is pressed
// by whoever runs this.
//
//   NODE_TLS_REJECT_UNAUTHORIZED=0 node signout-check.mjs https://<phone>:7443 <code> [seconds]
import { connect, sleep } from './client.mjs';

const [base, code, secs = '120'] = process.argv.slice(2);
const origin = base.replace(/\/$/, '');
const r = await fetch(origin + '/pair', {
  method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin },
  body: new URLSearchParams({ code }),
});
const j = await r.json().catch(() => ({}));
if (!j.token) { console.log('not let in:', r.status, JSON.stringify(j)); process.exit(1); }
const c = await connect(0, { url: origin.replace(/^http/, 'ws') + '/phone?t=' + encodeURIComponent(j.token), origin, wake: false });
await c.waitFor((m) => m.t === 'video', 15000).catch(() => null);
console.log(`READY ${new Date().toISOString().slice(11, 23)} (the phone's screen: ${c.texts.some((m) => m.t === 'video')})`);
const t0 = Date.now();
while (!c.closed && Date.now() - t0 < Number(secs) * 1000) await sleep(50);
console.log(c.closed ? `CLOSED ${new Date().toISOString().slice(11, 23)}` : `STILL OPEN after ${secs} s`);
console.log('states:', JSON.stringify(c.texts.filter((m) => m.t === 'state').map((m) => m.state)));
// The token itself must be gone too: /hello no longer names the phone for it.
const h = await fetch(origin + '/hello', { headers: { Authorization: `Bearer ${j.token}` } }).then((x) => x.json()).catch(() => ({}));
console.log('token still known to the phone:', h.name !== undefined);
process.exit(c.closed ? 0 : 1);
