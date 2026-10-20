/**
 * How old each frame is when a desktop window shows it, for the window's
 * status pill — the number every change to the video path is measured by.
 *
 * Both phones' streams stamp each frame with the phone's own clock
 * (CLOCK_MONOTONIC, microseconds): the Android desktop when its compositor
 * made the frame (openandroiddex-wmd, DesktopVideo), the Linux desktop when the
 * picture went into the encoder (LinuxStreamEncoder). Each pong says what that
 * clock reads ("at"). Half way through the ping's round trip is the best guess
 * of when it read it, and the ping with the shortest round trip is the guess
 * with the least room for error — so that one says where the phone's clock is
 * against this page's, and a frame's age is now minus its stamp, moved over.
 * What a frame's age leaves out is the phone's own before the stamp, and this
 * screen's after the draw: a frame on the way, not a photon.
 *
 * Pure, so test/dex-video-test.mjs runs this very file.
 */
export class FrameClock {
  /** The phone's clock minus this page's, in ms, and the round trip it was read across. */
  private best: { rtt: number; offset: number } | null = null;

  /** A pong for a ping sent at `sentAt` (performance.now()), back at `now`, the phone's clock at `phoneUs`. */
  pong(sentAt: number, now: number, phoneUs: unknown) {
    const rtt = now - sentAt;
    if (typeof phoneUs !== "number" || !Number.isFinite(phoneUs) || !(rtt >= 0)) return;
    const offset = phoneUs / 1000 - (sentAt + rtt / 2);
    // A best from long ago gives way slowly, so a link that is slower now — or
    // a phone clock that drifted — is followed within a minute or so.
    if (this.best) this.best.rtt += 0.02;
    if (!this.best || rtt <= this.best.rtt) this.best = { rtt, offset };
  }

  /** The phone's pongs carry its clock: frames can be timed. */
  get known() {
    return this.best !== null;
  }

  /** Milliseconds since the phone stamped a frame `ptsUs`, as of `now`; null before the clock is known. */
  age(ptsUs: number, now: number): number | null {
    if (!this.best) return null;
    return now - (ptsUs / 1000 - this.best.offset);
  }
}

/** The middle one of a second's samples, and the samples let go. */
export function median(samples: number[]): number | null {
  if (!samples.length) return null;
  const s = [...samples].sort((a, b) => a - b);
  return s[s.length >> 1];
}
