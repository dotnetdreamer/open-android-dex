// Run in headless Chrome by test/usb-desktop-test.mjs --device: the window's
// side of the stream, cut down to what can be checked — connect, say hello,
// decode every frame with WebCodecs through the app's own h264.ts (served
// with its types stripped), and keep score in window.result. Input and
// resizes are sent when the test calls window.act(...).

import { avcC, codecString, sameBytes, splitAccessUnit } from "./h264.js";

const params = new URLSearchParams(location.search);
const result = (window.result = {
  states: [],
  videos: [],
  packets: 0,
  keys: 0,
  decoded: 0,
  decodedSizes: {},
  decodeErrors: [],
  cursor: null,
  stats: [],
  rtt: [],
  decodeMs: [],
  closed: false,
  error: null,
});

let ws;
let decoder = null;
let description = null;
let sps = null;
let pps = null;
let needKey = true;
const sentAt = new Map();

function send(m) {
  if (ws && ws.readyState === 1) ws.send(m);
}

function newDecoder() {
  decoder = new VideoDecoder({
    output: (frame) => {
      result.decoded++;
      const k = `${frame.displayWidth}x${frame.displayHeight}`;
      result.decodedSizes[k] = (result.decodedSizes[k] || 0) + 1;
      const t = sentAt.get(frame.timestamp);
      if (t !== undefined) {
        result.decodeMs.push(performance.now() - t);
        sentAt.delete(frame.timestamp);
      }
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
  sentAt.set(pts, performance.now());
  decoder.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: unit.data }));
}

window.act = (what) => {
  if (what.pointer) {
    const [x, y, b] = what.pointer;
    send(new Uint8Array([0x10, b, x >> 8, x & 255, y >> 8, y & 255]));
  }
  if (what.key) {
    const [k, down] = what.key;
    send(new Uint8Array([0x11, down ? 1 : 0, (k >>> 24) & 255, (k >>> 16) & 255, (k >>> 8) & 255, k & 255]));
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
        start: false,
        w: Number(params.get("w")),
        h: Number(params.get("h")),
        bitrate: Number(params.get("bitrate")),
        fps: 60,
        acks: true,
      }),
    );
    setInterval(() => send(JSON.stringify({ t: "ping", n: performance.now() })), 500);
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data === "string") {
      const m = JSON.parse(ev.data);
      if (m.t === "state") result.states.push(m.state);
      else if (m.t === "video") result.videos.push(m);
      else if (m.t === "stats") result.stats.push(m);
      else if (m.t === "pong") result.rtt.push(performance.now() - m.n);
      else if (m.t === "clip") result.clip = m.text;
      return;
    }
    const b = new Uint8Array(ev.data);
    const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
    if (b[0] === 1 && !(b[1] & 2)) send(new Uint8Array([0x12]));
    if (b[0] === 1) onVideo(b[1], Number(v.getBigUint64(2)), b.subarray(10));
    else if (b[0] === 2) result.cursor = { hot: [v.getUint16(1), v.getUint16(3)], size: [v.getUint16(5), v.getUint16(7)], bytes: b.length - 9 };
  };
  ws.onclose = () => (result.closed = true);
} catch (e) {
  result.error = String(e);
}
