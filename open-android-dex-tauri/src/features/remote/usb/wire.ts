/**
 * What the window says to the phone's LinuxStream (LinuxStream.java has the
 * whole wire) that the phone reads its link by: the hello, and the
 * acknowledgement of each frame. Kept apart from UsbDesktop.tsx so that the
 * tests send exactly these bytes (test/usb-desktop-test.mjs, and the desktop
 * app's stream in test/linux-browser-test.mjs --device).
 */

export const IN_ACK = 0x12;

/** This computer's clock, in ms: what an acknowledgement says a frame arrived at. */
export const clock = () => performance.timeOrigin + performance.now();

/**
 * "A frame arrived, at this time": the phone keeps no more frames in the pipe
 * than its round trip holds, and reads from the times how late frames come
 * (LinuxStreamRate.java), lowering the bitrate the moment a queue starts to
 * build. A phone older than that reads the first byte only.
 */
export function ackOf(arrivedAt: number): Uint8Array {
  const a = new Uint8Array(9);
  a[0] = IN_ACK;
  new DataView(a.buffer).setFloat64(1, arrivedAt);
  return a;
}

/** "A frame arrived", and nothing more: the acknowledgement off the local network. */
export const PLAIN_ACK = new Uint8Array([IN_ACK]);

/**
 * Whether a route is the local network, where the phone may read the
 * acknowledgements' times: the cable, and Wi-Fi (wireless debugging, or the
 * tunnel on the same network). The phone's rule takes a round trip over
 * 400 ms for a queue (LinuxStreamRate.LAN_RTT_NS), which on the internet or a
 * relay it need not be: a steady 401 ms with nothing queued took the bitrate
 * to its floor (an audit's check, 2026-11-21). There the old rule stays.
 */
export function localRoute(tier: string) {
  return tier === "cable" || tier === "wifi";
}

/**
 * Whether the phone reads the acknowledgements' times on this route: on the
 * local network, and not over wireless debugging. There, on the S25
 * (2026-11-21, six runs of the desktop app's stream turn about with the old
 * rule), the new rule's round trips were no shorter, and at the median
 * longer in five of the six: both rules sat at the 0.5 Mb/s floor, most of
 * the new one's cuts by frames held past the link's own round trip. So the
 * cable and the tunnel on the same network read the times (what the browser's
 * own Wi-Fi measured better), wireless debugging keeps the old rule.
 */
export function readsTimes(tier: string, wirelessAdb: boolean) {
  return localRoute(tier) && !wirelessAdb;
}

/**
 * The window's first word: the desktop's size, the bitrate the route can
 * carry (its ceiling), and whether acknowledgements come with their times
 * (ackAt), so that the phone's bitrate follows how late frames arrive, as a
 * browser's does. A phone older than that ignores ackAt and keeps the rule
 * of a full link.
 */
export function helloOf(start: boolean, w: number, h: number, bitrate: number, ackAt: boolean) {
  return { t: "hello", v: 1, start, w, h, bitrate, fps: 60, acks: true, ackAt };
}

/**
 * The route changed under a live stream: its ceiling, and whether its
 * acknowledgements' times are to be read from here on (the phone turns the
 * new rule on or off; an older phone reads the ceiling only).
 */
export function routeChangedOf(bitrate: number, ackAt: boolean) {
  return { t: "bitrate", bitrate, ackAt };
}
