// The picture, off the page's main thread (viewer.js): the stream's socket,
// the decoder and the canvas, all in this worker, so that nothing the page
// does on its own thread (a long task, a busy moment, a garbage collection)
// stands between a frame and the screen. Everything that is not the picture
// (the phone's words, the pointer's shape, the sound) goes on to the page as
// it came, in order, and the page's input comes back here to be sent.
//
// The same decoding as viewer.js does on its own thread: units out of Annex
// B (h264.js), the decoder configured at each key frame with new parameter
// sets, a key frame asked for whenever it has to start over, and each frame
// drawn the moment it comes out of the decoder. Each frame is acknowledged as
// it arrives, with the time it arrived, which here is the time it really
// arrived, whatever the page's own thread is doing: the phone reads how late
// frames come from those times (LinuxStreamRate), and a busy page would
// otherwise look like a slow link.
//
// To the page: {t:'ready', decoder}, then for the socket {t:'open'|'text'|
// 'bin'|'closed', id, data?} and for each frame drawn {t:'drawn', id, late?,
// keysIn, keysAsked, decodeMs}; {t:'pixels', data} when asked. From the page:
// {t:'canvas', canvas}, {t:'open', id, url}, {t:'send', id, data},
// {t:'close', id}, {t:'pixels'}.

import { avcC, codecString, sameBytes, splitAccessUnit } from './h264.js';

const MSG_VIDEO = 0x01;
const IN_ACK = 0x12;
const clock = () => performance.timeOrigin + performance.now();

function ackOf(arrivedAt) {
  const a = new Uint8Array(9);
  a[0] = IN_ACK;
  new DataView(a.buffer).setFloat64(1, arrivedAt);
  return a;
}

let canvas = null;
let ctx = null;

/** The one stream there is: its socket, and what decoding it needs. */
let cur = null;

function newStream(id, url) {
  return {
    id,
    ws: null,
    dw: 0, dh: 0, cw: 0, ch: 0,
    wallClock: false,
    decoder: null,
    description: null,
    sps: null,
    pps: null,
    needKey: true,
    askedKeyAt: 0,
    told: false,
    keysIn: 0,
    keysAsked: 0,
    decodeMs: 0,
    decodeStart: new Map(),
    url,
  };
}

function send(s, data) {
  if (s.ws && s.ws.readyState === WebSocket.OPEN) s.ws.send(data);
}

function askKey(s) {
  const now = performance.now();
  if (now - s.askedKeyAt < 500) return;
  s.askedKeyAt = now;
  s.keysAsked++;
  send(s, JSON.stringify({ t: 'keyframe' }));
}

function closeDecoder(s) {
  if (s.decoder && s.decoder.state !== 'closed') {
    try {
      s.decoder.close();
    } catch {
      // closed already
    }
  }
  s.decoder = null;
  s.description = null;
  s.needKey = true;
  s.decodeStart.clear();
}

function draw(s, frame) {
  try {
    if (s !== cur || s.told || !ctx || s.dw <= 0) return;
    const t0 = s.decodeStart.get(frame.timestamp);
    if (t0 !== undefined) {
      s.decodeStart.delete(frame.timestamp);
      s.decodeMs = s.decodeMs * 0.9 + (performance.now() - t0) * 0.1;
    }
    if (canvas.width !== s.dw || canvas.height !== s.dh) {
      canvas.width = s.dw;
      canvas.height = s.dh;
      ctx.imageSmoothingQuality = 'high';
    }
    ctx.drawImage(frame, 0, 0, s.cw || frame.displayWidth, s.ch || frame.displayHeight, 0, 0, s.dw, s.dh);
    const msg = { t: 'drawn', id: s.id, keysIn: s.keysIn, keysAsked: s.keysAsked, decodeMs: s.decodeMs };
    if (s.wallClock) msg.late = clock() - frame.timestamp / 1000;
    postMessage(msg);
  } finally {
    frame.close();
  }
}

function newDecoder(s) {
  closeDecoder(s);
  s.decoder = new VideoDecoder({
    output: (frame) => draw(s, frame),
    error: () => {
      s.description = null;
      s.needKey = true;
      askKey(s);
    },
  });
}

