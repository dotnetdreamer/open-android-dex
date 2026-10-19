// End-to-end check of openandroiddex-remote with no phone: the phone's daemon,
// a computer pairing with it by code, and SSH through the tunnel — over the
// real public relays and address lookup, so this one needs the internet.
//
// The test plays LinuxService. It runs `dexremote serve`, and on every
// `pair-request` it writes peers.json and authorized_keys the way
// LinuxRemote.java does, then answers `pair-commit`. Everything on the
// computer's side runs with HOME pointed into a scratch directory, so nothing
// touches the real ~/.ssh.
//
// Phase A puts an echo server behind the daemon and checks the tunnel itself:
// an unpaired computer is turned away with a sentence, pairing works, 4 MB go
// round intact, half-close is passed on, and removing a computer on the phone
// closes its open session within seconds.
//
// Phase B (where /usr/sbin/sshd exists) puts a real OpenSSH sshd behind the
// daemon — on the host key the daemon generated, with the authorized_keys the
// pairing wrote — and logs in with the files the pairing wrote on the computer:
// a command, its exit status, scp, and all of it again over the relay alone.
//
//   node test/remote-test.js          (builds the crate first)
//   KEEP=1 node test/remote-test.js   (leaves the scratch directory for a look)

const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const CRATE = path.join(ROOT, 'openandroiddex-remote');
const BIN = path.join(CRATE, 'target', 'debug', process.platform === 'win32' ? 'dexremote.exe' : 'dexremote');
const SCRATCH = fs.mkdtempSync(path.join(os.tmpdir(), 'dexremote-e2e-'));
const PHONE_DIR = path.join(SCRATCH, 'phone');
const HOME = path.join(SCRATCH, 'home');
const STRANGER = path.join(SCRATCH, 'stranger');
const ALIAS = 'test-phone';

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail !== undefined ? ' — ' + JSON.stringify(detail) : ''));
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The environment a computer-side command runs in: its own home. */
function computerEnv(home, extra = {}) {
  return {
    ...process.env,
    HOME: home,
    USERPROFILE: home,
    XDG_DATA_HOME: path.join(home, '.local', 'share'),
    APPDATA: path.join(home, 'AppData', 'Roaming'),
    DEXREMOTE_LOG: 'warn',
    ...extra,
  };
}

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
    s.on('error', reject);
  });
}

/** Run a command to completion; resolves with code, stdout and stderr. */
function run(cmd, args, env, timeoutMs = 120000) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { env, stdio: ['pipe', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    p.stdout.on('data', (d) => (stdout += d));
    p.stderr.on('data', (d) => (stderr += d));
    p.stdin.end();
    const timer = setTimeout(() => p.kill('SIGKILL'), timeoutMs);
    p.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code, stdout, stderr });
    });
  });
}

function lines(stream, onLine) {
  let buf = '';
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    buf += chunk;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      onLine(buf.slice(0, i));
      buf = buf.slice(i + 1);
    }
  });
}

/** Write a file the way the app does: whole, then renamed over the old one. */
function writeAtomic(file, text) {
  fs.writeFileSync(file + '.tmp', text);
  fs.renameSync(file + '.tmp', file);
}

/** `dexremote serve`, driven the way LinuxService drives it. */
class Daemon {
  constructor(sshPort, extra = []) {
    this.sshPort = sshPort;
    this.extra = extra;
    this.events = [];
    this.waiters = [];
    this.peers = [];
  }

