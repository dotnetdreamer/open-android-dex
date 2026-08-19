// End-to-end check of openandroiddex-signal: a host and two peers doing the
// exact message dance the phone and the browser do, plus /ice.
//
// Includes a minimal WebSocket client, because the point is to exercise the
// server's own frame handling rather than a library's.
const { spawn } = require('child_process');
const crypto = require('crypto');
const http = require('http');
const net = require('net');
const path = require('path');

const SERVER = path.join(__dirname, '..', 'openandroiddex-signal', 'server.js');
const PORT = 18788;

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail !== undefined ? ' — ' + JSON.stringify(detail) : ''));
  }
}

class Client {
  constructor() {
    this.messages = [];
    this.waiters = [];
    this.buffer = Buffer.alloc(0);
  }

  connect(path_) {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString('base64');
      this.socket = net.connect(PORT, '127.0.0.1', () => {
        this.socket.write(`GET ${path_} HTTP/1.1\r\nHost: localhost:${PORT}\r\n`
          + 'Upgrade: websocket\r\nConnection: Upgrade\r\n'
          + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
      });
      this.socket.on('error', reject);
      let handshakeDone = false;
      this.socket.on('data', (chunk) => {
        if (!handshakeDone) {
          const text = chunk.toString('latin1');
          const end = text.indexOf('\r\n\r\n');
          if (end < 0) return;
          const expected = crypto.createHash('sha1')
            .update(key + '258EAFA5-E914-47DA-95CA-5AB0DC85B11F').digest('base64');
          if (!text.includes(expected)) {
            reject(new Error('bad accept'));
            return;
          }
          handshakeDone = true;
          this.buffer = chunk.subarray(end + 4);
          this.drain();
          resolve();
          return;
        }
        this.buffer = Buffer.concat([this.buffer, chunk]);
        this.drain();
      });
    });
  }

  drain() {
    for (;;) {
      const b = this.buffer;
      if (b.length < 2) return;
      const opcode = b[0] & 0x0f;
      let len = b[1] & 0x7f;
      let offset = 2;
      if (len === 126) {
        if (b.length < 4) return;
        len = b.readUInt16BE(2);
        offset = 4;
      }
      if (b.length < offset + len) return;
      const payload = b.subarray(offset, offset + len);
      this.buffer = b.subarray(offset + len);
      if (opcode === 0x1) {
        const msg = JSON.parse(payload.toString('utf8'));
        const waiter = this.waiters.shift();
        if (waiter) waiter(msg);
        else this.messages.push(msg);
      }
    }
  }

  send(obj) {
    const payload = Buffer.from(JSON.stringify(obj), 'utf8');
    const mask = crypto.randomBytes(4);
    const masked = Buffer.from(payload);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i & 3];
    let header;
    if (payload.length < 126) {
      header = Buffer.from([0x81, 0x80 | payload.length]);
    } else {
      header = Buffer.alloc(4);
      header[0] = 0x81;
      header[1] = 0x80 | 126;
      header.writeUInt16BE(payload.length, 2);
    }
    this.socket.write(Buffer.concat([header, mask, masked]));
  }

  next(timeoutMs = 3000) {
    if (this.messages.length) return Promise.resolve(this.messages.shift());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for a message')),
        timeoutMs);
      this.waiters.push((msg) => {
        clearTimeout(timer);
        resolve(msg);
      });
    });
  }

  close() {
    try { this.socket.destroy(); } catch (e) { /* gone */ }
  }
}

