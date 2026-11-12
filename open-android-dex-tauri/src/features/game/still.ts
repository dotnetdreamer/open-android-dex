import { avcC, codecString, splitAccessUnit } from "../remote/usb/h264";

/**
 * The game's picture as it was a moment ago, for the editor to place controls
 * over: game_still's bytes — a big-endian u32 with the stream's configuration's
 * length, that configuration (SPS and PPS), then one key frame, Annex B — decoded
 * with WebCodecs, as the USB desktop's stream is (UsbDesktop.tsx).
 *
 * Null where this webview has no decoder, or the bytes do not decode: the
 * editor says the picture can't be shown and the controls are placed by eye.
 */
export async function decodeStill(buf: ArrayBuffer): Promise<VideoFrame | null> {
  if (typeof VideoDecoder === "undefined" || typeof EncodedVideoChunk === "undefined" || buf.byteLength < 4) return null;
  const view = new DataView(buf);
  const n = view.getUint32(0);
  if (4 + n > buf.byteLength) return null;
  const bytes = new Uint8Array(buf);
  // The configuration and the frame as one buffer: the parameter sets may be in either.
  const unit = splitAccessUnit(bytes.subarray(4));
  if (!unit.sps || !unit.pps || !unit.key || !unit.data.length) return null;
  const codec = codecString(unit.sps);
  const description = avcC(unit.sps, unit.pps);
  try {
    const ok = await VideoDecoder.isConfigSupported({ codec, description });
    if (!ok.supported) return null;
  } catch {
    return null;
  }
  return new Promise<VideoFrame | null>((resolve) => {
    let got: VideoFrame | null = null;
    const decoder = new VideoDecoder({
      output: (frame) => {
        if (got) frame.close();
        else got = frame;
      },
      error: () => resolve(null),
    });
    try {
      decoder.configure({ codec, description, optimizeForLatency: true });
      decoder.decode(new EncodedVideoChunk({ type: "key", timestamp: 0, data: unit.data }));
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
