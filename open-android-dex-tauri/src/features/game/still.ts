import { avcC, codecString, splitAccessUnit } from "../remote/usb/h264";

/**
 * The game's picture as it was a moment ago, for the editor to place controls
 * over: game_still's bytes — a big-endian u32 with the stream's configuration's
 * length, that configuration (SPS and PPS), then each frame with a u32 length:
 * a key frame and the frames after it, Annex B — decoded with WebCodecs, as the
 * USB desktop's stream is (UsbDesktop.tsx), the last frame out being the picture.
 *
 * Null where this webview has no decoder, or the bytes do not decode: the
 * editor says the picture can't be shown and the controls are placed by eye.
 */
export async function decodeStill(buf: ArrayBuffer): Promise<VideoFrame | null> {
  if (typeof VideoDecoder === "undefined" || typeof EncodedVideoChunk === "undefined" || buf.byteLength < 4) return null;
  const view = new DataView(buf);
  const bytes = new Uint8Array(buf);
  const n = view.getUint32(0);
  if (4 + n > buf.byteLength) return null;
  const config = bytes.subarray(4, 4 + n);
  // Then each frame, a u32 length first: a key frame, and the frames after it.
  const frames: Uint8Array[] = [];
  for (let at = 4 + n; at + 4 <= buf.byteLength; ) {
    const len = view.getUint32(at);
    if (at + 4 + len > buf.byteLength) return null;
    frames.push(bytes.subarray(at + 4, at + 4 + len));
    at += 4 + len;
  }
  if (!frames.length) return null;
  // The configuration and the key frame as one buffer: the parameter sets may be in either.
  const first = new Uint8Array(config.length + frames[0].length);
  first.set(config);
  first.set(frames[0], config.length);
  const unit = splitAccessUnit(first);
  if (!unit.sps || !unit.pps || !unit.key || !unit.data.length) return null;
  const after = frames
    .slice(1)
    .map((f) => splitAccessUnit(f).data)
    .filter((d) => d.length);
  const codec = codecString(unit.sps);
  const description = avcC(unit.sps, unit.pps);
  try {
    const ok = await VideoDecoder.isConfigSupported({ codec, description });
    if (!ok.supported) return null;
  } catch {
    return null;
  }
  return new Promise<VideoFrame | null>((resolve) => {
    // The last frame out is the picture now: each earlier one is let go as the next comes.
    let got: VideoFrame | null = null;
    const decoder = new VideoDecoder({
      output: (frame) => {
        got?.close();
        got = frame;
      },
      error: () => {
        got?.close();
        resolve(null);
      },
    });
    try {
      decoder.configure({ codec, description, optimizeForLatency: true });
      decoder.decode(new EncodedVideoChunk({ type: "key", timestamp: 0, data: unit.data }));
      after.forEach((data, i) => decoder.decode(new EncodedVideoChunk({ type: "delta", timestamp: i + 1, data })));
      decoder
        .flush()
        .then(() => resolve(got))
        .catch(() => resolve(null))
        .finally(() => {
          if (decoder.state !== "closed") decoder.close();
        });
    } catch {
      resolve(null);
    }
  });
}
