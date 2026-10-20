// A stand-in for the phone's Xvnc, for test/usb-desktop-test.mjs.
//
// RFB 3.8 with VncAuth, the pixel format Xvnc has at depth 24 (32-bit,
// little-endian, red at bit 16 — B,G,R,X in memory), and only what the
// stream's client asks for: raw rectangles, ExtendedDesktopSize, DesktopSize
// and the Cursor shape. The picture moves — a bar sweeping across a gradient —
// so an encoder fed from it has real motion to encode, and every pixel of it
// can be recomputed from its position and the frame number, so a client's
// copy can be checked.
//
// VncAuth's DES comes from OpenSSL's legacy provider: run node with
// --openssl-legacy-provider (the test re-executes itself with it).

import net from "node:net";
import crypto from "node:crypto";

const ENC_RAW = 0;
const ENC_DESKTOP_SIZE = -223;
const ENC_CURSOR = -239;
const ENC_EXTENDED_DESKTOP_SIZE = -308;

/** The colour of pixel (x, y) in frame `n`, as [r, g, b]. */
export function pixelAt(x, y, n, w) {
  const bar = (n * 12) % Math.max(1, w);
  if (x >= bar && x < bar + 48) return [255, 255, 255];
  return [(x * 255 / Math.max(1, w - 1)) | 0, (y * 7) & 0xff, (n * 3) & 0xff];
}

function vncKey(password) {
  const key = Buffer.alloc(8);
  Buffer.from(password, "latin1").copy(key, 0, 0, 8);
  for (let i = 0; i < 8; i++) {
    let b = key[i];
    let r = 0;
    for (let j = 0; j < 8; j++) r |= ((b >> j) & 1) << (7 - j);
    key[i] = r;
  }
  return key;
}

function vncExpected(password, challenge) {
  const c = crypto.createCipheriv("des-ecb", vncKey(password), null);
  c.setAutoPadding(false);
  return Buffer.concat([c.update(challenge), c.final()]);
}

/**
 * Start one. Returns { port, close, events, clients, sendClipboard(text) }:
 * `events` collects everything clients said, as plain objects.
 */
