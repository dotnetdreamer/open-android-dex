/**
 * The phone's sound in the phone window (Remote desktop): what PhoneStream
 * sends after {"t":"sound"} as binary 0x03 runs, played with Web Audio. The
 * same player as the phone's page (sound.js in the launcher's assets, on
 * Linux's page's viewer.js): Opus through WebCodecs' AudioDecoder, PCM
 * otherwise; each run played as it lands, a little ahead, with as much in
 * hand as the gaps between runs say is needed, and a run that would play far
 * behind its picture dropped instead.
 */

const RATE = 48000;
const CODEC_OPUS = 1;
const SOUND_MIN = 0.015;
const SOUND_MAX = 0.35;
const SOUND_SLACK = 0.08;

export async function canOpus(): Promise<boolean> {
  if (typeof AudioDecoder === "undefined") return false;
  try {
    return (await AudioDecoder.isConfigSupported({ codec: "opus", sampleRate: RATE, numberOfChannels: 2 })).supported === true;
  } catch {
    return false;
  }
}

export type SoundPlayer = ReturnType<typeof createSoundPlayer>;

export function createSoundPlayer(onState: (playing: boolean) => void = () => {}) {
  let actx: AudioContext | null = null;
  let opus = false;
  let fmt = { codec: "pcm", rate: RATE, channels: 2, kbps: 1536 };
  let dec: AudioDecoder | null = null;
  let nextAt = 0;
  let lastRunAt = 0;
  let runGap = 0;
  let lastRunSilent = false;
  let settleUntil = 0;
  let runs = 0;
  let loudRuns = 0;
  let underruns = 0;
  let dropped = 0;

  const playing = () => !!actx && actx.state === "running";

  const closeDecoder = () => {
    const d = dec;
    dec = null;
    if (d && d.state !== "closed") {
      try {
        d.close();
      } catch {
        // closed
      }
    }
  };

  const play = (buf: AudioBuffer, silent: boolean) => {
    if (!actx) return;
    const t = performance.now();
    const gap = lastRunAt ? (t - lastRunAt) / 1000 : 0;
    lastRunAt = t;
    const afterSilence = lastRunSilent;
    if (gap < 0.5 && !afterSilence && t > settleUntil) runGap = Math.max(gap, runGap * 0.5 ** (gap / 2));
    lastRunSilent = silent;
    const cushion = Math.min(SOUND_MAX, Math.max(SOUND_MIN, runGap * 1.2 + 0.005));
    const now = actx.currentTime;
    const ahead = nextAt - now;
    if (ahead < 0.003) {
      if (nextAt && !afterSilence) underruns++;
      nextAt = now + cushion;
    } else if (ahead > cushion + SOUND_SLACK) {
      dropped++;
      return;
    }
    const src = actx.createBufferSource();
    src.buffer = buf;
    src.connect(actx.destination);
    src.start(nextAt);
    nextAt += buf.duration;
    runs++;
    if (!silent) loudRuns++;
  };

  const loudIn = (buf: AudioBuffer) => {
    for (let c = 0; c < buf.numberOfChannels; c++) {
      const x = buf.getChannelData(c);
      for (let i = 0; i < x.length; i++) if (x[i] > 1e-4 || x[i] < -1e-4) return true;
    }
    return false;
  };

  const playDecoded = (ad: AudioData) => {
    try {
      if (!playing() || !actx) return;
      const ch = ad.numberOfChannels;
      const buf = actx.createBuffer(ch, ad.numberOfFrames, ad.sampleRate);
      try {
        for (let c = 0; c < ch; c++) ad.copyTo(buf.getChannelData(c), { planeIndex: c, format: "f32-planar" });
      } catch {
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
  };

  return {
    async init() {
      opus = await canOpus();
    },
    codecs: () => (opus ? ["opus", "pcm"] : ["pcm"]),
    playing,
    /** In a click or a key, or anywhere the window allows sound to start. */
    start() {
      if (!actx) {
        try {
          actx = new AudioContext({ latencyHint: "interactive", sampleRate: RATE });
        } catch {
          actx = null;
          return;
        }
        actx.onstatechange = () => onState(playing());
      }
      if (actx.state === "suspended") actx.resume().then(() => onState(playing()), () => {});
      else onState(playing());
    },
    stop() {
      const c = actx;
      actx = null;
      nextAt = 0;
      closeDecoder();
      if (c) c.close().catch(() => {});
      onState(false);
    },
    format(m: Record<string, unknown>) {
      fmt = {
        codec: m.codec === "opus" ? "opus" : "pcm",
        rate: Number(m.rate) || RATE,
        channels: Number(m.channels) === 1 ? 1 : 2,
        kbps: Number(m.kbps) || 0,
      };
      closeDecoder();
      if (fmt.codec !== "opus" || typeof AudioDecoder === "undefined") return;
      try {
        dec = new AudioDecoder({ output: playDecoded, error: () => closeDecoder() });
        dec.configure({ codec: "opus", sampleRate: fmt.rate, numberOfChannels: fmt.channels });
      } catch {
        dec = null;
      }
    },
    run(codec: number, pts: number, data: Uint8Array) {
      if (!playing() || !actx) return;
      if (codec === CODEC_OPUS) {
        if (!dec || dec.state !== "configured") return;
        try {
          dec.decode(new EncodedAudioChunk({ type: "key", timestamp: pts, data }));
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
    settle() {
      runGap = 0;
      settleUntil = performance.now() + 1000;
    },
    stats: () => ({ state: actx ? actx.state : "none", runs, loudRuns, underruns, dropped, opus, format: fmt.codec }),
  };
}
