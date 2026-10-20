/**
 * The little of H.264 the window needs to hand the phone's stream to the
 * browser's decoder (WebCodecs): units out of Annex B, the avcC record a
 * decoder is configured with, and the codec string that names the stream.
 *
 * The phone sends what its encoder makes — Annex B, start codes and all, the
 * parameter sets in front of every key frame (LinuxStreamEncoder.java).
 * WebCodecs takes Annex B in some browsers and not others; the AVC form —
 * each unit behind its length, the parameter sets in a description — it takes
 * in all of them, so that is what this turns the stream into.
 *
 * Pure, so it runs under plain Node in test/usb-desktop-test.mjs.
 */

export const NAL_IDR = 5;
export const NAL_SPS = 7;
export const NAL_PPS = 8;
export const NAL_AUD = 9;

export const nalType = (unit: Uint8Array) => unit[0] & 0x1f;

/** The units in an Annex B buffer, start codes and trailing zeros taken off. */
export function nalUnits(data: Uint8Array): Uint8Array[] {
  const out: Uint8Array[] = [];
  const n = data.length;
  let start = -1;
  let i = 0;
  const close = (end: number) => {
    // A unit never ends in a zero byte (its last bit is the stop bit), so any
    // zeros here are a four-byte start code's lead or trailing padding.
    while (end > start && data[end - 1] === 0) end--;
    if (end > start) out.push(data.subarray(start, end));
  };
  while (i + 2 < n) {
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      if (start >= 0) close(i);
      i += 3;
      start = i;
    } else {
      i++;
    }
  }
  if (start >= 0) close(n);
  return out;
}

const hex2 = (b: number) => b.toString(16).padStart(2, "0");

/** `avc1.PPCCLL` — profile, constraint flags and level, as the SPS gives them. */
export function codecString(sps: Uint8Array): string {
  return `avc1.${hex2(sps[1])}${hex2(sps[2])}${hex2(sps[3])}`;
}

/**
 * The AVCDecoderConfigurationRecord (ISO/IEC 14496-15) for one SPS and one
 * PPS. High profiles carry four more fields, which some decoders insist on;
 * a phone's encoder always makes 8-bit 4:2:0, which is what they say.
 */
export function avcC(sps: Uint8Array, pps: Uint8Array): Uint8Array {
  const profile = sps[1];
  const high = profile === 100 || profile === 110 || profile === 122 || profile === 144;
  const out = new Uint8Array(11 + sps.length + pps.length + (high ? 4 : 0));
  out[0] = 1;
  out[1] = sps[1];
  out[2] = sps[2];
  out[3] = sps[3];
  out[4] = 0xff; // reserved, and four-byte lengths
  out[5] = 0xe1; // reserved, and one SPS
  out[6] = sps.length >> 8;
  out[7] = sps.length & 0xff;
  out.set(sps, 8);
  let o = 8 + sps.length;
  out[o++] = 1; // one PPS
  out[o++] = pps.length >> 8;
  out[o++] = pps.length & 0xff;
  out.set(pps, o);
  o += pps.length;
  if (high) {
    out[o++] = 0xfc | 1; // chroma_format_idc 1: 4:2:0
    out[o++] = 0xf8; // bit_depth_luma_minus8 0
    out[o++] = 0xf8; // bit_depth_chroma_minus8 0
    out[o++] = 0; // no SPS extensions
  }
  return out;
}

/** Units as the AVC form carries them: each behind its length, four bytes, big-endian. */
export function lengthPrefixed(units: Uint8Array[]): Uint8Array {
  let size = 0;
  for (const u of units) size += 4 + u.length;
  const out = new Uint8Array(size);
  let o = 0;
  for (const u of units) {
    out[o] = u.length >>> 24;
    out[o + 1] = (u.length >>> 16) & 0xff;
    out[o + 2] = (u.length >>> 8) & 0xff;
    out[o + 3] = u.length & 0xff;
    out.set(u, o + 4);
    o += 4 + u.length;
  }
  return out;
}

/** Two buffers hold the same bytes. */
export function sameBytes(a: Uint8Array | null, b: Uint8Array | null): boolean {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/**
 * One encoded unit from the phone, sorted: the parameter sets it carries
 * (null when it carries none), and the rest in the AVC form, ready to decode.
 */
export interface Access {
  sps: Uint8Array | null;
  pps: Uint8Array | null;
  /** An IDR is in it: a decoder can start here. */
  key: boolean;
  /** Length-prefixed slices; empty for a unit that was only parameter sets. */
  data: Uint8Array;
}

export function splitAccessUnit(annexB: Uint8Array): Access {
  let sps: Uint8Array | null = null;
  let pps: Uint8Array | null = null;
  let key = false;
  const slices: Uint8Array[] = [];
  for (const u of nalUnits(annexB)) {
    const t = nalType(u);
    if (t === NAL_SPS) sps = u.slice();
    else if (t === NAL_PPS) pps = u.slice();
    else if (t === NAL_AUD) continue;
    else {
      if (t === NAL_IDR) key = true;
      slices.push(u);
    }
  }
  return { sps, pps, key, data: slices.length ? lengthPrefixed(slices) : new Uint8Array(0) };
}
