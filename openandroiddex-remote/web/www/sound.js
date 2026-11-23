// The phone's sound in a page (Remote desktop): what PhoneStream sends after
// {"t":"sound"} as binary 0x03 runs, played with Web Audio. The same player
// as Linux's page (viewer.js): Opus through WebCodecs' AudioDecoder where the
// page has it (an https page), PCM otherwise; each run played as it lands, a
// little ahead, with as much in hand as the gaps between runs say is needed,
// and a run that would play far behind its picture dropped instead.
//
// A browser starts sound only in a click or a key: start() is called from
// one, and the page tells the phone it wants the sound only while it can play
// it (playing()), so the phone reads and sends nothing before that.

const RATE = 48000;
const CODEC_OPUS = 1;
/** Sound in hand: at least this, and at most SOUND_MAX however bursty the link. */
const SOUND_MIN = 0.015;
const SOUND_MAX = 0.35;
/** In hand beyond what is needed by this much: runs are dropped until it is back. */
const SOUND_SLACK = 0.08;

export async function canOpus() {
  if (typeof AudioDecoder === 'undefined') return false;
  try {
    return (await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: RATE, numberOfChannels: 2 })).supported === true;
  } catch {
    return false;
  }
}

/**
 * A player. onState runs when what the page can play changes (its
 * AudioContext started or stopped), so the page can tell the phone.
 */
