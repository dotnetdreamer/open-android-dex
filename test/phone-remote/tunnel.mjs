// Remote desktop through the tunnel, end to end: `dexremote phone` on this
// computer dials the phone by its key (direct, or through a relay), and this
// connects to the local address it prints, as the desktop app's window does.
//
//   node test/phone-remote/tunnel.mjs <dexremote.exe> <phone name> [seconds] [quality] [out.h264] [share]
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { connect, sleep } from './client.mjs';

const [exe, phone, secs = '8', quality = 'medium', out = '', share = ''] = process.argv.slice(2);
const origin = 'http://127.0.0.1:1';
// A test's own computer identity (`dexremote pair … --state DIR`), not the app's.
const state = process.env.DEXREMOTE_STATE ? ['--state', process.env.DEXREMOTE_STATE] : [];
const child = spawn(exe, ['phone', phone, '--origin', origin, ...state], { stdio: ['ignore', 'pipe', 'pipe'] });
let stderr = '';
child.stderr.on('data', d => { stderr += d; });
const url = await new Promise((resolve, reject) => {
  let buf = '';
  child.stdout.on('data', d => {
    buf += d;
    const m = buf.match(/ws:\/\/\S+/);
    if (m) resolve(m[0]);
  });
  child.on('exit', code => reject(new Error(`dexremote exited ${code}: ${stderr}`)));
  setTimeout(() => reject(new Error('no address from dexremote: ' + stderr)), 30000);
});
console.log('address', url.replace(/\/phone\/.*/, '/phone/<token>'));
const t0 = performance.now();
const c = await connect(0, { url, origin, quality, wake: false });
const opened = performance.now();
if (share) {
  await sleep(1000);
  c.send({ t: 'share' });
}
await sleep(Number(secs) * 1000);
c.close();
await sleep(500);
child.kill();
console.log(`connected in ${(opened - t0).toFixed(0)} ms`);
for (const m of c.texts) {
  if (m.t === 'stats') continue;
  const { _at, ...rest } = m;
  console.log(((m._at - t0) / 1000).toFixed(2), JSON.stringify(rest));
}
for (const s of c.texts.filter(m => m.t === 'stats').slice(-3)) {
  const { _at, ...rest } = s;
  console.log('stats', JSON.stringify(rest));
}
const bytes = c.units.reduce((a, u) => a + u.bytes, 0);
console.log(`frames ${c.frames} (key ${c.keys}), ${(bytes / 1024).toFixed(0)} KiB, closed ${JSON.stringify(c.closeInfo)}`);
const lines = stderr.trim().split(/\r?\n/).filter(Boolean);
if (lines.length) console.log('dexremote said:\n  ' + lines.slice(-6).join('\n  '));
if (out) writeFileSync(out, c.h264);
