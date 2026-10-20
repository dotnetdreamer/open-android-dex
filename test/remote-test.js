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
// Then `dexremote put`, the desktop window's file drop, against the same sshd:
// SFTP in the tunnel, Thunar's names for copies, Ctrl+C's take-back, and a
// host key other than the paired one refused. And `dexremote get`, the other
// way: the guest's Send To set up, its script run as Thunar runs it, and the
// outbox fetched into a scratch Downloads.
//
// Phase C opens the phone's desktop with `dexremote desktop`, against a
// stand-in websockify and a stand-in LinuxStream (the desktop as video, on a
// Unix socket): the refusals, a start the app asks for and one it
// fails, the password handed over, the page, the VNC socket and the video
// through the tunnel, and the requests from anywhere else that the forward
// turns away.
//
//   node test/remote-test.js          (builds the crate first)
//   KEEP=1 node test/remote-test.js   (leaves the scratch directory for a look)

const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
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

    await putPhase(again, user);
    await getPhase(again, user);

    again.send('quit');
    await Promise.race([again.exited, sleep(10000)]);
    sshd.kill();
  }

  // ── Phase C: the desktop ────────────────────────────────────────────────
  console.log('phase C — the desktop');
  await desktopPhase(daemon.peers);
}

/** Where a computer keeps its identity and phones.json, under `home`. */
function stateDir(home) {
  const base = process.platform === 'win32' ? path.join(home, 'AppData', 'Roaming')
    : process.platform === 'darwin' ? path.join(home, 'Library', 'Application Support')
      : path.join(home, '.local', 'share');
  return path.join(base, 'com.ccrstech.openandroiddex', 'remote');
}

/** Every file under `dir`, relative, with its contents' hash. */
function tree(dir) {
  const out = {};
  const walk = (d, rel) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(path.join(d, e.name), r);
      else out[r] = e.isFile() ? sha(fs.readFileSync(path.join(d, e.name))) : 'not a file';
    }
  };
  walk(dir, '');
  return out;
}

/**
 * Files onto the phone, the way a drop on the desktop window copies them:
 * `dexremote put`, SFTP inside an SSH session inside the tunnel, against the
 * real OpenSSH sshd of phase B. Its home is this machine's real one, so
 * everything lands in a scratch folder named with --to.
 */
