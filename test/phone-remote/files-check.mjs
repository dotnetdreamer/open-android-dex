// On a real phone: Remote desktop's Files through the door, in one test
// folder of its own (/Download/oadx-files-test) and nowhere else. A browser is
// let in with a code (or a token from an earlier run), then:
//
//   info             the phone's places, or the reason there are none
//   mkdir            /Download/oadx-files-test (refused to start if it is there)
//   put              a file of random bytes (3 MiB, or --mib), in pieces with --piece
//                    (as the phone's page sends a big one), then the same again (said "same")
//   list             the folder holds it, at its size
//   get              the bytes come back as they went
//   adb (optional)   the phone's own disk has those bytes
//   tunnel (opt.)    the same list through the Cloudflare address, and the page's script
//   delete           the folder goes, and /Download no longer shows it
//
//   NODE_TLS_REJECT_UNAUTHORIZED=0 node files-check.mjs https://<phone>:7443 <code|token>
//       [--expect-noaccess] [--adb <serial>] [--tunnel https://<name>.trycloudflare.com]
//       [--mib N] [--piece MiB]
//
// The first address may be the tunnel's itself: everything then goes through
// Cloudflare, the code too.
//
// --expect-noaccess: the phone has no "All files access" yet, so info must say
// "noaccess" and nothing else is tried.
import { createHash, randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  if (i < 0) return null;
  const v = args[i + 1];
  args.splice(i, 2);
  return v;
};
const noaccess = args.includes('--expect-noaccess');
if (noaccess) args.splice(args.indexOf('--expect-noaccess'), 1);
const serial = flag('--adb');
const tunnel = flag('--tunnel');
const mib = Number(flag('--mib') || 3);
const pieceMib = Number(flag('--piece') || 0);
const [base, codeOrToken] = args;
if (!base || !codeOrToken) {
  console.log('usage: files-check.mjs https://<phone>:7443 <code|token> [--expect-noaccess] [--adb <serial>] [--tunnel <url>]');
  process.exitCode = 2;
} else {
  process.exitCode = await main();
}

