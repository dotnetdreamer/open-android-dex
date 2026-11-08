/*
 * How big the guest's screen should be for the window it is shown in.
 *
 * A pure function in a module of its own, so that a computer can check it
 * (test/linux-guest-size-test.mjs) against LinuxGuestSize, the launcher's copy,
 * which starts the desktop at the size this page is about to ask for. The two
 * agree to the pixel over a sweep of windows: a start that is off is a desktop
 * XFCE lays out twice, the second time the moment this page connects.
 *
 * The box is the part of the window the desktop has for good, in CSS px: the
 * stage less the docked bar and a pinned keys row (dex.js, steadyBox). The
 * keyboard and the keys row it brings up come and go, and are never in it.
 */

/* The phone's own screen: 2.25 guest px to the CSS px in either orientation,
   so turning the phone re-lays XFCE out without changing the size of anything
   in it — the way the phone's own apps turn — wherever none of the limits
   below decides. On its side under the docked bar the 720 one does, and
   XFCE is drawn about 5 % smaller there than upright. */
export const PHONE_SCALE = 2.25;
/* XFCE's dialogs and most apps' windows stop fitting below this, and a phone
   on its side, under the bar, is only about 300 CSS px tall. */
export const PHONE_SHORT_MIN = 720;
/* Past this a tablet, or a Fold opened out, would draw its desktop smaller
   than a phone does. */
export const PHONE_SHORT_MAX = 1200;
/* Every pixel is drawn by the phone's CPU and encoded again for each viewer;
   and 2400 is as tall as LinuxService starts a desktop, so a phone upright
   never asks for one taller than it started. */
export const PHONE_LONG_MAX = 2400;

/* The desktop display: the start's limits (LinuxService), a computer's too. */
export const DESKTOP_MIN_W = 800;
export const DESKTOP_MIN_H = 600;
export const DESKTOP_MAX_W = 3840;
export const DESKTOP_MAX_H = 2400;

/* Sizes are even — every encoder takes those (LinuxStream's rule). Rounded on
   the phone, for the closest shape; floored on the desktop display, so that
   the desktop still fits at whole device pixels. */
const even = (v) => Math.max(2, 2 * Math.round(v / 2));
const evenDown = (v) => 2 * Math.floor(v / 2);

/**
 * @param {string} where 'phone', or anything else for the desktop display
 * @param {number} w the box, CSS px
 * @param {number} h the box, CSS px
 * @param {number} dpr device px per CSS px
 * @returns {{w: number, h: number}} the guest's screen, in its own pixels
 */
export function guestSize(where, w, h, dpr) {
  if (where === 'phone') {
    const short = Math.min(w, h);
    const long = Math.max(w, h);
    let k = PHONE_SCALE;
    k = Math.max(k, PHONE_SHORT_MIN / short);
    k = Math.min(k, PHONE_SHORT_MAX / short);
    k = Math.min(k, PHONE_LONG_MAX / long);
    /* The short side is made a whole even number first, and the long side
       follows it at the box's own ratio, so that only the long side's rounding
       is left over: within one guest pixel of the box's shape, under half a
       CSS pixel on a phone. Rounding both sides would leave the short side's
       pixel magnified by the ratio — more than two on a phone on its side.
       The long side's limit, where it bites, holds. */
    let s = even(short * k);
    let l = even(s * long / short);
    if (l > PHONE_LONG_MAX) {
      l = PHONE_LONG_MAX;
      s = even(l * short / long);
    }
    return w <= h ? { w: s, h: l } : { w: l, h: s };
  }
  /* Whole device pixels per guest pixel — 1 around 160 dpi, 2 from 280, 3
     from 440 — because XFCE draws at 96 dpi whatever the display (its
     xsettings pin the scale at 1, for VNC's sake): one for one would make it
     half the size of everything around it on a desktop fitted to a 2x screen,
     and a fraction would smear its one-pixel lines across two. */
  const per = Math.max(1, Math.floor(dpr + 0.25));
  return {
    w: Math.min(Math.max(evenDown(w * dpr / per), DESKTOP_MIN_W), DESKTOP_MAX_W),
    h: Math.min(Math.max(evenDown(h * dpr / per), DESKTOP_MIN_H), DESKTOP_MAX_H),
  };
}

/** CSS px per guest px at which a desktop sized for the desktop display is sharp. */
export function sharpScale(dpr) {
  return Math.max(1, Math.floor(dpr + 0.25)) / dpr;
}
