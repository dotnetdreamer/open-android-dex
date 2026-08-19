#!/usr/bin/env node
/*
 * openandroiddex-signal — the rendezvous.
 *
 * A phone on mobile data has no address anyone can open: carrier-grade NAT
 * gives it no inbound path, there is no port on a router to forward, and the
 * launcher ships no tunnel client. What it can always do is dial OUT. So it
 * dials out to this, announces a room, and waits. A browser opens the same
 * room here, the two exchange one offer and one answer through it, and from
 * that moment the video, the input and the files all go peer-to-peer — or
 * through TURN, if neither end can be reached directly.
 *
 * This server is therefore deliberately small and deliberately ignorant:
 *
 *   - It relays SDP and ICE candidates and nothing else. A few kilobytes per
 *     session.
 *   - It never sees the access code. The phone checks that over the data
 *     channel, after the connection exists, so a compromised relay still
 *     cannot look at anyone's screen.
 *   - It never sees the room in a request line. The browser gets the room from
 *     the URL fragment, which is not sent to servers, and hands it over on the
 *     socket instead — so the room does not end up in an access log.
 *
 * It also serves the viewer page, because in a rendezvous session there is
 * nowhere else for the page to come from, and /ice, because the browser needs
 * ICE servers before it can answer and this box is the one that knows them.
 *
 * No dependencies, on purpose: it is meant to be dropped next to an existing
 * coturn on a box someone already runs, and `npm install` on a server is a
 * thing people are right to be wary of.
 *
 *   node server.js
 *
 * Environment:
 *   PORT          listen port                       (default 8788)
 *   HOST          bind address                      (default 0.0.0.0)
 *   PUBLIC_DIR    where the viewer page lives       (default: the launcher's assets)
 *   STUN_URL      comma-separated STUN urls         (default Google's)
 *   TURN_URL      e.g. turn:turn.example.com:3478
 *   TURN_SECRET   coturn's static-auth-secret       (preferred: short-lived credentials)
 *   TURN_USER     long-term username                (used only without TURN_SECRET)
 *   TURN_PASS     long-term password
 *   TURN_TTL      seconds a REST credential lasts   (default 86400)
 *
 * Put it behind a TLS terminator (the same nginx or caddy that fronts
 * everything else). A browser will refuse a wss:// upgrade from an https page
 * otherwise, and the phone verifies the certificate hostname.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || 8788);
const HOST = process.env.HOST || '0.0.0.0';
const TURN_TTL = Number(process.env.TURN_TTL || 86400);

/** Where the page comes from: a deployed copy, or the launcher's own assets. */
const PUBLIC_DIR = process.env.PUBLIC_DIR || (() => {
  const beside = path.join(__dirname, 'public');
  if (fs.existsSync(path.join(beside, 'index.html'))) return beside;
  return path.join(__dirname, '..', 'openandroiddex-launcher', 'app', 'src',
    'main', 'assets', 'web');
})();

/** Rooms are cheap but not free, and an open relay is somebody's botnet. */
const MAX_ROOMS = 200;
const MAX_PEERS_PER_ROOM = 8;
const MAX_MESSAGE = 64 * 1024;
/** A socket that has not said anything in this long is not connected. */
const IDLE_MS = 120_000;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

/* ── ICE ─────────────────────────────────────────────────────────────── */

/**
 * The servers a browser should use.
 *
 * With TURN_SECRET this mints coturn's REST-style credentials: the username is
 * an expiry timestamp and the password is its HMAC under the shared secret, so
 * what leaves this box stops working on its own and nothing long-lived is ever
 * handed to a browser. That is why TURN_SECRET is the documented path and
 * TURN_USER/TURN_PASS only the fallback.
 */
function iceServers() {
  const servers = [];
  const stun = process.env.STUN_URL || 'stun:stun.l.google.com:19302';
  for (const url of stun.split(',').map((s) => s.trim()).filter(Boolean)) {
    servers.push({ urls: url });
  }
  const turn = (process.env.TURN_URL || '').trim();
  if (turn) {
    if (process.env.TURN_SECRET) {
      const username = `${Math.floor(Date.now() / 1000) + TURN_TTL}:dex`;
      const credential = crypto.createHmac('sha1', process.env.TURN_SECRET)
        .update(username).digest('base64');
      servers.push({ urls: turn, username, credential });
    } else if (process.env.TURN_USER) {
      servers.push({
        urls: turn,
        username: process.env.TURN_USER,
        credential: process.env.TURN_PASS || '',
      });
    } else {
      servers.push({ urls: turn });
    }
  }
  return servers;
}

