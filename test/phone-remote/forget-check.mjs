// On a real phone: the door ends a browser's phone stream when that
// browser's token goes, and only then. Two browsers are let in with two codes
// (A and B). A opens the phone's screen (asleep: "wake":false, so the screen
// stays off), B forgets itself (/forget) and A must keep its stream, then A
// forgets itself and its stream must end at once. No touch is sent.
//
//   NODE_TLS_REJECT_UNAUTHORIZED=0 node forget-check.mjs https://<phone>:7443 <codeA> [tokenB]
// (B is let in first, by whoever runs this, since the phone makes a new code
// after each browser it lets in: B's token, then A's code)
import { connect, sleep } from './client.mjs';

const [base, codeA, givenB] = process.argv.slice(2);
const origin = base.replace(/\/$/, '');
async function pair(code) {
  const r = await fetch(origin + '/pair', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin },
    body: new URLSearchParams({ code }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.token) throw new Error(`pairing refused: ${r.status} ${JSON.stringify(j)}`);
  return j.token;
}
const forget = async (token) => (await fetch(origin + '/forget', {
  method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin },
  body: new URLSearchParams({ token }),
})).status;

const tokB = givenB || null;
const tokA = await pair(codeA);
console.log('A let in');
const a = await connect(0, { url: origin.replace(/^http/, 'ws') + '/phone?t=' + encodeURIComponent(tokA), origin, wake: false });
await a.waitFor((m) => m.t === 'video', 15000).catch(() => null);
console.log('A has the phone\'s screen:', a.texts.some((m) => m.t === 'video'), 'closed:', a.closed);
if (tokB) {
  console.log('B forgets itself:', await forget(tokB));
  await sleep(2500);
  console.log('A still open after B forgot itself:', !a.closed);
}
const t0 = performance.now();
console.log('A forgets itself:', await forget(tokA));
for (let i = 0; i < 60 && !a.closed; i++) await sleep(50);
console.log(a.closed ? `A's stream ended ${Math.round(performance.now() - t0)} ms after its Forget` : 'A\'s stream is STILL OPEN 3 s after its Forget');
console.log('last words to A:', JSON.stringify(a.texts.filter((m) => m.t === 'state').map((m) => m.state)));
try { a.close(); } catch { /* gone */ }
process.exit(a.closed ? 0 : 1);
