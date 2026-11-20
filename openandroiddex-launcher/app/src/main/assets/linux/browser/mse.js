// The picture through the browser's own video player (Media Source
// Extensions), for a page that is not given WebCodecs: one opened over plain
// http, since browsers keep VideoDecoder to secure pages, or a browser too old
// to have it. viewer.js uses WebCodecs wherever it can and this only where it
// cannot.
//
// Each frame from the phone becomes a fragment of its own (moof + mdat) and is
// appended as it arrives, in 'sequence' mode with the frame's length at the
// stream's rate, so that a desktop that stops moving for a minute leaves no
// gap in the player's timeline: the player waits at the end of what it has
// and shows the next frame the moment it lands. A player keeps a little in
// hand to play smoothly, and that is its cost: measured in Edge at 60 Hz, a
// frame is on screen about 50 ms after it arrives where WebCodecs takes 20.
// What it holds beyond about three frames is worked off by playing a little
// faster, and a backlog of over half a second is skipped at the next key
// frame, which is asked for.
//
// Pure but for the player, and the boxes (initSegment, mediaSegment) pure
// entirely: test/linux-browser-test.mjs checks them under plain Node.

import { avcC, codecString, sameBytes } from './h264.js';

const te = new TextEncoder();

function concat(parts) {
  let n = 0;
  for (const p of parts) n += p.length;
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

function box(type, ...payload) {
  const body = concat(payload);
  const out = new Uint8Array(8 + body.length);
  new DataView(out.buffer).setUint32(0, out.length);
  out.set(te.encode(type), 4);
  out.set(body, 8);
  return out;
}

function full(type, version, flags, ...payload) {
  return box(type, new Uint8Array([version, (flags >> 16) & 0xff, (flags >> 8) & 0xff, flags & 0xff]), ...payload);
}

function u32(...v) {
  const b = new Uint8Array(v.length * 4);
  const d = new DataView(b.buffer);
  v.forEach((x, i) => d.setUint32(i * 4, x >>> 0));
  return b;
}

function u16(...v) {
  const b = new Uint8Array(v.length * 2);
  const d = new DataView(b.buffer);
  v.forEach((x, i) => d.setUint16(i * 2, x & 0xffff));
  return b;
}

const zeros = (n) => new Uint8Array(n);
const MATRIX = u32(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000);

/** Ticks a second in the fragments' times. */
export const TIMESCALE = 1_000_000;

/**
 * ftyp + moov for one H.264 track (ISO/IEC 14496-12, -15). No duration and
 * no mehd: a live stream, which is how Chromium knows to hold no more frames
 * back than it must.
 */
export function initSegment(avcc, width, height) {
  const ftyp = box('ftyp', te.encode('iso5'), u32(512), te.encode('iso5'), te.encode('iso6'), te.encode('mp41'));
  const mvhd = full('mvhd', 0, 0, u32(0, 0, 1000, 0), u32(0x00010000), u16(0x0100), zeros(10), MATRIX, zeros(24), u32(2));
  const tkhd = full('tkhd', 0, 3, u32(0, 0, 1, 0, 0), zeros(16), MATRIX, u32(width << 16, height << 16));
  const mdhd = full('mdhd', 0, 0, u32(0, 0, TIMESCALE, 0), u16(0x55c4, 0));
  const hdlr = full('hdlr', 0, 0, u32(0), te.encode('vide'), zeros(12), te.encode('Linux\0'));
  const vmhd = full('vmhd', 0, 1, zeros(8));
  const dinf = box('dinf', full('dref', 0, 0, u32(1), full('url ', 0, 1)));
  const avc1 = box('avc1', zeros(6), u16(1), zeros(16), u16(width, height), u32(0x00480000, 0x00480000, 0), u16(1),
    zeros(32), u16(0x18, 0xffff), box('avcC', avcc));
  const stbl = box('stbl', full('stsd', 0, 0, u32(1), avc1), full('stts', 0, 0, u32(0)), full('stsc', 0, 0, u32(0)),
    full('stsz', 0, 0, u32(0, 0)), full('stco', 0, 0, u32(0)));
  const trak = box('trak', tkhd, box('mdia', mdhd, hdlr, box('minf', vmhd, dinf, stbl)));
  const mvex = box('mvex', full('trex', 0, 0, u32(1, 1, 0, 0, 0)));
  return concat([ftyp, box('moov', mvhd, trak, mvex)]);
}

/** One frame: moof (its number, time, length, and whether it is a key frame) and mdat (the frame, in the AVC form). */
export function mediaSegment(seq, baseTime, duration, sample, key) {
  const mfhd = full('mfhd', 0, 0, u32(seq));
  const tfhd = full('tfhd', 0, 0x020000, u32(1)); // default-base-is-moof, track 1
  const tfdt = full('tfdt', 1, 0, u32(Math.floor(baseTime / 2 ** 32), baseTime >>> 0));
  // data offset, duration, size and flags present; the offset is filled in below
  const trun = full('trun', 0, 0x000701, u32(1, 0, duration, sample.length, key ? 0x02000000 : 0x01010000));
  const moof = box('moof', mfhd, box('traf', tfhd, tfdt, trun));
  // trun is moof's last box, and its data offset 16 bytes into it.
  new DataView(moof.buffer).setUint32(moof.length - 16, moof.length + 8);
  const head = new Uint8Array(8);
  new DataView(head.buffer).setUint32(0, 8 + sample.length);
  head.set(te.encode('mdat'), 4);
  return concat([moof, head, sample]);
}

/** The width and height an SPS describes (ITU-T H.264 7.3.2.1.1), or null if it cannot be read. */
export function spsSize(sps) {
  // Exp-Golomb over the RBSP: emulation prevention bytes out first.
  const rbsp = [];
  for (let i = 1; i < sps.length; i++) {
    if (i + 2 < sps.length && sps[i] === 0 && sps[i + 1] === 0 && sps[i + 2] === 3) {
      rbsp.push(0, 0);
      i += 2;
    } else {
      rbsp.push(sps[i]);
    }
  }
  let bit = 0;
  const u = (n) => {
    let v = 0;
    for (let k = 0; k < n; k++) {
      const byte = rbsp[bit >> 3];
      if (byte === undefined) throw new Error('short SPS');
      v = (v << 1) | ((byte >> (7 - (bit & 7))) & 1);
      bit++;
    }
    return v;
  };
  const ue = () => {
    let zeros = 0;
    while (u(1) === 0) zeros++;
    return (1 << zeros) - 1 + (zeros ? u(zeros) : 0);
  };
  const se = () => {
    const k = ue();
    return k & 1 ? (k + 1) >> 1 : -(k >> 1);
  };
  try {
    const profile = u(8);
    u(16); // constraint flags, level
    ue(); // seq_parameter_set_id
    let chroma = 1;
    if ([100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].includes(profile)) {
      chroma = ue();
      if (chroma === 3) u(1);
      ue();
      ue();
      u(1);
      if (u(1)) {
        for (let i = 0; i < (chroma !== 3 ? 8 : 12); i++) {
          if (u(1)) {
            let last = 8;
            let next = 8;
            for (let j = 0; j < (i < 6 ? 16 : 64); j++) {
              if (next !== 0) next = (last + se() + 256) % 256;
              last = next === 0 ? last : next;
            }
          }
        }
      }
    }
    ue(); // log2_max_frame_num_minus4
    const poc = ue();
    if (poc === 0) ue();
    else if (poc === 1) {
      u(1);
      se();
      se();
      const n = ue();
      for (let i = 0; i < n; i++) se();
    }
    ue(); // max_num_ref_frames
    u(1);
    const wMbs = ue() + 1;
    const hMapUnits = ue() + 1;
    const frameMbsOnly = u(1);
    if (!frameMbsOnly) u(1);
    u(1);
    let [l, r, t, b] = [0, 0, 0, 0];
    if (u(1)) {
      l = ue();
      r = ue();
      t = ue();
      b = ue();
    }
    const cropX = chroma === 0 ? 1 : 2;
    const cropY = (chroma === 0 || chroma === 3 ? 1 : 2) * (2 - frameMbsOnly);
    return [wMbs * 16 - (l + r) * cropX, hMapUnits * 16 * (2 - frameMbsOnly) - (t + b) * cropY];
  } catch {
    return null;
  }
}

/**
 * The picture on a <video> that a MediaSource feeds. {@code onFrame(meta,
 * arrivedAt)} for each frame the player puts on screen, with the time its
 * bytes arrived when it is known; {@code askKey()} when it needs a key frame
 * to go on from; {@code onFail(why)} when it cannot play this stream at all.
 */
export class MsePlayer {
  constructor(video, { fps = 60, onFrame, askKey, onFail }) {
    this.video = video;
    this.dur = Math.round(TIMESCALE / fps);
    this.onFrame = onFrame;
    this.askKey = askKey;
    this.onFail = onFail;
    this.ms = null;
    this.sb = null;
    this.queue = [];
    this.seq = 0;
    this.timeline = 0; // where the next frame lands, in TIMESCALE ticks
    this.description = null;
    this.type = '';
    this.needKey = true;
    this.jumpAtKey = false;
    this.started = false;
    this.arrivals = new Map(); // a frame's start, in ms of media time → when it arrived
    this.closed = false;
    this.lastEvict = 0;
    video.muted = true;
    video.playsInline = true;
    video.disablePictureInPicture = true;
    video.disableRemotePlayback = true;
    this.frameCb = (now, meta) => {
      if (this.closed) return;
      const key = Math.round(meta.mediaTime * 1000);
      const at = this.arrivals.get(key);
      this.onFrame(meta, at);
      video.requestVideoFrameCallback(this.frameCb);
    };
    video.requestVideoFrameCallback(this.frameCb);
    this.trimTimer = setInterval(() => this.trim(), 100);
    this.onError = () => this.reset('the player stopped');
    video.addEventListener('error', this.onError);
    // A player gathers frames before it starts, and would keep that much in
    // hand for good: as it starts, it goes to the newest, which is cheap then
    // (the stream's key frame is a moment old).
    this.atEdge = false;
    this.onPlaying = () => {
      if (this.atEdge || !video.buffered.length) return;
      this.atEdge = true;
      const end = video.buffered.end(video.buffered.length - 1);
      if (end - video.currentTime > 0.1) video.currentTime = Math.max(0, end - 0.05);
    };
    video.addEventListener('playing', this.onPlaying);
  }

  static supported() {
    return typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('video/mp4; codecs="avc1.42e01f"');
  }

  /** A frame from the phone: {@code data} in the AVC form, with the stream's current parameter sets. */
  push(data, key, sps, pps, arrivedAt) {
    if (this.closed) return;
    if (this.needKey && !key) {
      this.askKey();
      return;
    }
    if (key && sps && pps) {
      const d = avcC(sps, pps);
      if (!sameBytes(d, this.description)) {
        const type = `video/mp4; codecs="${codecString(sps)}"`;
        if (!MediaSource.isTypeSupported(type)) {
          this.onFail(`this browser cannot play ${codecString(sps)}`);
          return;
        }
        const size = spsSize(sps) || [this.video.videoWidth || 1280, this.video.videoHeight || 720];
        this.description = d;
        this.queue.push({ kind: 'init', type, bytes: initSegment(d, size[0], size[1]) });
      }
    }
    if (!this.description) {
      this.askKey();
      return;
    }
    // Behind by more than half a second of frames not yet in the player: the
    // player cannot keep up. Start again from a key frame rather than grow.
    const waiting = this.queue.filter((q) => q.kind === 'frame').length;
    if (waiting > 30 && !key) {
      this.queue = this.queue.filter((q) => q.kind !== 'frame');
      this.needKey = true;
      this.askKey();
      return;
    }
    this.needKey = false;
    const start = this.timeline;
    this.timeline += this.dur;
    this.arrivals.set(Math.round((start / TIMESCALE) * 1000), arrivedAt);
    if (this.arrivals.size > 600) {
      const first = this.arrivals.keys().next().value;
      this.arrivals.delete(first);
    }
    this.queue.push({ kind: 'frame', key, start, bytes: mediaSegment(++this.seq, start, this.dur, data, key) });
    this.open();
    this.pump();
  }

  open() {
    if (this.ms) return;
    const ms = new MediaSource();
    this.ms = ms;
    this.video.src = URL.createObjectURL(ms);
    ms.addEventListener(
      'sourceopen',
      () => {
        URL.revokeObjectURL(this.video.src);
        this.pump();
      },
      { once: true },
    );
  }

  pump() {
    const ms = this.ms;
    if (!ms || ms.readyState !== 'open' || this.closed) return;
    if (this.sb && this.sb.updating) return;
    const next = this.queue.shift();
    if (!next) return;
    try {
      if (next.kind === 'init') {
        if (!this.sb) {
          this.sb = ms.addSourceBuffer(next.type);
          this.sb.mode = 'sequence';
          this.sb.addEventListener('updateend', () => this.appended());
          this.sb.addEventListener('error', () => this.reset('a fragment was refused'));
        } else if (next.type !== this.type && this.sb.changeType) {
          this.sb.changeType(next.type);
        }
        this.type = next.type;
        this.sb.appendBuffer(next.bytes);
        return;
      }
      if (!this.sb) return; // a frame before any parameter sets: dropped
      if (next.key && this.jumpAtKey) {
        this.jumpAtKey = false;
        this.jumpTo = next.start;
      }
      this.sb.appendBuffer(next.bytes);
    } catch (e) {
      // QuotaExceededError or a closed source: from the top, at a key frame.
      this.reset(e && e.name ? e.name : 'append failed');
    }
  }

  appended() {
    if (this.closed) return;
    const v = this.video;
    if (this.jumpTo !== undefined) {
      v.currentTime = this.jumpTo / TIMESCALE;
      this.jumpTo = undefined;
    }
    if (!this.started && v.buffered.length) {
      this.started = true;
      v.play().catch(() => {});
    } else if (v.paused) {
      v.play().catch(() => {});
    }
    this.evict();
    this.pump();
  }

  /** Keep about three frames in hand: work off more by playing faster, skip a big backlog at a key frame. */
  trim() {
    const v = this.video;
    if (this.closed || !v.buffered.length) return;
    const end = v.buffered.end(v.buffered.length - 1);
    const behind = end - v.currentTime;
    if (behind > 0.6 && !this.jumpAtKey && this.jumpTo === undefined) {
      this.jumpAtKey = true;
      this.askKey();
    }
    const rate = behind > 0.2 ? 1.25 : behind > 0.08 ? 1.08 : 1;
    if (v.playbackRate !== rate) v.playbackRate = rate;
  }

  /** What has been shown, more than twenty seconds back, out of the player's memory. */
  evict() {
    const v = this.video;
    const now = performance.now();
    if (now - this.lastEvict < 10000 || !this.sb || this.sb.updating || v.currentTime < 30) return;
    this.lastEvict = now;
    try {
      this.sb.remove(0, v.currentTime - 20);
    } catch {
      // updating after all: the next time round
    }
  }

  /** Something went wrong in the player: a new one, from the next key frame. */
  reset(why) {
    if (this.closed) return;
    this.teardown();
    this.queue = [];
    this.description = null;
    this.needKey = true;
    this.started = false;
    this.timeline = 0;
    this.arrivals.clear();
    this.failures = (this.failures || 0) + 1;
    if (this.failures > 5) {
      this.onFail(why);
      return;
    }
    this.askKey();
  }

  teardown() {
    const v = this.video;
    if (this.sb && this.ms && this.ms.readyState === 'open') {
      try {
        this.ms.endOfStream();
      } catch {
        // closing anyway
      }
    }
    this.sb = null;
    this.ms = null;
    this.type = '';
    this.jumpAtKey = false;
    this.jumpTo = undefined;
    this.atEdge = false;
    v.removeAttribute('src');
    v.load();
  }

  close() {
    if (this.closed) return;
    this.teardown();
    this.closed = true;
    clearInterval(this.trimTimer);
    this.video.removeEventListener('error', this.onError);
    this.video.removeEventListener('playing', this.onPlaying);
    this.queue = [];
  }
}