function onVideo(s, b, arrived) {
  const flags = b[1];
  const pts = Number(new DataView(b.buffer, b.byteOffset, b.byteLength).getBigUint64(2));
  // On arrival, with when; never for parameter sets alone.
  if (!(flags & 2)) send(s, ackOf(arrived));
  const unit = splitAccessUnit(b.subarray(10));
  if (unit.sps) s.sps = unit.sps;
  if (unit.pps) s.pps = unit.pps;
  if (!unit.data.length) return;
  const isKey = unit.key || (flags & 1) !== 0;
  if (isKey) s.keysIn++;
  if (isKey && s.sps && s.pps) {
    const d = avcC(s.sps, s.pps);
    if (!s.decoder || s.decoder.state === 'closed' || !sameBytes(d, s.description)) {
      if (!s.decoder || s.decoder.state === 'closed') newDecoder(s);
      s.decoder.configure({
        codec: codecString(s.sps),
        description: d,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      });
      s.description = d;
    }
    s.needKey = false;
  }
  if (s.needKey || !s.decoder || s.decoder.state !== 'configured') {
    askKey(s);
    return;
  }
  // A decoder behind by more than a couple of frames cannot keep up: start
  // over from a key frame rather than show a growing delay.
  if (s.decoder.decodeQueueSize > 3 && !isKey) {
    s.needKey = true;
    askKey(s);
    return;
  }
  if (s.decodeStart.size > 240) s.decodeStart.clear();
  s.decodeStart.set(pts, performance.now());
  s.decoder.decode(new EncodedVideoChunk({ type: isKey ? 'key' : 'delta', timestamp: pts, data: unit.data }));
}

/** A word from the phone: what the picture needs of it read here too, then on to the page. */
function onText(s, text) {
  if (text.includes('"video"') || text.includes('"state"')) {
    let m = null;
    try {
      m = JSON.parse(text);
    } catch {
      m = null;
    }
    if (m && m.t === 'video') {
      s.dw = Number(m.dw);
      s.dh = Number(m.dh);
      s.cw = Number(m.cw);
      s.ch = Number(m.ch);
      s.wallClock = m.clock === 'wall';
      s.needKey = true;
    } else if (m && m.t === 'state' && m.state !== 'starting' && m.state !== 'running') {
      // Over: nothing still on its way may draw the desktop back over what
      // the page is about to say.
      s.told = true;
      closeDecoder(s);
    }
  }
  postMessage({ t: 'text', id: s.id, data: text });
}

function open(id, url) {
  close();
  const s = newStream(id, url);
  cur = s;
  let ws;
  try {
    ws = new WebSocket(url);
  } catch {
    postMessage({ t: 'closed', id });
    cur = null;
    return;
  }
  ws.binaryType = 'arraybuffer';
  s.ws = ws;
  ws.onopen = () => {
    if (s === cur) postMessage({ t: 'open', id });
  };
  ws.onmessage = (ev) => {
    if (s !== cur) return;
    if (typeof ev.data === 'string') {
      onText(s, ev.data);
      return;
    }
    const arrived = clock();
    const b = new Uint8Array(ev.data);
    if (b[0] === MSG_VIDEO && b.length > 10) {
      if (!s.told) onVideo(s, b, arrived);
      return;
    }
    postMessage({ t: 'bin', id, data: ev.data }, [ev.data]);
  };
  ws.onclose = () => {
    if (s !== cur) return;
    closeDecoder(s);
    cur = null;
    postMessage({ t: 'closed', id });
  };
}

function close() {
  const s = cur;
  cur = null;
  if (!s) return;
  if (s.ws) {
    s.ws.onopen = null;
    s.ws.onmessage = null;
    s.ws.onclose = null;
    try {
      s.ws.close();
    } catch {
      // gone already
    }
  }
  closeDecoder(s);
}

onmessage = (e) => {
  const m = e.data;
  switch (m.t) {
    case 'canvas':
      canvas = m.canvas;
      ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
      break;
    case 'open':
      open(m.id, m.url);
      break;
    case 'pixels': {
      // For the tests: the canvas's size, and how much of it is lit.
      let data = null;
      if (ctx && canvas.width && canvas.height) {
        const d = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let lit = 0;
        let n = 0;
        for (let i = 0; i < d.length; i += 4 * 997) {
          n++;
          if (d[i] + d[i + 1] + d[i + 2] > 60) lit++;
        }
        data = { w: canvas.width, h: canvas.height, lit, n };
      }
      postMessage({ t: 'pixels', data });
      break;
    }
    case 'send':
      if (cur && cur.id === m.id) send(cur, m.data);
      break;
    case 'close':
      if (cur && cur.id === m.id) close();
      break;
    default:
  }
};

postMessage({ t: 'ready', decoder: typeof VideoDecoder === 'function' && typeof EncodedVideoChunk === 'function' });