  start() {
    this.proc = spawn(
      BIN,
      ['serve', '--dir', PHONE_DIR, '--ssh-port', String(this.sshPort), '--name', 'Test Phone', ...this.extra],
      { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, DEXREMOTE_LOG: 'warn,dexremote=debug' } },
    );
    const log = fs.createWriteStream(path.join(SCRATCH, 'daemon.log'), { flags: 'a' });
    this.proc.stderr.pipe(log);
    this.exited = new Promise((resolve) => this.proc.on('close', resolve));
    lines(this.proc.stdout, (line) => this.onEvent(line));
  }

  send(line) {
    this.proc.stdin.write(line + '\n');
  }

  onEvent(line) {
    this.events.push(line);
    const w = line.split(' ');
    if (w[0] === 'pair-request') {
      // What LinuxRemote.addPeer does: the computer goes into peers.json and
      // its key into the guest's authorized_keys, and only then is it paired.
      const [, id, name64, osName, key64] = w;
      const ssh = Buffer.from(key64, 'base64url').toString();
      this.peers = this.peers.filter((p) => p.id !== id);
      this.peers.push({ id, name: Buffer.from(name64, 'base64url').toString(), os: osName, ssh, added: Date.now() });
      this.writePeers();
      this.send('pair-commit ' + id);
    }
    for (const w of this.waiters.splice(0)) w();
  }

  writePeers() {
    writeAtomic(path.join(PHONE_DIR, 'peers.json'), JSON.stringify({ v: 1, peers: this.peers }));
    writeAtomic(path.join(PHONE_DIR, 'ssh', 'authorized_keys'), this.peers.map((p) => p.ssh + '\n').join(''));
  }

  /** The first event starting with `prefix` after index `from`. */
  async next(prefix, from = 0, timeoutMs = 60000) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const hit = this.events.slice(from).find((e) => e === prefix || e.startsWith(prefix + ' '));
      if (hit) return hit;
      if (Date.now() > deadline) return null;
      await new Promise((r) => {
        this.waiters.push(r);
        setTimeout(r, 250);
      });
    }
  }
}

function pairingCode() {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  return [...crypto.randomBytes(8)].map((b) => alphabet[b & 31]).join('');
}

/** Connect to a local port, retrying while whatever listens there comes up. */
async function connectSoon(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      return await new Promise((resolve, reject) => {
        const s = net.connect({ port, host: '127.0.0.1', allowHalfOpen: true });
        s.once('connect', () => resolve(s));
        s.once('error', reject);
      });
    } catch (e) {
      if (Date.now() > deadline) throw e;
      await sleep(300);
    }
  }
}

/** Send `data`, half-close, and collect everything that comes back. */
function roundTrip(sock, data) {
  return new Promise((resolve) => {
    const got = [];
    sock.on('data', (d) => got.push(d));
    sock.on('end', () => resolve(Buffer.concat(got)));
    sock.on('error', () => resolve(Buffer.concat(got)));
    sock.end(data);
  });
}

const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');