export function createSoundPlayer({ onState = () => {} } = {}) {
  let actx = null;
  let opus = false;
  let fmt = { codec: 'pcm', rate: RATE, channels: 2, kbps: 1536, mode: '' };
  let dec = null;
  let nextAt = 0;
  let lastRunAt = 0;
  let runGap = 0;
  let lastRunSilent = false;
  let settleUntil = 0;
  let runs = 0, underruns = 0, dropped = 0, lead = 0, cushion = 0, loudRuns = 0;

  const playing = () => !!actx && actx.state === 'running';

  function closeDecoder() {
    const d = dec;
    dec = null;
    if (d && d.state !== 'closed') {
      try { d.close(); } catch { /* closed */ }
    }
  }

  function play(buf, silent) {
    const t = performance.now();
    const gap = lastRunAt ? (t - lastRunAt) / 1000 : 0;
    lastRunAt = t;
    // The longest gap lately, forgotten by half every two seconds; not a gap
    // after silence (the phone holds silence back), nor one over half a
    // second (the link stopping, not a burst).
    const afterSilence = lastRunSilent;
    if (gap < 0.5 && !afterSilence && t > settleUntil) runGap = Math.max(gap, runGap * 0.5 ** (gap / 2));
    lastRunSilent = silent;
    cushion = Math.min(SOUND_MAX, Math.max(SOUND_MIN, runGap * 1.2 + 0.005));
    const now = actx.currentTime;
    const ahead = nextAt - now;
    if (ahead < 0.003) {
      if (nextAt && !afterSilence) underruns++;
      nextAt = now + cushion;
    } else if (ahead > cushion + SOUND_SLACK) {
      dropped++;
      return;
    }
    lead = lead * 0.9 + (nextAt - now) * 0.1;
    const src = actx.createBufferSource();
    src.buffer = buf;
    src.connect(actx.destination);
    src.start(nextAt);
    nextAt += buf.duration;
    runs++;
    if (!silent) loudRuns++;
  }

  function loudIn(buf) {
    for (let c = 0; c < buf.numberOfChannels; c++) {
      const x = buf.getChannelData(c);
      for (let i = 0; i < x.length; i++) if (x[i] > 1e-4 || x[i] < -1e-4) return true;
    }
    return false;
  }

  function playDecoded(ad) {
    try {
      if (!playing()) return;
      const ch = ad.numberOfChannels;
      const buf = actx.createBuffer(ch, ad.numberOfFrames, ad.sampleRate);
      try {
        for (let c = 0; c < ch; c++) ad.copyTo(buf.getChannelData(c), { planeIndex: c, format: 'f32-planar' });
      } catch {
        // A browser that converts no formats: its own, interleaved, spread by hand.
        const all = new Float32Array(ad.numberOfFrames * ch);
        ad.copyTo(all, { planeIndex: 0 });
        for (let c = 0; c < ch; c++) {
          const out = buf.getChannelData(c);
          for (let i = 0; i < ad.numberOfFrames; i++) out[i] = all[i * ch + c];
        }
      }
      play(buf, !loudIn(buf));
    } finally {
      ad.close();
    }
  }

  return {
    async init() { opus = await canOpus(); },
    codecs: () => (opus ? ['opus', 'pcm'] : ['pcm']),
    playing,
    /** In a click or a key: the moment a browser lets a page start sound. */
    start() {
      if (!actx) {
        try {
          actx = new AudioContext({ latencyHint: 'interactive', sampleRate: RATE });
        } catch {
          actx = null;
          return;
        }
        actx.onstatechange = () => onState(playing());
      }
      if (actx.state === 'suspended') actx.resume().then(() => onState(playing()), () => {});
      else onState(playing());
    },
    /** No sound here: the context goes, and what was in hand with it. */
    stop() {
      const c = actx;
      actx = null;
      nextAt = 0;
      closeDecoder();
      if (c) c.close().catch(() => {});
      onState(false);
    },
    /** The phone's word on what follows: Opus gets a decoder, PCM none. */
    format(m) {
      fmt = {
        codec: m.codec === 'opus' ? 'opus' : 'pcm',
        rate: Number(m.rate) || RATE,
        channels: Number(m.channels) === 1 ? 1 : 2,
        kbps: Number(m.kbps) || 0,
        mode: String(m.mode || ''),
      };
      closeDecoder();
      if (fmt.codec !== 'opus' || typeof AudioDecoder === 'undefined') return;
      try {
        dec = new AudioDecoder({ output: playDecoded, error: () => closeDecoder() });
        dec.configure({ codec: 'opus', sampleRate: fmt.rate, numberOfChannels: fmt.channels });
      } catch {
        dec = null;
      }
    },
    /** One run from the phone: an Opus packet to decode, or PCM at the said rate and channels. */
    run(codec, pts, data) {
      if (!playing()) return;
      if (codec === CODEC_OPUS) {
        if (!dec || dec.state !== 'configured') return;
        try {
          dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: pts, data }));
        } catch {
          closeDecoder();
        }
        return;
      }
      const ch = fmt.channels;
      const frames = Math.floor(data.length / (2 * ch));
      if (!frames) return;
      const buf = actx.createBuffer(ch, frames, fmt.rate);
      const d = new DataView(data.buffer, data.byteOffset, data.byteLength);
      let loud = false;
      for (let c = 0; c < ch; c++) {
        const out = buf.getChannelData(c);
        for (let i = 0, o = c * 2; i < frames; i++, o += 2 * ch) {
          const v = d.getInt16(o, true);
          if (v > 1 || v < -1) loud = true;
          out[i] = v / 32768;
        }
      }
      play(buf, !loud);
    },
    /**
     * The tab is in sight again: the sound in hand grew to bridge a hidden
     * tab's bursts, so it is forgotten, and the next second's gaps (the
     * backlog arriving) say nothing of the link.
     */
    settle() {
      runGap = 0;
      settleUntil = performance.now() + 1000;
    },
    describe() {
      if (fmt.codec === 'opus') return `Opus ${fmt.kbps} kb/s ${fmt.channels === 1 ? 'mono' : 'stereo'}`;
      return `${Math.round(fmt.rate / 1000)} kHz ${fmt.channels === 1 ? 'mono' : 'stereo'}`;
    },
    /** For the tests' eyes. */
    stats: () => ({
      state: actx ? actx.state : 'none', runs, loudRuns, underruns, dropped, opus,
      format: fmt.codec, decoder: dec ? dec.state : 'none',
      leadMs: Math.round(lead * 1000), cushionMs: Math.round(cushion * 1000), gapMs: Math.round(runGap * 1000),
    }),
  };
}