async function main() {
  const origin = base.replace(/\/$/, '');
  const DIR = '/Download/oadx-files-test';
  const NAME = 'hello.bin';
  let failed = 0;
  const check = (ok, what) => {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`);
    if (!ok) failed++;
  };

  let token = codeOrToken;
  if (/^\d{6}$/.test(codeOrToken)) {
    const r = await fetch(origin + '/pair', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Origin: origin },
      body: new URLSearchParams({ code: codeOrToken }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.token) throw new Error(`pairing refused: ${r.status} ${JSON.stringify(j)}`);
    token = j.token;
    console.log('let in; token for a later run:', token);
  }

  const auth = { Authorization: 'Bearer ' + token };
  async function call(method, op, query, body, extra = {}, at = origin) {
    const url = at + '/pfs/' + op + (query ? '?' + new URLSearchParams(query) : '');
    const headers = { ...auth, ...extra };
    if (method !== 'GET') headers.Origin = at;
    const r = await fetch(url, { method, headers, body });
    const buf = Buffer.from(await r.arrayBuffer());
    let json = null;
    try { json = JSON.parse(buf.toString('utf8')); } catch { /* bytes */ }
    return { status: r.status, json, buf };
  }
  const form = (o) => ({ body: new URLSearchParams(o).toString(), type: { 'Content-Type': 'application/x-www-form-urlencoded' } });

  const info = await call('GET', 'info');
  if (noaccess) {
    check(info.status === 503 && info.json?.error === 'noaccess', `info without All files access: ${info.status} ${info.buf.toString('utf8')}`);
    return failed ? 1 : 0;
  }
  check(info.status === 200 && info.json?.home === '/', `info: ${info.status} places ${JSON.stringify((info.json?.places || []).map((p) => p.id))}`);

  const top = await call('GET', 'list', { path: '/Download' });
  check(top.status === 200, `list /Download: ${top.status}, ${top.json?.entries?.length} entries`);
  if (top.json?.entries?.some((e) => e.n === 'oadx-files-test')) {
    console.log(`${DIR} is already there: not mine to touch, so nothing more is done`);
    return 1;
  }

  const f = form({ path: DIR });
  const mk = await call('POST', 'mkdir', null, f.body, f.type);
  check(mk.status === 200 && mk.json?.path === DIR, `mkdir: ${mk.status} ${mk.buf.toString('utf8')}`);

  const bytes = randomBytes(Math.round(mib * 1024 * 1024));
  const sha = createHash('sha256').update(bytes).digest('hex');
  const mtime = Date.now();
  const q = { path: `${DIR}/${NAME}`, size: bytes.length, off: 0, mtime, on: 'fail' };
  const step = pieceMib > 0 ? Math.round(pieceMib * 1024 * 1024) : bytes.length;
  const t0 = performance.now();
  let put;
  let pieces = 0;
  for (let off = 0; off < bytes.length; off += step) {
    put = await call('PUT', 'put', { ...q, off }, bytes.subarray(off, Math.min(bytes.length, off + step)), { 'Content-Type': 'application/octet-stream' });
    pieces++;
    if (put.status !== 200 || (off + step < bytes.length && put.json?.have !== Math.min(bytes.length, off + step))) break;
  }
  const ms = performance.now() - t0;
  check(put.status === 200 && put.json?.done === true && put.json?.path === `${DIR}/${NAME}`,
    `put ${mib} MiB in ${pieces} piece(s): ${put.status} ${put.buf.toString('utf8')} (${Math.round(ms)} ms, ${(bytes.length / 1048576 / (ms / 1000)).toFixed(1)} MiB/s)`);
  const again = await call('PUT', 'put', q, bytes, { 'Content-Type': 'application/octet-stream' });
  check(again.status === 200 && again.json?.same === true, `put the same again: ${again.status} ${again.buf.toString('utf8')}`);

  const ls = await call('GET', 'list', { path: DIR });
  const entry = ls.json?.entries?.find((e) => e.n === NAME);
  check(ls.status === 200 && ls.json?.entries?.length === 1 && entry?.s === bytes.length,
    `list ${DIR}: ${ls.status} ${JSON.stringify(ls.json?.entries?.map((e) => [e.n, e.s]))}`);

  const tg = performance.now();
  const get = await call('GET', 'get', { path: `${DIR}/${NAME}` });
  const getMs = performance.now() - tg;
  const gotSha = createHash('sha256').update(get.buf).digest('hex');
  check(get.status === 200 && gotSha === sha, `get: ${get.status} ${get.buf.length} bytes, same bytes: ${gotSha === sha} (${Math.round(getMs)} ms, ${(get.buf.length / 1048576 / (getMs / 1000)).toFixed(1)} MiB/s)`);

  if (serial) {
    const onDisk = execFileSync('adb', ['-s', serial, 'shell', `sha256sum /sdcard${DIR}/${NAME}`], { encoding: 'utf8' }).trim().split(/\s+/)[0];
    check(onDisk === sha, `the phone's own disk: ${onDisk.slice(0, 16)}… ${onDisk === sha ? 'same' : 'DIFFERENT'}`);
  }

  if (tunnel) {
    const at = tunnel.replace(/\/$/, '');
    const tl = await call('GET', 'list', { path: DIR }, undefined, {}, at);
    check(tl.status === 200 && tl.json?.entries?.[0]?.n === NAME, `list through the tunnel: ${tl.status} ${JSON.stringify(tl.json?.entries?.map((e) => e.n))}`);
    const js = await fetch(at + '/phone-files.js');
    check(js.status === 200, `the page's Files script through the tunnel: ${js.status}`);
    const linux = await fetch(at + '/fs/info', { headers: auth });
    check(linux.status === 403, `Linux's files through the tunnel: ${linux.status} (403 wanted)`);
  }

  const d = form({ p: DIR });
  const del = await call('POST', 'delete', null, d.body, d.type);
  check(del.status === 200 && JSON.stringify(del.json?.deleted) === JSON.stringify([DIR]), `delete: ${del.status} ${del.buf.toString('utf8')}`);
  const after = await call('GET', 'list', { path: '/Download' });
  check(after.status === 200 && !after.json?.entries?.some((e) => e.n === 'oadx-files-test'), `/Download without it: ${after.status}`);
  if (serial) {
    const gone = execFileSync('adb', ['-s', serial, 'shell', `ls /sdcard/Download | grep -c '^oadx-files-test$' || true`], { encoding: 'utf8' }).trim();
    check(gone === '0', `the phone's own disk no longer has it: ${gone}`);
  }
  console.log(failed ? `${failed} FAILED` : 'all passed');
  return failed ? 1 : 0;
}