function get(path_) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: path_ }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers }));
    }).on('error', reject);
  });
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const server = spawn(process.execPath, [SERVER], {
    env: Object.assign({}, process.env, {
      PORT: String(PORT),
      HOST: '127.0.0.1',
      TURN_URL: 'turn:turn.example.com:3478',
      TURN_SECRET: 'test-secret',
      STUN_URL: 'stun:stun.example.com:3478',
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  server.stdout.on('data', () => {});
  server.stderr.on('data', (d) => process.stderr.write('server: ' + d));

  try {
    await wait(700);

    console.log('http');
    const page = await get('/');
    check('serves the viewer page', page.status === 200 && page.body.includes('Open Android DeX'),
      page.status);
    const traversal = await get('/../../server.js');
    check('refuses a traversal', traversal.status === 404 || traversal.status === 403,
      traversal.status);
    const ice = await get('/ice');
    const servers = JSON.parse(ice.body);
    check('serves ICE servers', ice.status === 200 && servers.length === 2, servers);
    const turn = servers.find((s) => s.urls.startsWith('turn:'));
    check('mints a coturn REST credential', !!turn && /^\d+:dex$/.test(turn.username || ''),
      turn);
    const expected = crypto.createHmac('sha1', 'test-secret')
      .update(turn.username).digest('base64');
    check('the credential is the HMAC of the username', turn.credential === expected);

    console.log('rendezvous');
    const host = new Client();
    await host.connect('/signal');
    host.send({ t: 'host', room: 'room-abc' });
    check('host is accepted', (await host.next()).t === 'hosting');

    const peer = new Client();
    await peer.connect('/signal');
    peer.send({ t: 'join', room: 'room-abc' });
    const joined = await peer.next();
    check('peer is told its id', joined.t === 'joined' && !!joined.peer, joined);
    const sawJoin = await host.next();
    check('host is told a peer joined',
      sawJoin.t === 'join' && sawJoin.peer === joined.peer, sawJoin);

    host.send({ t: 'offer', peer: joined.peer, sdp: 'v=0 fake offer' });
    const offer = await peer.next();
    check('the offer reaches the peer', offer.t === 'offer' && offer.sdp === 'v=0 fake offer',
      offer);

    peer.send({ t: 'answer', sdp: 'v=0 fake answer' });
    const answer = await host.next();
    check('the answer reaches the host, stamped with the peer id',
      answer.t === 'answer' && answer.peer === joined.peer, answer);

    peer.send({ t: 'ice', candidate: 'candidate:1 1 udp', mid: '0', index: 0 });
    const candidate = await host.next();
    check('candidates are relayed', candidate.t === 'ice' && candidate.peer === joined.peer,
      candidate);

    // A peer cannot pretend to be a different one: the server stamps the id.
    peer.send({ t: 'answer', peer: 'p999', sdp: 'spoofed' });
    const spoof = await host.next();
    check('a peer cannot forge another peer id', spoof.peer === joined.peer, spoof);

    console.log('rooms');
    const stranger = new Client();
    await stranger.connect('/signal');
    stranger.send({ t: 'join', room: 'nobody-here' });
    check('joining an empty room says so', (await stranger.next()).t === 'no-host');
    stranger.close();

    const second = new Client();
    await second.connect('/signal');
    second.send({ t: 'join', room: 'room-abc' });
    const secondJoined = await second.next();
    check('a second viewer gets its own id',
      secondJoined.peer !== joined.peer, secondJoined);
    await host.next();       // the host's join notice for the second peer

    peer.close();
    const left = await host.next();
    check('the host is told when a peer goes', left.t === 'leave' && left.peer === joined.peer,
      left);

    host.close();
    const orphaned = await second.next();
    check('viewers are told when the phone goes', orphaned.t === 'host-gone', orphaned);
    second.close();

    // The phone reconnecting must take the room back rather than be refused.
    const host2 = new Client();
    await host2.connect('/signal');
    host2.send({ t: 'host', room: 'room-abc' });
    check('the phone can re-host its room', (await host2.next()).t === 'hosting');
    host2.close();

    console.log(failures ? `\n${failures} FAILED` : '\nall checks passed');
  } catch (e) {
    failures++;
    console.log('\nFAILED: ' + e.message);
  } finally {
    server.kill();
    // The port must be free again for the next run.
    await wait(200);
    process.exit(failures ? 1 : 0);
  }
})();
