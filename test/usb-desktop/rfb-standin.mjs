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
// The clipboard is TigerVNC 1.13's extended one, as Xvnc does it: its
// capabilities in answer to the encodings, ahead of any picture, with a size
// of 0 (nothing unasked); the guest's text announced, then provided when
// asked for — UTF-8, CRLF, a NUL, zlib sync-flushed; a client's text asked
// for when "something in the guest pastes" (at once, by default, and again on
// `paste()`), and refused over MaxCutText. `extendedClipboard: false` is a
// server without it: Latin-1 cut text both ways.
//
// VncAuth's DES comes from OpenSSL's legacy provider: run node with
// --openssl-legacy-provider (the test re-executes itself with it).

import net from "node:net";
import crypto from "node:crypto";
import zlib from "node:zlib";

const ENC_RAW = 0;
const ENC_DESKTOP_SIZE = -223;
const ENC_CURSOR = -239;
const ENC_EXTENDED_DESKTOP_SIZE = -308;
const ENC_EXTENDED_CLIPBOARD = 0xc0a1e5ce | 0;
const CLIP_TEXT = 1;
const CLIP_CAPS = 1 << 24;
const CLIP_REQUEST = 1 << 25;
const CLIP_PEEK = 1 << 26;
const CLIP_NOTIFY = 1 << 27;
const CLIP_PROVIDE = 1 << 28;
/** Xvnc's default MaxCutText. */
const MAX_CUT_TEXT = 262144;

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

/** ServerCutText in its extended form: a negative length, then the flags and the rest. */
function extended(sock, flags, rest = Buffer.alloc(0)) {
  const head = Buffer.alloc(12);
  head[0] = 3;
  head.writeInt32BE(-(4 + rest.length), 4);
  head.writeUInt32BE(flags >>> 0, 8);
  sock.write(Buffer.concat([head, rest]));
}

/** A text as Xvnc provides one: its length, UTF-8 with CRLF and a NUL, deflated and sync-flushed. */
function provideBody(text) {
  const bytes = Buffer.from(text.replace(/\r?\n/g, "\r\n") + "\0", "utf8");
  const len = Buffer.alloc(4);
  len.writeUInt32BE(bytes.length);
  return zlib.deflateSync(Buffer.concat([len, bytes]), { finishFlush: zlib.constants.Z_SYNC_FLUSH });
}

/**
 * Start one. Returns { port, close, events, clients, sendClipboard(text), paste() }:
 * `events` collects everything clients said, as plain objects.
 */
export function startRfbStandin({
  password = "standin1",
  width = 640,
  height = 400,
  fps = 60,
  extendedClipboard = true,
  pasteAtOnce = true,
} = {}) {
  const events = [];
  const clients = new Set();
  const server = net.createServer((sock) => serve(sock));
  let fb = { w: width, h: height };
  /** The guest's clipboard, for a client that asks. */
  let guestText = "";

  function serve(sock) {
    sock.setNoDelay(true);
    let buf = Buffer.alloc(0);
    let waiters = [];
    const client = { sock, encodings: [], frame: 0, pendingResize: null, sentLayout: false, sentCursor: false, timer: null, extClip: false };
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
          if (extendedClipboard && client.encodings.includes(ENC_EXTENDED_CLIPBOARD) && !client.extClip) {
            // Xvnc's answer, written before it handles the update request
            // behind it: its actions, text, and a size of 0 for text.
            client.extClip = true;
            extended(sock, CLIP_CAPS | CLIP_REQUEST | CLIP_PEEK | CLIP_NOTIFY | CLIP_PROVIDE | CLIP_TEXT, Buffer.alloc(4));
          }
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
          const len = h.readInt32BE(3);
          if (len >= 0) {
            const text = (await need(len)).toString("latin1");
            if (len > MAX_CUT_TEXT) events.push({ type: "clip-too-long", bytes: len });
            else events.push({ type: "clipboard", text });
          } else {
            const body = await need(-len);
            onExtended(client, body, -len);
          }
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

  /** A client's extended clipboard message, as Xvnc (SMsgReader) reads one. */
  function onExtended(client, body, n) {
    if (!client.extClip || n < 4) {
      events.push({ type: "clip-unexpected", n });
      return;
    }
    const flags = body.readUInt32BE(0);
    const rest = body.subarray(4);
    if (flags & CLIP_CAPS) {
      const sizes = [];
      for (let o = 0; o + 4 <= rest.length; o += 4) sizes.push(rest.readUInt32BE(o));
      events.push({ type: "clip-caps", flags, sizes });
    } else if (flags & CLIP_REQUEST) {
      events.push({ type: "clip-request", text: !!(flags & CLIP_TEXT) });
      if (flags & CLIP_TEXT) extended(client.sock, CLIP_PROVIDE | CLIP_TEXT, provideBody(guestText));
    } else if (flags & CLIP_PEEK) {
      events.push({ type: "clip-peek" });
      extended(client.sock, CLIP_NOTIFY | (guestText ? CLIP_TEXT : 0));
    } else if (flags & CLIP_NOTIFY) {
      const text = !!(flags & CLIP_TEXT);
      events.push({ type: "clip-notify", text });
      client.offers = text;
      // Something in the guest pastes straight away.
      if (text && pasteAtOnce) extended(client.sock, CLIP_REQUEST | CLIP_TEXT);
    } else if (flags & CLIP_PROVIDE) {
      if (n > MAX_CUT_TEXT) {
        events.push({ type: "clip-too-long", bytes: n });
        return;
      }
      let raw;
      try {
        raw = zlib.inflateSync(rest, { finishFlush: zlib.constants.Z_SYNC_FLUSH });
      } catch (e) {
        events.push({ type: "clip-bad-zlib", error: String(e) });
        return;
      }
      const bytes = raw.readUInt32BE(0);
      if (bytes > MAX_CUT_TEXT) {
        events.push({ type: "clip-too-long", bytes });
        return;
      }
      const data = raw.subarray(4, 4 + bytes);
      const text = data.toString("utf8");
      events.push({
        type: "clip-provide",
        // As Xvnc keeps it: the NUL off, CRLF back to LF.
        text: text.replace(/\0$/, "").replace(/\r\n/g, "\n"),
        bytes,
        nul: data[data.length - 1] === 0,
        // Every line end CRLF, none bare.
        crlf: !/[^\r]\n/.test(text) && !/^\n/.test(text),
        valid: Buffer.from(text, "utf8").equals(data),
      });
    } else {
      events.push({ type: "clip-unknown", flags });
    }
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
        /** The guest copies `text`: announced to each client, or sent whole as Latin-1 to one without the extension. */
        sendClipboard(text) {
          guestText = text;
          const t = Buffer.from(text, "latin1");
          for (const c of clients) {
            if (c.extClip) {
              extended(c.sock, CLIP_NOTIFY | CLIP_TEXT);
              continue;
            }
            const m = Buffer.alloc(8 + t.length);
            m[0] = 3;
            m.writeUInt32BE(t.length, 4);
            t.copy(m, 8);
            c.sock.write(m);
          }
        },
        /** Something in the guest pastes: the text is asked for from each client that offered some. */
        paste() {
          for (const c of clients) if (c.extClip && c.offers) extended(c.sock, CLIP_REQUEST | CLIP_TEXT);
        },
        close() {
          for (const c of clients) c.sock.destroy();
          return new Promise((r) => server.close(() => r()));
        },
      });
    });
  });
}