/* ── static + /ice ───────────────────────────────────────────────────── */

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/ice') {
    const body = JSON.stringify(iceServers());
    res.writeHead(200, {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
    });
    res.end(body);
    return;
  }
  let name = url.pathname === '/' ? '/index.html' : url.pathname;
  // The only path traversal defence that is worth anything: resolve, then
  // check the result is still inside the directory.
  const file = path.resolve(PUBLIC_DIR, '.' + name);
  if (!file.startsWith(path.resolve(PUBLIC_DIR))) {
    res.writeHead(403).end('no');
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(data);
  });
});

/* ── the smallest WebSocket server that is still correct ─────────────── */

const GUID = '258EAFA5-E914-47DA-95CA-5AB0DC85B11F';

class Conn {
  constructor(socket) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.onMessage = () => {};
    this.onClose = () => {};
    this.closed = false;
    this.lastSeen = Date.now();
    socket.on('data', (chunk) => this.feed(chunk));
    socket.on('error', () => this.close());
    socket.on('close', () => this.close());
    // 'end' as well, and it is the one that actually fires. A socket taken
    // over from an HTTP upgrade is left half-open when the far end goes: it
    // emits 'end' on the FIN and never emits 'close' until this side is
    // destroyed too — so listening only for 'close' means a viewer that closed
    // its tab is never reported gone, and its room entry lives forever.
    socket.on('end', () => this.close());
  }

  feed(chunk) {
    this.lastSeen = Date.now();
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      const frame = this.readFrame();
      if (!frame) return;
      if (frame.opcode === 0x8) {
        this.close();
        return;
      }
      if (frame.opcode === 0x9) {
        this.writeFrame(0xA, frame.payload);
        continue;
      }
      if (frame.opcode === 0x1) {
        this.onMessage(frame.payload.toString('utf8'));
      }
    }
  }

  readFrame() {
    const b = this.buffer;
    if (b.length < 2) return null;
    const opcode = b[0] & 0x0f;
    const masked = (b[1] & 0x80) !== 0;
    let len = b[1] & 0x7f;
    let offset = 2;
    if (len === 126) {
      if (b.length < offset + 2) return null;
      len = b.readUInt16BE(offset);
      offset += 2;
    } else if (len === 127) {
      if (b.length < offset + 8) return null;
      len = Number(b.readBigUInt64BE(offset));
      offset += 8;
    }
    if (len > MAX_MESSAGE) {
      this.close();
      return null;
    }
    // A browser MUST mask; refusing an unmasked frame is the spec, and it is
    // also a cheap way to reject anything that is not a WebSocket client.
    if (!masked) {
      this.close();
      return null;
    }
    if (b.length < offset + 4 + len) return null;
    const mask = b.subarray(offset, offset + 4);
    offset += 4;
    const payload = Buffer.from(b.subarray(offset, offset + len));
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    this.buffer = b.subarray(offset + len);
    return { opcode, payload };
  }

  send(obj) {
    this.writeFrame(0x1, Buffer.from(JSON.stringify(obj), 'utf8'));
  }

  writeFrame(opcode, payload) {
    if (this.closed) return;
    const len = payload.length;
    let header;
    if (len < 126) {
      header = Buffer.from([0x80 | opcode, len]);
    } else if (len <= 0xffff) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 126;
      header.writeUInt16BE(len, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(len), 2);
    }
    try {
      this.socket.write(Buffer.concat([header, payload]));
    } catch (e) {
      this.close();
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    try {
      this.socket.destroy();
    } catch (e) { /* already gone */ }
    this.onClose();
  }
}

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, 'http://localhost');
  const key = req.headers['sec-websocket-key'];
  if (url.pathname !== '/signal' || !key) {
    socket.destroy();
    return;
  }
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\n'
    + 'Upgrade: websocket\r\n'
    + 'Connection: Upgrade\r\n'
    + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
  socket.setNoDelay(true);
  attach(new Conn(socket));
});

/* ── rooms ───────────────────────────────────────────────────────────── */

