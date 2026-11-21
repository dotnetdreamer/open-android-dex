// This computer's microphone, for Linux (viewer.js): the browser's samples in,
// and out to the page every 10 ms as the message the phone writes into
// Linux's microphone (LinuxStreamMic.java): 0x13, then 480 samples of s16le,
// 48 kHz, one channel. A context at another rate is resampled here, linearly,
// which a voice does not mind. Runs on the browser's audio thread, so a busy
// page never makes a gap in what Linux hears.

const OUT_RATE = 48000;
const PER_MESSAGE = OUT_RATE / 100;
const MSG_MIC = 0x13;

class MicTap extends AudioWorkletProcessor {
  constructor() {
    super();
    this.step = sampleRate / OUT_RATE; // input samples per output sample
    this.pos = 0; // where the next output sample falls, in input samples from the last block's start
    this.last = 0; // the last input sample of the block before
    this.out = new DataView(new ArrayBuffer(1 + PER_MESSAGE * 2));
    this.n = 0;
    this.out.setUint8(0, MSG_MIC);
  }

  put(v) {
    const c = v < -1 ? -1 : v > 1 ? 1 : v;
    this.out.setInt16(1 + this.n * 2, c < 0 ? c * 0x8000 : c * 0x7fff, true);
    if (++this.n === PER_MESSAGE) {
      const buf = this.out.buffer;
      this.port.postMessage(new Uint8Array(buf), [buf]);
      this.out = new DataView(new ArrayBuffer(1 + PER_MESSAGE * 2));
      this.out.setUint8(0, MSG_MIC);
      this.n = 0;
    }
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch || !ch.length) return true;
    if (this.step === 1) {
      for (let i = 0; i < ch.length; i++) this.put(ch[i]);
      return true;
    }
    // Between the sample before this block (index -1) and each in it.
    while (this.pos < ch.length - 1) {
      const i = Math.floor(this.pos);
      const f = this.pos - i;
      const a = i < 0 ? this.last : ch[i];
      const b = ch[i + 1];
      this.put(a + (b - a) * f);
      this.pos += this.step;
    }
    this.last = ch[ch.length - 1];
    this.pos -= ch.length;
    return true;
  }
}

registerProcessor('mic-tap', MicTap);
