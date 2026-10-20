// Run in headless Chrome by test/dex-video-test.mjs --device: the window's side
// of the Android desktop's stream (openandroiddex-wmd, Desktop), cut down to
// what can be checked — connect, say hello, decode every frame with WebCodecs
// through the app's own h264.ts (served with its types stripped), acknowledge
// each as it arrives, and keep score in window.result. Input, resizes and the
// clipboard are sent when the test calls window.act(...).
//
// The latency is the window's own sum: each frame's pts is when the phone's
// compositor made it, on the clock the phone's pong reads ("at"), and the
// ping with the shortest round trip says where that clock is against this one.

import { avcC, codecString, sameBytes, splitAccessUnit } from "./h264.js";

const params = new URLSearchParams(location.search);
const result = (window.result = {
  videos: [],
  states: [],
  packets: 0,
  keys: 0,
  decoded: 0,
  decodedSizes: {},
  decodeErrors: [],
  stats: [],
  rtt: [],
  latency: [],
  clips: [],
  closed: false,
  error: null,
});

let ws;
let decoder = null;
let description = null;
let sps = null;
let pps = null;
let needKey = true;
/** The phone's clock minus this page's, in ms, from the ping with the shortest round trip. */
let clock = null;

function send(m) {
  if (ws && ws.readyState === 1) ws.send(m);
}

function newDecoder() {
  decoder = new VideoDecoder({
    output: (frame) => {
      result.decoded++;
      const k = `${frame.displayWidth}x${frame.displayHeight}`;
      result.decodedSizes[k] = (result.decodedSizes[k] || 0) + 1;
      if (clock) result.latency.push(performance.now() - (frame.timestamp / 1000 - clock.offset));
      frame.close();
    },
    error: (e) => {
      result.decodeErrors.push(String(e.message || e));
      description = null;
      needKey = true;
      send(JSON.stringify({ t: "keyframe" }));
    },
  });
}

function onVideo(flags, pts, payload) {
  result.packets++;
  const unit = splitAccessUnit(payload);
  if (unit.sps) sps = unit.sps;
  if (unit.pps) pps = unit.pps;
  if (!unit.data.length) return;
  const isKey = unit.key || (flags & 1) !== 0;
  if (isKey) result.keys++;
  if (isKey && sps && pps) {
    const d = avcC(sps, pps);
    if (!decoder || decoder.state === "closed" || !sameBytes(d, description)) {
      if (!decoder || decoder.state === "closed") newDecoder();
      decoder.configure({ codec: codecString(sps), description: d, optimizeForLatency: true });
      description = d;
      result.codec = codecString(sps);
    }
    needKey = false;
  }
  if (needKey || !decoder || decoder.state !== "configured") return;
  decoder.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: unit.data }));
}

const u16 = (v, at, n) => v.setUint16(at, n);
const scrolls = [];

window.act = (what) => {
  if (what.pointer) {
    const [x, y, buttons, meta = 0] = what.pointer;
    const b = new Uint8Array(10);
    const v = new DataView(b.buffer);
    b[0] = 0x20;
    b[1] = buttons;
    u16(v, 2, x);
    u16(v, 4, y);
    v.setUint32(6, meta);
    send(b);
  }
  if (what.scroll) {
    const [x, y, h, vs, meta = 0] = what.scroll;
    const b = new Uint8Array(17);
    const v = new DataView(b.buffer);
    b[0] = 0x21;
    u16(v, 1, x);
    u16(v, 3, y);
    v.setFloat32(5, h);
    v.setFloat32(9, vs);
    v.setUint32(13, meta);
    send(b);
  }
  if (what.key) {
    const [down, code, repeat = 0, meta = 0] = what.key;
    const b = new Uint8Array(9);
    const v = new DataView(b.buffer);
    b[0] = 0x22;
    b[1] = down ? 1 : 0;
    b[2] = repeat;
    u16(v, 3, code);
    v.setUint32(5, meta);
    send(b);
  }
  if (what.text) {
    const t = new TextEncoder().encode(what.text);
    const b = new Uint8Array(1 + t.length);
    b[0] = 0x23;
    b.set(t, 1);
    send(b);
  }
  // Keep the page moving: a wheel turn every frame, down then back up.
  if (what.scrolling) {
    const [x, y, ms] = what.scrolling;
    const start = performance.now();
    const timer = setInterval(() => {
      const t = performance.now() - start;
      if (t > ms) return clearInterval(timer);
      window.act({ scroll: [x, y, 0, Math.floor(t / 700) % 2 ? 0.6 : -0.6] });
    }, 16);
    scrolls.push(timer);
  }
  if (what.json) send(JSON.stringify(what.json));
  return true;
};

try {
  ws = new WebSocket(params.get("ws"));
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    send(
      JSON.stringify({
        t: "hello",
        v: 1,
        w: Number(params.get("w")),
        h: Number(params.get("h")),
        bitrate: Number(params.get("bitrate")),
        fps: 60,
        acks: true,
      }),
    );
    setInterval(() => send(JSON.stringify({ t: "ping", n: performance.now() })), 250);
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data === "string") {
      const m = JSON.parse(ev.data);
      if (m.t === "video") result.videos.push(m);
      else if (m.t === "state") result.states.push(m);
      else if (m.t === "stats") result.stats.push(m);
      else if (m.t === "clip") result.clips.push(m.text);
      else if (m.t === "pong") {
        const now = performance.now();
        const rtt = now - m.n;
        result.rtt.push(rtt);
        const offset = m.at / 1000 - (m.n + rtt / 2);
        if (!clock || rtt < clock.rtt) clock = { rtt, offset };
      }
      return;
    }
    const b = new Uint8Array(ev.data);
    const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
    if (b[0] === 1 && !(b[1] & 2)) send(new Uint8Array([0x12]));
    if (b[0] === 1) onVideo(b[1], Number(v.getBigUint64(2)), b.subarray(10));
  };
  ws.onclose = () => (result.closed = true);
} catch (e) {
  result.error = String(e);
}