/** room id → { host: Conn, peers: Map<peerId, Conn> } */
const rooms = new Map();
let peerCounter = 0;

function attach(conn) {
  conn.role = null;
  conn.room = null;
  conn.peerId = null;

  conn.onMessage = (text) => {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch (e) {
      return;
    }
    if (msg.t === 'ping') return;                 // the phone's keepalive

    if (msg.t === 'host') {
      becomeHost(conn, String(msg.room || ''));
      return;
    }
    if (msg.t === 'join' && conn.role === null) {
      becomePeer(conn, String(msg.room || ''));
      return;
    }
    relay(conn, msg);
  };

  conn.onClose = () => detach(conn);
}

function becomeHost(conn, roomId) {
  if (!roomId || roomId.length > 64) {
    conn.close();
    return;
  }
  if (!rooms.has(roomId) && rooms.size >= MAX_ROOMS) {
    conn.send({ t: 'error', why: 'busy' });
    conn.close();
    return;
  }
  let room = rooms.get(roomId);
  if (room && room.host && room.host !== conn) {
    // The phone reconnected — usually the old socket is already dead and the
    // network simply has not said so yet. The newest one wins.
    room.host.close();
  }
  if (!room) {
    room = { host: null, peers: new Map() };
    rooms.set(roomId, room);
  }
  room.host = conn;
  conn.role = 'host';
  conn.room = roomId;
  conn.send({ t: 'hosting', room: roomId });
  log(`room ${roomId}: host connected`);
}

function becomePeer(conn, roomId) {
  const room = rooms.get(roomId);
  if (!room || !room.host) {
    conn.send({ t: 'no-host' });
    return;
  }
  if (room.peers.size >= MAX_PEERS_PER_ROOM) {
    conn.send({ t: 'error', why: 'full' });
    return;
  }
  const peerId = 'p' + (++peerCounter);
  room.peers.set(peerId, conn);
  conn.role = 'peer';
  conn.room = roomId;
  conn.peerId = peerId;
  conn.send({ t: 'joined', peer: peerId });
  room.host.send({ t: 'join', peer: peerId });
  log(`room ${roomId}: ${peerId} joined`);
}

/**
 * Move one message across the room.
 *
 * Everything is addressed by peer id: a host names the peer it is answering,
 * and a peer's messages get its own id stamped on so it cannot pretend to be
 * another. Nothing else about the content is inspected — this relay has no
 * opinion about SDP and should not grow one.
 */
function relay(conn, msg) {
  const room = rooms.get(conn.room);
  if (!room) return;
  if (conn.role === 'host') {
    const peer = room.peers.get(String(msg.peer || ''));
    if (peer) peer.send(msg);
  } else if (conn.role === 'peer' && room.host) {
    msg.peer = conn.peerId;
    room.host.send(msg);
  }
}

function detach(conn) {
  const room = rooms.get(conn.room);
  if (!room) return;
  if (conn.role === 'host' && room.host === conn) {
    for (const peer of room.peers.values()) peer.send({ t: 'host-gone' });
    room.host = null;
    if (room.peers.size === 0) rooms.delete(conn.room);
    log(`room ${conn.room}: host gone`);
  } else if (conn.role === 'peer') {
    room.peers.delete(conn.peerId);
    if (room.host) room.host.send({ t: 'leave', peer: conn.peerId });
    if (!room.host && room.peers.size === 0) rooms.delete(conn.room);
    log(`room ${conn.room}: ${conn.peerId} left`);
  }
}

/** Sockets that vanished without a FIN — common on mobile networks. */
setInterval(() => {
  const now = Date.now();
  for (const room of rooms.values()) {
    for (const conn of [room.host, ...room.peers.values()]) {
      if (conn && now - conn.lastSeen > IDLE_MS) conn.close();
    }
  }
}, 30_000).unref();

function log(line) {
  process.stdout.write(`${new Date().toISOString()} ${line}\n`);
}

server.listen(PORT, HOST, () => {
  log(`openandroiddex-signal on ${HOST}:${PORT}, serving ${PUBLIC_DIR}`);
  log(process.env.TURN_URL
    ? `TURN ${process.env.TURN_URL} (${process.env.TURN_SECRET ? 'REST credentials' : 'static credentials'})`
    : 'no TURN configured — direct paths only, which will fail behind carrier NAT');
});
