// Ask the phone for screen sharing over the stream ({"t":"share"}), then watch
// the source change: the slow view first, screen sharing once it is allowed.
//
//   node test/phone-remote/share.mjs <port> [seconds] [quality] [out.h264]
import { connect, sleep } from './client.mjs';
import { writeFileSync } from 'node:fs';

const [port, secs = '10', quality = 'medium', out = ''] = process.argv.slice(2);
const c = await connect(Number(port), { quality, wake: false });
const t0 = performance.now();
await sleep(500);
c.send({ t: 'share' });
await sleep(Number(secs) * 1000);
c.close();
await sleep(300);
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
console.log(`frames ${c.frames} (key ${c.keys}), ${(bytes / 1024).toFixed(0)} KiB`);
if (out) writeFileSync(out, c.h264);