async function putPhase(phone, user) {
  const phonesFile = path.join(stateDir(HOME), 'phones.json');
  const paired = fs.readFileSync(phonesFile, 'utf8');
  // The pairing logs in as the guest's root; the stand-in sshd can only take
  // the user it runs as.
  const asUser = JSON.parse(paired);
  for (const p of asUser.phones) p.user = user;
  fs.writeFileSync(phonesFile, JSON.stringify(asUser));

  const drop = path.join(SCRATCH, 'drop');
  const desk = path.join(SCRATCH, 'guest-desktop');
  // As the far end names it: on a Mac /var is /private/var.
  const deskShown = path.join(fs.realpathSync(SCRATCH), 'guest-desktop');
  fs.mkdirSync(path.join(drop, 'trip', 'photos', 'raw'), { recursive: true });
  // More than the writes one file keeps in flight, and not a multiple of them.
  fs.writeFileSync(path.join(drop, 'notes.txt'), crypto.randomBytes(5 * 1024 * 1024 + 123));
  fs.utimesSync(path.join(drop, 'notes.txt'), 1700000000, 1700000000);
  fs.writeFileSync(path.join(drop, 'trip', 'run.sh'), '#!/bin/sh\necho hi\n');
  fs.chmodSync(path.join(drop, 'trip', 'run.sh'), 0o755);
  // A folder of small files: they go several at a time.
  for (let i = 0; i < 24; i++) fs.writeFileSync(path.join(drop, 'trip', 'photos', `p${i}.jpg`), crypto.randomBytes(20000 + i));
  fs.writeFileSync(path.join(drop, 'trip', 'photos', 'raw', 'big.dng'), crypto.randomBytes(1536 * 1024));
  fs.writeFileSync(path.join(drop, 'trip', 'empty.txt'), '');
  fs.symlinkSync(path.join(drop, 'notes.txt'), path.join(drop, 'trip', 'link-to-notes'));
  const items = [path.join(drop, 'notes.txt'), path.join(drop, 'trip')];
  const put = (paths, extra = {}) => run(BIN, ['put', ALIAS, ...paths, '--to', desk], computerEnv(HOME, extra));

  const mark = phone.events.length;
  const first = await put(items);
  check('put copies a file and a folder onto the phone, through the tunnel', first.code === 0, first);
  check('…printing where each one landed', first.stdout.trim() === [path.join(deskShown, 'notes.txt'), path.join(deskShown, 'trip')].join('\n'), first.stdout);
  const want = tree(drop);
  delete want['trip/link-to-notes'];
  const got = fs.existsSync(desk) ? tree(desk) : {};
  const sorted = (t) => JSON.stringify(Object.entries(t).sort());
  check('…every file byte for byte, folders and all', sorted(got) === sorted(want), { want: Object.keys(want), got: Object.keys(got) });
  const landedNotes = path.join(desk, 'notes.txt');
  check('…with its time, and a script still executable',
    fs.existsSync(landedNotes) && Math.round(fs.statSync(landedNotes).mtimeMs / 1000) === 1700000000 &&
    (fs.statSync(path.join(desk, 'trip', 'run.sh')).mode & 0o111) !== 0);
  check('…and a link inside the folder left out, and said so', !fs.existsSync(path.join(desk, 'trip', 'link-to-notes')) && /1 link/.test(first.stderr), first.stderr);

  const second = await put(items);
  const third = await put(items);
  check('nothing is ever replaced: a second drop lands as Thunar names a copy',
    second.code === 0 && second.stdout.includes(path.join(deskShown, 'notes (copy 1).txt')) && second.stdout.includes(path.join(deskShown, 'trip (copy 1)')), second);
  check('…and a third counts on', third.code === 0 && fs.existsSync(path.join(desk, 'notes (copy 2).txt')) && fs.existsSync(path.join(desk, 'trip (copy 2)', 'photos', 'p23.jpg')), third);
  check('…while the first is untouched', sha(fs.readFileSync(landedNotes)) === want['notes.txt']);

  const relayed = await put([path.join(drop, 'trip', 'run.sh')], { DEXREMOTE_RELAY_ONLY: '1' });
  check('…and it works over the relay alone', relayed.code === 0 && fs.existsSync(path.join(desk, 'run.sh')), relayed);

  // Cancelled half way: the folder in progress goes, all of it.
  const slow = path.join(drop, 'slow');
  fs.mkdirSync(slow);
  fs.writeFileSync(path.join(slow, 'first.txt'), 'first');
  fs.closeSync(fs.openSync(path.join(slow, 'huge.bin'), 'w'));
  fs.truncateSync(path.join(slow, 'huge.bin'), 1024 * 1024 * 1024);
  const cancelled = await new Promise((resolve) => {
    const p = spawn(BIN, ['put', ALIAS, slow, '--to', desk], { env: computerEnv(HOME), stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';
    p.stderr.on('data', (d) => (stderr += d));
    // Once the copy is under way: the folder is there on the phone.
    const started = Date.now();
    const poll = setInterval(() => {
      if (fs.existsSync(path.join(desk, 'slow', 'huge.bin')) || Date.now() - started > 30000) {
        clearInterval(poll);
        setTimeout(() => p.kill('SIGINT'), 300);
      }
    }, 50);
    p.on('close', (code) => resolve({ code, stderr }));
  });
  check('Ctrl+C stops a copy, saying so', cancelled.code !== 0 && /Stopped after copying/.test(cancelled.stderr), cancelled);
  check('…and takes back the folder it was in the middle of', !fs.existsSync(path.join(desk, 'slow')) && !fs.readdirSync(desk).some((n) => n.startsWith('slow')), fs.readdirSync(desk));
  fs.rmSync(slow, { recursive: true, force: true });

  // A host key other than the pinned one: nothing is sent at all.
  const forged = JSON.parse(JSON.stringify(asUser));
  for (const p of forged.phones) p.hostKey = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIL4QsFWVD+4QbqEyJsOSw7nu694yirYzh9BJ9HBcDD1d';
  fs.writeFileSync(phonesFile, JSON.stringify(forged));
  const before = fs.readdirSync(desk).length;
  const refused = await put(items);
  check('put refuses a phone whose host key is not the paired one, before sending a byte',
    refused.code !== 0 && /different SSH host key/.test(refused.stderr) && fs.readdirSync(desk).length === before, refused);

  fs.writeFileSync(phonesFile, paired);
  const counts = () => phone.events.slice(mark).filter((e) => e.startsWith('sessions '));
  const deadline = Date.now() + 8000;
  while (counts().at(-1) !== 'sessions 0' && Date.now() < deadline) await sleep(250);
  check('…and every copy let go of its session on the phone', counts().length > 0 && counts().at(-1) === 'sessions 0', counts());
}

/**
 * Files the other way: what the guest's Send To queues, fetched with
 * `dexremote get` — which also writes the guest's half: the script and this
 * computer's Send To entry. The stand-in sshd's home is this machine's real
 * one, so the "guest" here is a scratch folder named with --guest-home, and
 * the script runs as Thunar would run it, with HOME pointed there.
 */
async function getPhase(phone, user) {
  const phonesFile = path.join(stateDir(HOME), 'phones.json');
  const paired = fs.readFileSync(phonesFile, 'utf8');
  const asUser = JSON.parse(paired);
  for (const p of asUser.phones) p.user = user;
  fs.writeFileSync(phonesFile, JSON.stringify(asUser));

  const guest = path.join(SCRATCH, 'guest-home');
  const downloads = path.join(SCRATCH, 'downloads');
  fs.mkdirSync(guest, { recursive: true });
  const get = () => run(BIN, ['get', ALIAS, '--guest-home', guest, '--to', downloads], computerEnv(HOME));

  const first = await get();
  check('get sets this computer up in the guest, with nothing waiting yet', first.code === 0 && /Nothing is waiting/.test(first.stderr), first);
  const script = path.join(guest, '.local', 'share', 'openandroiddex', 'oadx-send');
  check('…the send script, executable', fs.existsSync(script) && (fs.statSync(script).mode & 0o111) !== 0);
  const sendToDir = path.join(guest, '.local', 'share', 'Thunar', 'sendto');
  const entries = fs.existsSync(sendToDir) ? fs.readdirSync(sendToDir) : [];
  const entry = entries.length === 1 ? fs.readFileSync(path.join(sendToDir, entries[0]), 'utf8') : '';
  const key = (entries[0] || '').replace(/^openandroiddex-/, '').replace(/\.desktop$/, '');
  check('…and a Send To entry for this computer, which runs it', /^[0-9a-f]{16}$/.test(key) && entry.includes(`\nExec=${script} ${key} %F\n`) && /\nName=.+\n/.test(entry), { entries, entry });
  check('…and says it is looking (.seen)', fs.existsSync(path.join(guest, '.local', 'share', 'openandroiddex', 'outbox', key, '.seen')));

  // What a person picks in Thunar.
  const docs = path.join(guest, 'Documents');
  fs.mkdirSync(path.join(docs, 'photos', 'raw'), { recursive: true });
  const report = crypto.randomBytes(2 * 1024 * 1024 + 7);
  fs.writeFileSync(path.join(docs, 'report.pdf'), report);
  fs.utimesSync(path.join(docs, 'report.pdf'), 1700000000, 1700000000);
  for (let i = 0; i < 6; i++) fs.writeFileSync(path.join(docs, 'photos', `p${i}.jpg`), crypto.randomBytes(5000 + i));
  fs.writeFileSync(path.join(docs, 'photos', 'raw', 'r.dng'), crypto.randomBytes(9000));
  fs.symlinkSync(path.join(docs, 'report.pdf'), path.join(docs, 'photos', 'link-to-report'));
  fs.writeFileSync(path.join(docs, 'locked.txt'), 'nobody may read this');
  fs.chmodSync(path.join(docs, 'locked.txt'), 0o000);
  const send = (args) => spawnSync('/bin/sh', [script, ...args], { cwd: docs, env: { ...process.env, HOME: guest }, encoding: 'utf8' });

  const sent = send([key, path.join(docs, 'report.pdf'), 'photos', path.join(docs, 'gone.txt'), path.join(docs, 'locked.txt')]);
  const outbox = path.join(guest, '.local', 'share', 'openandroiddex', 'outbox', key);
  const batches = fs.readdirSync(outbox).filter((n) => !n.startsWith('.'));
  check('Send To queues the picked items as one batch, whole', sent.status === 0 && batches.length === 1 &&
    fs.readdirSync(path.join(outbox, batches[0])).length === 4, { sent, batches });

  const second = await get();
  const landed = second.stdout.trim().split('\n');
  check('get fetches them into Downloads, printing where each landed', second.code === 0 &&
    JSON.stringify(landed) === JSON.stringify([path.join(downloads, 'report.pdf'), path.join(downloads, 'photos')]), second);
  check('…byte for byte, with its time', fs.existsSync(path.join(downloads, 'report.pdf')) &&
    sha(fs.readFileSync(path.join(downloads, 'report.pdf'))) === sha(report) &&
    Math.round(fs.statSync(path.join(downloads, 'report.pdf')).mtimeMs / 1000) === 1700000000);
  const photos = fs.existsSync(path.join(downloads, 'photos')) ? tree(path.join(downloads, 'photos')) : {};
  check('…a folder with everything in it, and the link inside it left out', Object.keys(photos).length === 7 && photos['raw/r.dng'] &&
    !('link-to-report' in photos) && /1 link/.test(second.stderr), { photos: Object.keys(photos), stderr: second.stderr });
  check('…saying what was gone or unreadable in Linux, not failing on it', /2 item\(s\) were gone/.test(second.stderr), second.stderr);
  check('…and the outbox is empty after', fs.readdirSync(outbox).filter((n) => !n.startsWith('.')).length === 0, fs.readdirSync(outbox));

  send([key, path.join(docs, 'report.pdf'), path.join(docs, 'photos')]);
  const third = await get();
  check('nothing in Downloads is replaced: the same again lands as "(2)"',
    third.code === 0 && third.stdout.includes(path.join(downloads, 'report (2).pdf')) && third.stdout.includes(path.join(downloads, 'photos (2)')) &&
    sha(fs.readFileSync(path.join(downloads, 'report.pdf'))) === sha(report), third);

  const dock = send(['--open', path.join(docs, 'report.pdf')]);
  const viaDock = await get();
  check('the dock\'s "Send to computer" sends to the computer that is looking', dock.status === 0 && viaDock.stdout.includes(path.join(downloads, 'report (3).pdf')), { dock, viaDock });
  const nobody = spawnSync('/bin/sh', [script, '--open', path.join(docs, 'report.pdf')], { env: { ...process.env, HOME: path.join(SCRATCH, 'nobody') }, encoding: 'utf8' });
  check('…and refuses when no computer has ever looked', nobody.status === 2, nobody);

  fs.chmodSync(path.join(docs, 'locked.txt'), 0o600);
  fs.writeFileSync(phonesFile, paired);
}

/** A raw HTTP request through the forward; resolves with everything that came back. */
function rawRequest(port, head, body = Buffer.alloc(0)) {
  return new Promise((resolve) => {
    const s = net.connect({ port, host: '127.0.0.1' }, () => s.write(Buffer.concat([Buffer.from(head), body])));
    const got = [];
    s.on('data', (d) => got.push(d));
    s.on('end', () => resolve(Buffer.concat(got)));
    s.on('error', () => resolve(Buffer.concat(got)));
    s.setTimeout(20000, () => s.destroy());
  });
}

/** `dexremote desktop`, running: its URL once printed, and how it exited. */
function startDesktopCli(args) {
  const p = spawn(BIN, ['desktop', ...args], { env: computerEnv(HOME), stdio: ['ignore', 'pipe', 'pipe'] });
  const cli = { proc: p, stdout: '', stderr: '' };
  p.stdout.on('data', (d) => (cli.stdout += d));
  p.stderr.on('data', (d) => (cli.stderr += d));
  cli.exited = new Promise((resolve) => p.on('close', resolve));
  cli.url = async (timeoutMs = 60000) => {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const line = cli.stdout.split('\n').find((l) => l.startsWith('http://'));
      if (line) return line.trim();
      if (p.exitCode !== null) return null;
      await sleep(200);
    }
    return null;
  };
  return cli;
}

/**
 * The phone's desktop through the tunnel, against a stand-in websockify: an
 * HTTP server for the page, and an echo after a 101 for the WebSocket. The
 * test plays LinuxService again — it answers `desktop-start` the way
 * RemoteHost does, with `starting`, then `running` once the server is up.
 */
async function desktopPhase(peers) {
  const webPort = await freePort();
  const page = '<!doctype html><title>stand-in viewer</title>';
  const web = http.createServer((req, res) => {
    // A length, not chunks: websockify's file server answers the same way.
    res.writeHead(200, { 'Content-Type': 'text/html', 'Content-Length': Buffer.byteLength(page) });
    res.end(page);
  });
  web.on('upgrade', (req, socket) => {
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
    socket.pipe(socket);
  });
  // LinuxStream, standing in on a path (macOS has no abstract sockets): it
  // notes the request it was handed, upgrades it, and echoes.
  const streamSock = path.join(SCRATCH, 'linux-stream.sock');
  const streamSeen = [];
  const linuxStream = net.createServer((c) => {
    let head = Buffer.alloc(0);
    const onData = (d) => {
      head = Buffer.concat([head, d]);
      const end = head.indexOf('\r\n\r\n');
      if (end < 0) return;
      c.off('data', onData);
      const [line, ...headers] = head.slice(0, end).toString().split('\r\n');
      streamSeen.push({ line, headers });
      c.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
      if (head.length > end + 4) c.write(head.slice(end + 4));
      c.pipe(c);
    };
    c.on('data', onData);
    c.on('error', () => {});
  });
  await new Promise((r) => linuxStream.listen(streamSock, r));

  const phone = new Daemon(await freePort(), ['--desktop-port', String(webPort), '--stream-socket', streamSock]);
  phone.peers = peers;
  phone.start();
  check('a daemon with a desktop starts', !!(await phone.next('ready')), phone.events);
  check('…and reaches a relay', !!(await phone.next('online')), phone.events);
  const PASS = 'Xy7pQ2rT9kLm';

  phone.send('desktop missing - -');
  const probe = await run(BIN, ['probe', ALIAS], computerEnv(HOME));
  check('a probe says what the desktop is doing', /desktop missing/.test(probe.stdout), probe);
  const fwdPort = await freePort();
  const missing = await run(BIN, ['desktop', ALIAS, `127.0.0.1:${fwdPort}`], computerEnv(HOME));
  check('no Linux: the desktop is refused with a sentence', missing.code !== 0 && /Linux is not installed/.test(missing.stderr), missing);

  phone.send(`desktop stopped ${PASS} -`);
  const joinOnly = await run(BIN, ['desktop', '--no-start', ALIAS, `127.0.0.1:${fwdPort}`], computerEnv(HOME));
  check('a stopped desktop is not started without being asked', joinOnly.code !== 0 && /not running/.test(joinOnly.stderr), joinOnly);
  check('…and the phone was not asked to', !phone.events.some((e) => e.startsWith('desktop-start')), phone.events);

  // A start the phone cannot carry through: the app says so a moment later.
  let mark = phone.events.length;
  const doomed = startDesktopCli([ALIAS, `127.0.0.1:${fwdPort}`]);
  const asked = await phone.next('desktop-start', mark);
  check('opening a stopped desktop asks the app to start it', !!asked, phone.events.slice(mark));
  phone.send(`desktop starting ${PASS} -`);
  await sleep(1000);
  phone.send(`desktop failed ${PASS} -`);
  const doomedCode = await Promise.race([doomed.exited, sleep(30000).then(() => 'hung')]);
  check('a start that fails is reported as one', doomedCode !== 0 && doomedCode !== 'hung' && /could not start its desktop/.test(doomed.stderr), { doomedCode, stderr: doomed.stderr });

  // The real thing: asked at a size no phone should be made to draw.
  phone.send(`desktop stopped ${PASS} -`);
  mark = phone.events.length;
  const APP = 'tauri://localhost';
  const cli = startDesktopCli(['--size', '99999x900', '--video-origin', APP, ALIAS, `127.0.0.1:${fwdPort}`]);
  const start = await phone.next('desktop-start', mark);
  check('the size asked for is kept to what a phone can carry', start === 'desktop-start 3840 900', start);
  phone.send(`desktop starting ${PASS} -`);
  await new Promise((r) => web.listen(webPort, '127.0.0.1', r));
  phone.send(`desktop running ${PASS} 3840x900`);
  const url = await cli.url();
  check('the computer is told how to log in, password included', url === `http://127.0.0.1:${fwdPort}/dex.html?password=${PASS}&ctx=pc`, { url, stderr: cli.stderr });
  check('…and was told the phone was starting it', /Starting it on the phone/.test(cli.stderr), cli.stderr);

  const got = await rawRequest(fwdPort, `GET /dex.html?password=${PASS}&ctx=pc HTTP/1.1\r\nHost: 127.0.0.1:${fwdPort}\r\nConnection: close\r\n\r\n`);
  check('the viewer page comes through the tunnel', got.toString().startsWith('HTTP/1.1 200') && got.toString().endsWith(page), got.toString());
  const rebound = await rawRequest(fwdPort, `GET /dex.html HTTP/1.1\r\nHost: example.com:${fwdPort}\r\nConnection: close\r\n\r\n`);
  check('a request for another host name is refused (DNS rebinding)', rebound.toString().startsWith('HTTP/1.1 403'), rebound.toString());
  const upgrade = (origin) =>
    `GET /websockify HTTP/1.1\r\nHost: 127.0.0.1:${fwdPort}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nOrigin: ${origin}\r\n\r\n`;
  const cross = await rawRequest(fwdPort, upgrade('https://example.com'));
  check("another site's page cannot open the VNC socket", cross.toString().startsWith('HTTP/1.1 403'), cross.toString());

  const frames = crypto.randomBytes(1024 * 1024);
  const socket = await new Promise((resolve) => {
    const s = net.connect({ port: fwdPort, host: '127.0.0.1' }, () => s.write(upgrade(`http://127.0.0.1:${fwdPort}`)));
    let buf = Buffer.alloc(0);
    const onData = (d) => {
      buf = Buffer.concat([buf, d]);
      const end = buf.indexOf('\r\n\r\n');
      if (end < 0) return;
      s.off('data', onData);
      resolve({ s, head: buf.slice(0, end).toString(), rest: buf.slice(end + 4) });
    };
    s.on('data', onData);
    s.on('error', () => resolve({ s, head: '', rest: Buffer.alloc(0) }));
  });
  check("the viewer's own page opens the VNC socket", socket.head.startsWith('HTTP/1.1 101'), socket.head);
  const echoed = await new Promise((resolve) => {
    const got = [socket.rest];
    let n = socket.rest.length;
    socket.s.on('data', (d) => {
      got.push(d);
      n += d.length;
      if (n >= frames.length) resolve(Buffer.concat(got));
    });
    socket.s.on('close', () => resolve(Buffer.concat(got)));
    socket.s.write(frames);
  });
  check('1 MB through the VNC socket comes back intact', sha(echoed.slice(0, frames.length)) === sha(frames), { got: echoed.length });

  // The desktop as video: the same port, the stream op, LinuxStream's socket.
  const video = await (async () => {
    for (let i = 0; i < 50; i++) {
      const line = cli.stdout.split('\n').find((l) => l.startsWith('ws://'));
      if (line) return line.trim();
      await sleep(100);
    }
    return null;
  })();
  check('a phone with video says so, and the computer prints its address', /^ws:\/\/127\.0\.0\.1:\d+\/stream\/[0-9a-f]{32}$/.test(video || ''), { video, stdout: cli.stdout });
  const videoPath = video ? new URL(video).pathname : '/stream/none';
  const videoUpgrade = (p, origin, host = `127.0.0.1:${fwdPort}`) =>
    `GET ${p} HTTP/1.1\r\nHost: ${host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nOrigin: ${origin}\r\n\r\n`;
  const seenBefore = streamSeen.length;
  const refusedVideo = [
    await rawRequest(fwdPort, videoUpgrade('/stream/' + '0'.repeat(32), APP)),
    await rawRequest(fwdPort, videoUpgrade(videoPath, `http://127.0.0.1:${fwdPort}`)),
    await rawRequest(fwdPort, videoUpgrade(videoPath, 'https://example.com')),
    await rawRequest(fwdPort, videoUpgrade(videoPath, APP, `example.com:${fwdPort}`)),
  ].map((b) => b.toString().split('\r\n')[0]);
  check('the video refuses another token, the viewer\'s own frame, another site and another host', refusedVideo.every((l) => l.startsWith('HTTP/1.1 403')), refusedVideo);
  check('…and none of them reached the phone', streamSeen.length === seenBefore, streamSeen.slice(seenBefore));
  const vsocket = await new Promise((resolve) => {
    const s = net.connect({ port: fwdPort, host: '127.0.0.1' }, () => s.write(videoUpgrade(videoPath, APP)));
    let buf = Buffer.alloc(0);
    const onData = (d) => {
      buf = Buffer.concat([buf, d]);
      const end = buf.indexOf('\r\n\r\n');
      if (end < 0) return;
      s.off('data', onData);
      resolve({ s, head: buf.slice(0, end).toString(), rest: buf.slice(end + 4) });
    };
    s.on('data', onData);
    s.on('error', () => resolve({ s, head: '', rest: Buffer.alloc(0) }));
  });
  check("the app's own page opens the video, through the tunnel to LinuxStream's socket", vsocket.head.startsWith('HTTP/1.1 101'), vsocket.head);
  const handed = streamSeen[streamSeen.length - 1] || { line: '', headers: [] };
  check('…which is handed /stream: the token stays on the computer', handed.line === 'GET /stream HTTP/1.1', handed.line);
  check('…with the page\'s own Origin, for LinuxStream\'s check', handed.headers.includes(`Origin: ${APP}`), handed.headers);
  const vframes = crypto.randomBytes(1024 * 1024);
  const vechoed = await new Promise((resolve) => {
    const got = [vsocket.rest];
    let n = vsocket.rest.length;
    vsocket.s.on('data', (d) => {
      got.push(d);
      n += d.length;
      if (n >= vframes.length) resolve(Buffer.concat(got));
    });
    vsocket.s.on('close', () => resolve(Buffer.concat(got)));
    vsocket.s.write(vframes);
  });
  check('1 MB through the video stream comes back intact', sha(vechoed.slice(0, vframes.length)) === sha(vframes), { got: vechoed.length });
  vsocket.s.destroy();

  const counts = phone.events.slice(mark).filter((e) => e.startsWith('sessions ')).map((e) => Number(e.split(' ')[1]));
  check('the desktop is one session, however many streams it opened', counts.length > 0 && Math.max(...counts) === 1, counts);
  const status = await run(BIN, ['probe', ALIAS], computerEnv(HOME));
  check('…and the probe sees it running', /desktop running/.test(status.stdout), status);
  socket.s.destroy();

  // The daemon killed outright and started again, as an app update or the
  // end of a DeX session does it: same identity, so the same address, and
  // nobody tells the computer its connection died. The viewer's socket must
  // not hang on it — the ping hangs up, and the next request dials anew.
  const watched = await new Promise((resolve) => {
    const s = net.connect({ port: fwdPort, host: '127.0.0.1' }, () => s.write(upgrade(`http://127.0.0.1:${fwdPort}`)));
    s.once('data', () => resolve(s));
    s.on('error', () => {});
  });
  const dropped = new Promise((r) => watched.on('close', () => r(Date.now())));
  phone.proc.kill('SIGKILL');
  const killedAt = Date.now();
  const again = new Daemon(await freePort(), ['--desktop-port', String(webPort)]);
  again.peers = peers;
  again.start();
  await again.next('ready');
  again.send(`desktop running ${PASS} 3840x900`);
  const droppedAt = await Promise.race([dropped, sleep(45000).then(() => null)]);
  check('a phone whose daemon died is noticed in seconds, not minutes', droppedAt !== null && droppedAt - killedAt < 30000,
    { seconds: droppedAt && (droppedAt - killedAt) / 1000 });
  await again.next('online');
  const after = await rawRequest(fwdPort, `GET /dex.html HTTP/1.1\r\nHost: 127.0.0.1:${fwdPort}\r\nConnection: close\r\n\r\n`);
  check('…and the next request reaches the daemon that took its place', after.toString().startsWith('HTTP/1.1 200'), after.toString().slice(0, 200));

  // That daemon has no video (an older app): the stream op is refused with a
  // sentence, and a computer asking first is told so.
  const novideo = await rawRequest(fwdPort, videoUpgrade(videoPath, APP));
  check('a phone without video refuses the stream, in words', novideo.toString().startsWith('HTTP/1.1 502') && /video did not answer/.test(novideo.toString()), novideo.toString().slice(0, 200));
  // A new process has to find the phone afresh, moments after its daemon
  // started again; once more if that first dial is not answered in time.
  let older;
  for (let attempt = 0; attempt < 2; attempt++) {
    older = startDesktopCli(['--video-origin', APP, ALIAS, `127.0.0.1:${await freePort()}`]);
    await older.url();
    for (let i = 0; i < 50 && !/too old for the desktop's video/.test(older.stderr); i++) await sleep(100);
    if (!/did not answer/.test(older.stderr)) break;
    await Promise.race([older.exited, sleep(5000)]);
  }
  check('…and the computer asking first is told the app is too old for it', /too old for the desktop's video/.test(older.stderr) && !older.stdout.includes('ws://'), { stdout: older.stdout, stderr: older.stderr });
  older.proc.kill('SIGINT');
  await Promise.race([older.exited, sleep(10000)]);

  mark = again.events.length;
  cli.proc.kill('SIGINT');
  const cliCode = await Promise.race([cli.exited, sleep(10000).then(() => 'hung')]);
  check('Ctrl+C closes the desktop cleanly', cliCode === 0, { cliCode, stderr: cli.stderr });
  check('…and the phone lets go of the session at once', (await again.next('sessions 0', mark, 8000)) === 'sessions 0', again.events.slice(mark));

  again.send('quit');
  await Promise.race([again.exited, sleep(10000)]);
  web.close();
  linuxStream.close();
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