export function startRfbStandin({ password = "standin1", width = 640, height = 400, fps = 60 } = {}) {
  const events = [];
  const clients = new Set();
  const server = net.createServer((sock) => serve(sock));
  let fb = { w: width, h: height };

  function serve(sock) {
    sock.setNoDelay(true);
    let buf = Buffer.alloc(0);
    let waiters = [];
    const client = { sock, encodings: [], frame: 0, pendingResize: null, sentLayout: false, sentCursor: false, timer: null };
    clients.add(client);
    sock.on("data", (d) => {
      buf = Buffer.concat([buf, d]);
      pump();
    });
    sock.on("error", () => {});
    sock.on("close", () => {
      clients.delete(client);
      clearTimeout(client.timer);
      events.push({ type: "closed" });
    });
    const need = (n) =>
      new Promise((resolve) => {
        waiters.push({ n, resolve });
        pump();
      });
    function pump() {
      while (waiters.length && buf.length >= waiters[0].n) {
        const { n, resolve } = waiters.shift();
        const out = buf.subarray(0, n);
        buf = buf.subarray(n);
        resolve(Buffer.from(out));
      }
    }

    (async () => {
      sock.write("RFB 003.008\n");
      const version = (await need(12)).toString("latin1");
      events.push({ type: "version", version: version.trim() });
      sock.write(Buffer.from([1, 2])); // one security type: VncAuth
      const chosen = (await need(1))[0];
      if (chosen !== 2) {
        events.push({ type: "bad-security", chosen });
        sock.destroy();
        return;
      }
      const challenge = crypto.randomBytes(16);
      sock.write(challenge);
      const response = await need(16);
      if (!response.equals(vncExpected(password, challenge))) {
        events.push({ type: "auth-failed" });
        const reason = Buffer.from("Authentication failure");
        const msg = Buffer.alloc(8 + reason.length);
        msg.writeUInt32BE(1, 0);
        msg.writeUInt32BE(reason.length, 4);
        reason.copy(msg, 8);
        sock.end(msg);
        return;
      }
      sock.write(Buffer.from([0, 0, 0, 0]));
      const shared = (await need(1))[0];
      events.push({ type: "client-init", shared });
      const name = Buffer.from("standin:1");
      const init = Buffer.alloc(24 + name.length);
      init.writeUInt16BE(fb.w, 0);
      init.writeUInt16BE(fb.h, 2);
      // 32 bpp, depth 24, little-endian, true colour, 255 x3, shifts R16 G8 B0
      Buffer.from([32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0]).copy(init, 4);
      init.writeUInt32BE(name.length, 20);
      name.copy(init, 24);
      sock.write(init);

      for (;;) {
        const type = (await need(1))[0];
        if (type === 0) {
          const pf = await need(19);
          events.push({ type: "set-pixel-format", pf: [...pf.subarray(3)] });
        } else if (type === 2) {
          const head = await need(3);
          const n = head.readUInt16BE(1);
          const list = await need(4 * n);
          client.encodings = [];
          for (let i = 0; i < n; i++) client.encodings.push(list.readInt32BE(4 * i));
          events.push({ type: "set-encodings", encodings: client.encodings });
        } else if (type === 3) {
          const req = await need(9);
          const incremental = req[0] === 1;
          events.push({ type: "update-request", incremental });
          scheduleUpdate(client, incremental);
        } else if (type === 4) {
          const k = await need(7);
          events.push({ type: "key", down: k[0] === 1, keysym: k.readUInt32BE(3) });
        } else if (type === 5) {
          const p = await need(5);
          events.push({ type: "pointer", buttons: p[0], x: p.readUInt16BE(1), y: p.readUInt16BE(3) });
        } else if (type === 6) {
          const h = await need(7);
          const len = h.readUInt32BE(3);
          const text = (await need(len)).toString("latin1");
          events.push({ type: "clipboard", text });
        } else if (type === 251) {
          const h = await need(7);
          const w = h.readUInt16BE(1);
          const hh = h.readUInt16BE(3);
          const screens = h[5];
          await need(16 * screens);
          events.push({ type: "set-desktop-size", w, h: hh });
          fb = { w, h: hh };
          for (const c of clients) c.pendingResize = { w, h: hh, mine: c === client };
        } else {
          events.push({ type: "unknown", message: type });
          sock.destroy();
          return;
        }
      }
    })().catch(() => sock.destroy());
  }

  function scheduleUpdate(client, incremental) {
    clearTimeout(client.timer);
    client.timer = setTimeout(() => sendUpdate(client, incremental), incremental ? 1000 / fps : 0);
  }

  function rawRect(x, y, w, h, n) {
    const head = Buffer.alloc(12);
    head.writeUInt16BE(x, 0);
    head.writeUInt16BE(y, 2);
    head.writeUInt16BE(w, 4);
    head.writeUInt16BE(h, 6);
    head.writeInt32BE(ENC_RAW, 8);
    const px = Buffer.alloc(w * h * 4);
    let o = 0;
    for (let j = 0; j < h; j++) {
      for (let i = 0; i < w; i++) {
        const [r, g, b] = pixelAt(x + i, y + j, n, fb.w);
        px[o] = b;
        px[o + 1] = g;
        px[o + 2] = r;
        px[o + 3] = 0;
        o += 4;
      }
    }
    return [head, px];
  }

  function sendUpdate(client, incremental) {
    const sock = client.sock;
    if (sock.destroyed) return;
    const parts = [];
    let count = 0;
    let full = !incremental;
    const wantsLayout = client.encodings.includes(ENC_EXTENDED_DESKTOP_SIZE);
    if (client.pendingResize || (wantsLayout && !client.sentLayout)) {
      const r = client.pendingResize;
      client.pendingResize = null;
      client.sentLayout = true;
      if (wantsLayout) {
        const head = Buffer.alloc(12 + 4 + 16);
        head.writeUInt16BE(r ? (r.mine ? 1 : 2) : 0, 0);
        head.writeUInt16BE(0, 2);
        head.writeUInt16BE(fb.w, 4);
        head.writeUInt16BE(fb.h, 6);
        head.writeInt32BE(ENC_EXTENDED_DESKTOP_SIZE, 8);
        head[12] = 1;
        head.writeUInt32BE(7, 16); // screen id
        head.writeUInt16BE(fb.w, 24);
        head.writeUInt16BE(fb.h, 26);
        parts.push(head);
      } else {
        const head = Buffer.alloc(12);
        head.writeUInt16BE(fb.w, 4);
        head.writeUInt16BE(fb.h, 6);
        head.writeInt32BE(ENC_DESKTOP_SIZE, 8);
        parts.push(head);
      }
      count++;
      if (r) full = true;
    }
    if (client.encodings.includes(ENC_CURSOR) && !client.sentCursor) {
      client.sentCursor = true;
      const w = 12;
      const h = 18;
      const head = Buffer.alloc(12);
      head.writeUInt16BE(1, 0); // hotspot
      head.writeUInt16BE(2, 2);
      head.writeUInt16BE(w, 4);
      head.writeUInt16BE(h, 6);
      head.writeInt32BE(ENC_CURSOR, 8);
      const px = Buffer.alloc(w * h * 4, 0);
      for (let i = 0; i < w * h; i++) {
        px[i * 4 + 2] = 255; // red, in B,G,R,X
      }
      const stride = Math.ceil(w / 8);
      const mask = Buffer.alloc(stride * h);
      for (let y = 0; y < h; y++) for (let x = 0; x <= Math.min(w - 1, y); x++) mask[y * stride + (x >> 3)] |= 0x80 >> (x & 7);
      parts.push(head, px, mask);
      count++;
    }
    client.frame++;
    const n = client.frame;
    if (full) {
      // Xvnc splits big updates into rectangles; so does this, by row bands.
      const band = 64;
      for (let y = 0; y < fb.h; y += band) {
        parts.push(...rawRect(0, y, fb.w, Math.min(band, fb.h - y), n));
        count++;
      }
    } else {
      // The bar moved and the gradient's blue changed: a whole-frame change
      // in two strips, which is what a scrolling page looks like.
      parts.push(...rawRect(0, 0, fb.w, fb.h >> 1, n), ...rawRect(0, fb.h >> 1, fb.w, fb.h - (fb.h >> 1), n));
      count += 2;
    }
    const head = Buffer.alloc(4);
    head[0] = 0;
    head.writeUInt16BE(count, 2);
    sock.write(Buffer.concat([head, ...parts]));
  }

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      resolve({
        port: server.address().port,
        events,
        clients,
        get size() {
          return { ...fb };
        },
        sendClipboard(text) {
          const t = Buffer.from(text, "latin1");
          for (const c of clients) {
            const m = Buffer.alloc(8 + t.length);
            m[0] = 3;
            m.writeUInt32BE(t.length, 4);
            t.copy(m, 8);
            c.sock.write(m);
          }
        },
        close() {
          for (const c of clients) c.sock.destroy();
          return new Promise((r) => server.close(() => r()));
        },
      });
    });
  });
}
