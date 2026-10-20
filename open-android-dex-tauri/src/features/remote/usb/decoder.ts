import { avcC, codecString, sameBytes, splitAccessUnit } from "./h264";

/**
 * A phone's H.264, decoded on this computer's GPU (WebCodecs) and handed to
 * `draw` frame by frame as it comes out — the Android desktop's window
 * (DexWindow.tsx), the same steps the Linux desktop's takes (UsbDesktop.tsx).
 *
 * The decoder is configured from the parameter sets in front of each key frame
 * and made again whenever they change (a new encoder: a resize, a new size of
 * screen). It never falls behind: a queue of more than a few frames is a
 * decoder that cannot keep up, and it starts over at the next key frame rather
 * than show a growing delay. `askKey` is how it asks the phone for one.
 */
export class StreamDecoder {
  private decoder: VideoDecoder | null = null;
  private description: Uint8Array | null = null;
  private sps: Uint8Array | null = null;
  private pps: Uint8Array | null = null;
  private needKey = true;
  private askedAt = 0;

  constructor(
    private readonly sink: {
      draw: (frame: VideoFrame) => void;
      askKey: () => void;
      error: (message: string) => void;
    },
  ) {}

  /** This browser decodes the phone's video at all. */
  static available() {
    return typeof VideoDecoder !== "undefined" && typeof EncodedVideoChunk !== "undefined";
  }

  /** A new encoder on the phone: nothing decodes until its first key frame. */
  restart() {
    this.needKey = true;
  }

  /** One video message: its flags (1 key frame, 2 parameter sets), its pts in µs, H.264 Annex B. */
  push(flags: number, pts: number, payload: Uint8Array) {
    const unit = splitAccessUnit(payload);
    if (unit.sps) this.sps = unit.sps;
    if (unit.pps) this.pps = unit.pps;
    if (!unit.data.length) return; // parameter sets only
    const isKey = unit.key || (flags & 1) !== 0;
    if (isKey && this.sps && this.pps) {
      const d = avcC(this.sps, this.pps);
      if (!this.decoder || this.decoder.state === "closed" || !sameBytes(d, this.description)) {
        if (!this.decoder || this.decoder.state === "closed") this.make();
        this.decoder!.configure({
          codec: codecString(this.sps),
          description: d,
          optimizeForLatency: true,
          hardwareAcceleration: "prefer-hardware",
        });
        this.description = d;
      }
      this.needKey = false;
    }
    const dec = this.decoder;
    if (this.needKey || !dec || dec.state !== "configured") {
      this.askKey();
      return;
    }
    if (dec.decodeQueueSize > 3 && !isKey) {
      this.needKey = true;
      this.askKey();
      return;
    }
    dec.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: unit.data }));
  }

  close() {
    if (this.decoder && this.decoder.state !== "closed") this.decoder.close();
    this.decoder = null;
  }

  private make() {
    this.decoder = new VideoDecoder({
      output: (frame) => this.sink.draw(frame),
      error: (e) => {
        this.sink.error(e.message);
        this.description = null;
        this.needKey = true;
        this.askKey();
      },
    });
  }

  private askKey() {
    const now = performance.now();
    if (now - this.askedAt < 500) return;
    this.askedAt = now;
    this.sink.askKey();
  }
}
