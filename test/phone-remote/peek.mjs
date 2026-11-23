// One look at Remote desktop's stream: connect through an adb forward, take
// what comes for a few seconds, print it, and keep the H.264 for ffmpeg.
//
//   node test/phone-remote/peek.mjs <port> [seconds] [quality] [out.h264]
import { connect, sleep } from './client.mjs';
import { writeFileSync } from 'node:fs';

const [port, secs = '6', quality = 'medium', out = ''] = process.argv.slice(2);
const c = await connect(Number(port), { quality, wake: false });
const t0 = performance.now();
await sleep(Number(secs) * 1000);
c.close();
await sleep(300);
const span = (performance.now() - t0) / 1000;
for (const m of c.texts) {
  const at = ((m._at - t0) / 1000).toFixed(2);
  const { _at, ...rest } = m;
  if (m.t === 'stats') continue;
  console.log(at, JSON.stringify(rest));
}
const stats = c.texts.filter(m => m.t === 'stats');
for (const s of stats.slice(-3)) {
  const { _at, ...rest } = s;
  console.log('stats', JSON.stringify(rest));
}
const bytes = c.units.reduce((a, u) => a + u.bytes, 0);
console.log(`frames ${c.frames} (key ${c.keys}) in ${span.toFixed(1)} s, ${(bytes / 1024).toFixed(0)} KiB, closed ${JSON.stringify(c.closeInfo)}`);
if (out) {
  writeFileSync(out, c.h264);
  console.log('wrote', out);
}