async function main() {
  console.log('building dexremote…');
  const build = spawnSync('cargo', ['build', '--quiet'], { cwd: CRATE, stdio: 'inherit' });
  if (build.status !== 0) {
    console.log('  FAIL cargo build');
    process.exit(1);
  }
  fs.mkdirSync(PHONE_DIR, { recursive: true });
  fs.mkdirSync(HOME, { recursive: true });
  fs.mkdirSync(STRANGER, { recursive: true });

  // ── Phase A: the tunnel, against an echo server ─────────────────────────
  console.log('phase A — the tunnel');
  const echo = net.createServer({ allowHalfOpen: true }, (s) => s.pipe(s));
  await new Promise((r) => echo.listen(0, '127.0.0.1', r));
  const daemon = new Daemon(echo.address().port);
  daemon.start();

  const ready = await daemon.next('ready');
  check('the daemon announces its id and host key', !!ready, daemon.events);
  if (!ready) throw new Error('daemon never became ready');
  const [, phoneId, host64] = ready.split(' ');
  const hostLine = Buffer.from(host64, 'base64url').toString();
  const pubFile = fs.readFileSync(path.join(PHONE_DIR, 'ssh', 'ssh_host_ed25519_key.pub'), 'utf8').trim();
  check('the announced host key is the one on disk', hostLine === pubFile, { hostLine, pubFile });
  daemon.send('ssh up');
  check('it reaches a relay', !!(await daemon.next('online')), daemon.events);

  const stranger = await run(BIN, ['proxy', phoneId], computerEnv(STRANGER));
  check('an unpaired computer is turned away', stranger.code === 255, stranger);
  check('…with a sentence saying why', /does not know this computer/.test(stranger.stderr), stranger.stderr);

  const code = pairingCode();
  const mark = daemon.events.length;
  daemon.send(`pair ${code} 300`);
  check('pairing opens', !!(await daemon.next('pair-listening', mark)), daemon.events);
  const display = code.slice(0, 4) + '-' + code.slice(4).toLowerCase();
  const paired = await run(BIN, ['pair', display], computerEnv(HOME));
  check('pairing by code succeeds (typed lower-case, with a dash)', paired.code === 0, paired);
  check('…and prints how to connect', paired.stdout.trim() === `ssh ${ALIAS}`, paired.stdout);
  check('the phone reports the pairing', !!(await daemon.next('paired', mark)), daemon.events);
  check('the code is spent afterwards', !!(await daemon.next('pair-closed', mark)), daemon.events);
  const pcId = daemon.peers[0] && daemon.peers[0].id;

  // A code reopened after a restart keeps the deadline it was first shown
  // with, and ends then by the wall clock — not by the daemon's own timer,
  // which a sleeping phone stops.
  const now = () => Math.floor(Date.now() / 1000);
  const brief = pairingCode();
  const markBrief = daemon.events.length;
  daemon.send(`pair ${brief} 600 ${now() + 8}`);
  const briefOpen = await daemon.next('pair-listening', markBrief);
  check('a reopened code listens only until its first deadline',
    !!briefOpen && Number(briefOpen.split(' ')[2]) <= now() + 8, briefOpen);
  const briefEnd = await daemon.next('pair-failed', markBrief, 20000);
  check('…and expires at it', !!briefEnd && /^pair-failed expired \S+$/.test(briefEnd), daemon.events.slice(markBrief));
  const tooLate = await run(BIN, ['pair', brief], computerEnv(STRANGER));
  check('…after which it pairs nobody', tooLate.code !== 0, tooLate);
  const markGone = daemon.events.length;
  daemon.send(`pair ${pairingCode()} 600 ${now() - 5}`);
  check('a code whose deadline has passed is not opened at all',
    (await daemon.next('pair-failed', markGone, 10000)) === 'pair-failed expired', daemon.events.slice(markGone));

  const ssh = path.join(HOME, '.ssh');
  const conf = fs.readFileSync(path.join(ssh, 'openandroiddex.conf'), 'utf8');
  check('the computer has a Host block for the phone', conf.includes(`Host ${ALIAS}\n`), conf);
  check('…whose ProxyCommand dials the phone by id', conf.includes(` proxy ${phoneId}\n`), conf);
  const known = fs.readFileSync(path.join(ssh, 'openandroiddex_known_hosts'), 'utf8');
  check('the phone host key is pinned from pairing', known.includes(hostLine.split(' ').slice(0, 2).join(' ')), known);
  check('~/.ssh/config includes it', /^Include openandroiddex\.conf$/m.test(fs.readFileSync(path.join(ssh, 'config'), 'utf8')));

  const probe = await run(BIN, ['probe', ALIAS], computerEnv(HOME));
  check('the paired computer can probe the phone', probe.code === 0 && /reachable/.test(probe.stdout), probe);
  check('…and the path is direct between two endpoints on one machine', /direct/.test(probe.stdout), probe.stdout);
  const relayed = await run(BIN, ['probe', ALIAS], computerEnv(HOME, { DEXREMOTE_RELAY_ONLY: '1' }));
  check('relay-only works and says so', relayed.code === 0 && /relayed/.test(relayed.stdout), relayed);

  const fwdPort = await freePort();
  const fwd = spawn(BIN, ['forward', ALIAS, `127.0.0.1:${fwdPort}`], { env: computerEnv(HOME), stdio: ['ignore', 'ignore', 'pipe'] });
  let fwdLog = '';
  fwd.stderr.on('data', (d) => (fwdLog += d));

  const payload = crypto.randomBytes(4 * 1024 * 1024);
  const back = await roundTrip(await connectSoon(fwdPort), payload);
  check('4 MB through the tunnel come back intact', sha(back) === sha(payload), { sent: payload.length, got: back.length });

  // An idle session, then the computer is removed on the phone.
  const idle = await connectSoon(fwdPort);
  // Flowing, and 'end' counts: a paused socket with unread data never emits
  // either, and with allowHalfOpen a remote end does not become a 'close'.
  const idleClosed = new Promise((r) => {
    idle.on('end', r);
    idle.on('close', r);
  });
  idle.on('data', () => {});
  idle.on('error', () => {});
  idle.write('still here?');
  await sleep(1500);
  const mark2 = daemon.events.length;
  daemon.peers = [];
  daemon.writePeers();
  const closedInTime = await Promise.race([idleClosed.then(() => true), sleep(8000).then(() => false)]);
  check('removing a computer closes its open session at once', closedInTime);
  check('…and the phone says so', !!(await daemon.next('revoked', mark2, 5000)), daemon.events.slice(mark2));
  const refused = await roundTrip(await connectSoon(fwdPort), Buffer.from('anyone?'));
  check('…and it cannot get back in', refused.length === 0, refused.toString());

  // Put it back for phase B, the way a second pairing would.
  daemon.peers = [{ id: pcId, name: 'test', os: 'macos', ssh: fs.readFileSync(path.join(ssh, 'openandroiddex_ed25519.pub'), 'utf8').trim(), added: Date.now() }];
  daemon.writePeers();
  fwd.kill();

  daemon.send('quit');
  const quitCode = await Promise.race([daemon.exited, sleep(10000).then(() => 'hung')]);
  check('quit stops the daemon cleanly', quitCode === 0, quitCode);
  check('…and takes its status file with it', !fs.existsSync(path.join(PHONE_DIR, 'status.json')));
  echo.close();

  // ── Phase B: real SSH ───────────────────────────────────────────────────
  const SSHD = '/usr/sbin/sshd';
  if (!fs.existsSync(SSHD)) {
    console.log('phase B skipped — no ' + SSHD + ' here');
  } else {
    console.log('phase B — OpenSSH through the tunnel');
    const sshPort = await freePort();
    const sshdConfig = path.join(SCRATCH, 'sshd_config');
    fs.writeFileSync(
      sshdConfig,
      [
        `Port ${sshPort}`,
        'ListenAddress 127.0.0.1',
        // The key the daemon made, which is what the guest's sshd uses.
        `HostKey ${path.join(PHONE_DIR, 'ssh', 'ssh_host_ed25519_key')}`,
        `AuthorizedKeysFile ${path.join(PHONE_DIR, 'ssh', 'authorized_keys')}`,
        `PidFile ${path.join(SCRATCH, 'sshd.pid')}`,
        'UsePAM no',
        'PasswordAuthentication no',
        'KbdInteractiveAuthentication no',
        'StrictModes no',
        'Subsystem sftp internal-sftp',
        '',
      ].join('\n'),
    );
    const sshd = spawn(SSHD, ['-D', '-e', '-f', sshdConfig], { stdio: ['ignore', 'ignore', 'pipe'] });
    sshd.stderr.pipe(fs.createWriteStream(path.join(SCRATCH, 'sshd.log')));
    await connectSoon(sshPort).then((s) => s.destroy());

    // Given DNS servers, as LinuxService always gives them on a phone: the
    // daemon then resolves through its own resolver, the one `net` rebuilds.
    const again = new Daemon(sshPort, ['--dns', '1.1.1.1,8.8.8.8']);
    again.peers = daemon.peers;
    again.start();
    const ready2 = await again.next('ready');
    check('a restarted daemon keeps its identity', ready2 && ready2.split(' ')[1] === phoneId, ready2);
    check('…and reaches a relay through its own resolver', !!(await again.next('online')), again.events);
    again.send('ssh up');

    const user = os.userInfo().username;
    // ~ and relative Includes resolve against the passwd home, not $HOME, so
    // the test points ssh at the generated files rather than at ~/.ssh/config.
    const sshArgs = [
      '-F', path.join(ssh, 'openandroiddex.conf'),
      '-o', 'IdentityFile=' + path.join(ssh, 'openandroiddex_ed25519'),
      '-o', 'UserKnownHostsFile=' + path.join(ssh, 'openandroiddex_known_hosts'),
      '-o', 'BatchMode=yes',
      '-o', 'User=' + user,
    ];
    const hello = await run('ssh', [...sshArgs, ALIAS, 'echo hello-from-the-phone'], computerEnv(HOME));
    check('ssh logs in through the tunnel, host key verified, no prompt', hello.code === 0 && hello.stdout.trim() === 'hello-from-the-phone', hello);
    const status = await run('ssh', [...sshArgs, ALIAS, 'exit 7'], computerEnv(HOME));
    check('the remote exit status comes back', status.code === 7, status);

    const file = path.join(SCRATCH, 'upload.bin');
    const copy = path.join(SCRATCH, 'uploaded.bin');
    fs.writeFileSync(file, crypto.randomBytes(3 * 1024 * 1024));
    const scp = await run('scp', [...sshArgs, file, `${ALIAS}:${copy}`], computerEnv(HOME));
    check('scp copies a file through it', scp.code === 0 && fs.existsSync(copy) && sha(fs.readFileSync(copy)) === sha(fs.readFileSync(file)), scp);

    const viaRelay = await run('ssh', [...sshArgs, ALIAS, 'echo over-the-relay'], computerEnv(HOME, { DEXREMOTE_RELAY_ONLY: '1' }));
    check('…and all of it works over the relay alone', viaRelay.code === 0 && viaRelay.stdout.trim() === 'over-the-relay', viaRelay);

    // What a phone walking from Wi-Fi onto mobile data sends: new servers.
    again.send('net 9.9.9.9,1.1.1.1');
    await sleep(2000);
    const daemonLog = fs.readFileSync(path.join(SCRATCH, 'daemon.log'), 'utf8');
    check('a network change brings the new DNS servers in', /DNS servers now \[9\.9\.9\.9, 1\.1\.1\.1\]/.test(daemonLog), daemonLog.slice(-2000));
    const afterNet = await run('ssh', [...sshArgs, ALIAS, 'echo after-the-change'], computerEnv(HOME, { DEXREMOTE_RELAY_ONLY: '1' }));
    check('…and the phone is still reachable after it', afterNet.code === 0 && afterNet.stdout.trim() === 'after-the-change', afterNet);

    // Several sessions at once — a terminal, VS Code and an scp is the normal
    // case. Each ProxyCommand runs its own endpoint; were they to share the
    // computer's key, the relay would only route to the newest of them, which
    // is why this is also run over the relay alone.
    for (const [label, extra] of [['direct', {}], ['over the relay alone', { DEXREMOTE_RELAY_ONLY: '1' }]]) {
      const many = await Promise.all([1, 2, 3].map((n) =>
        run('ssh', [...sshArgs, ALIAS, `sleep 2; echo session-${n}`], computerEnv(HOME, extra))));
      check(`three sessions at once, ${label}`,
        many.every((r, i) => r.code === 0 && r.stdout.trim() === `session-${i + 1}`), many);
    }

    const wrongHost = path.join(SCRATCH, 'wrong_known_hosts');
    fs.writeFileSync(wrongHost, known.replace(/ssh-ed25519 \S+/, 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL4QsFWVD+4QbqEyJsOSw7nu694yirYzh9BJ9HBcDD1d'));
    const mitm = await run('ssh', [...sshArgs.slice(0, 4), '-o', 'UserKnownHostsFile=' + wrongHost, ...sshArgs.slice(6), ALIAS, 'true'], computerEnv(HOME));
    check('a host key other than the paired one is refused', mitm.code !== 0 && /HOST IDENTIFICATION HAS CHANGED|Host key verification failed/.test(mitm.stderr), mitm.stderr);

    again.send('quit');
    await Promise.race([again.exited, sleep(10000)]);
    sshd.kill();
  }
}

main()
  .catch((e) => {
    failures++;
    console.log('  FAIL ' + (e && e.stack ? e.stack : e));
  })
  .finally(() => {
    if (process.env.KEEP === '1') {
      console.log('scratch kept at ' + SCRATCH);
    } else {
      fs.rmSync(SCRATCH, { recursive: true, force: true });
    }
    console.log(failures === 0 ? '\nall checks passed' : `\n${failures} check(s) FAILED`);
    process.exit(failures === 0 ? 0 : 1);
  });
