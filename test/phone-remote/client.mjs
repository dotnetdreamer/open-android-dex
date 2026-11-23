// A computer's side of Remote desktop's stream (PhoneStream), for tests:
// connects to ws://127.0.0.1:<port>/phone (an adb forward to the phone's
// @<package>.phone-stream), says hello, acknowledges every frame with its
// arrival time as the desktop app does, and records what came.
//
//   const c = await connect(port, { quality: 'medium', wake: false });
//   await c.waitFor(m => m.t === 'video');
//   ...
//   c.close();
//
// Every text message is kept in c.texts, every video unit in c.units
// ({at, flags, pts, bytes}), and the H.264 itself in c.h264 (Annex B, the
// units one after another, as a decoder takes it).

export async function connect(port, opts = {}) {
  // A whole address (the tunnel's /phone/<token>, from `dexremote phone`), or
  // a port of an adb forward to the phone's socket.
  const url = opts.url || `ws://127.0.0.1:${port}${opts.path || '/phone'}`;
  const ws = opts.origin ? new WebSocket(url, { headers: { Origin: opts.origin } }) : new WebSocket(url);
  ws.binaryType = 'arraybuffer';
  const c = {
    ws,
    texts: [],
    units: [],
    chunks: [],
    frames: 0,
    keys: 0,
    closed: false,
    closeInfo: null,
    waiters: [],
    get h264() { return Buffer.concat(this.chunks); },
    send(obj) { ws.send(JSON.stringify(obj)); },
    pointer(buttons, x, y) {
      const b = new Uint8Array(6);
      const v = new DataView(b.buffer);
      v.setUint8(0, 0x10); v.setUint8(1, buttons); v.setUint16(2, x); v.setUint16(4, y);
      ws.send(b);
    },
    key(keysym, down) {
      const b = new Uint8Array(6);
      const v = new DataView(b.buffer);
      v.setUint8(0, 0x11); v.setUint8(1, down ? 1 : 0); v.setUint32(2, keysym);
      ws.send(b);
    },
    waitFor(test, ms = 10000) {
      const hit = this.texts.find(test);
      if (hit) return Promise.resolve(hit);
      return new Promise((resolve, reject) => {
        const w = { test, resolve };
        this.waiters.push(w);
        setTimeout(() => {
          const i = this.waiters.indexOf(w);
          if (i >= 0) { this.waiters.splice(i, 1); reject(new Error('timed out waiting')); }
        }, ms);
      });
    },
    close() { try { ws.close(); } catch {} },
  };
  const ack = opts.ack ?? 'timed';
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      m._at = performance.now();
      c.texts.push(m);
      for (const w of [...c.waiters]) {
        if (w.test(m)) { c.waiters.splice(c.waiters.indexOf(w), 1); w.resolve(m); }
      }
      return;
    }
    const d = new Uint8Array(ev.data);
    if (d[0] !== 0x01) return;
    const v = new DataView(ev.data);
    const flags = d[1];
    const pts = Number(v.getBigUint64(2));
    const body = Buffer.from(d.subarray(10));
    c.chunks.push(body);
    c.units.push({ at: performance.now(), flags, pts, bytes: body.length });
    if (flags & 2) return; // codec config: not a frame, not acknowledged
    c.frames++;
    if (flags & 1) c.keys++;
    if (ack === 'none') return;
    if (ack === 'plain') {
      ws.send(new Uint8Array([0x12]));
    } else {
      const b = new Uint8Array(9);
      new DataView(b.buffer).setUint8(0, 0x12);
      new DataView(b.buffer).setFloat64(1, performance.timeOrigin + performance.now());
      ws.send(b);
    }
  };
  ws.onclose = (ev) => { c.closed = true; c.closeInfo = { code: ev.code, reason: ev.reason }; };
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = (e) => reject(new Error('cannot connect: ' + (e.message || 'error')));
  });
  c.send({
    t: 'hello', v: 1, w: opts.w || 1280, h: opts.h || 800,
    bitrate: opts.bitrate || 20_000_000, fps: opts.fps || 60,
    acks: ack !== 'none', ackAt: ack === 'timed',
    quality: opts.quality, wake: opts.wake ?? false,
  });
  return c;
}

/** Frames a second over the last `ms` of what came. */
export function fpsOver(c, ms) {
  const now = performance.now();
  return c.units.filter(u => !(u.flags & 2) && now - u.at <= ms).length / (ms / 1000);
}

export const sleep = (ms) => new Promise(r => setTimeout(r, ms));
