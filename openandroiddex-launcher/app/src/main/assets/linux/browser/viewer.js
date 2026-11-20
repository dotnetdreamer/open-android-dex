// LinuxOnDroid in a computer's browser.
//
// The phone reads Xvnc's pixels raw, draws them into its hardware video
// encoder and sends H.264 down this page's WebSocket (LinuxStream.java). This
// page decodes it on the computer's GPU (WebCodecs) and draws each frame the
// moment it comes out of the decoder, one pixel for one: no player, no buffer,
// no jitter queue. The pointer is this computer's own, wearing the shape Xvnc
// sends, so it moves at the screen's rate whatever the video does. Keys go as
// keysyms (keysym.js) and the window's size goes as the desktop's.
//
// Over plain http, which a browser that refuses the phone's certificate is
// left with, browsers give a page no WebCodecs: there the picture goes
// through the browser's own video player instead (mse.js), a little later.
// The page offers the secure address first, and goes there by itself once
// this browser has taken the certificate.
//
// Linux's sound comes down the same socket as PCM and is played with Web
// Audio, which every page has. Sound off is nothing sent at all.
//
// Three pictures to choose from, live, without reconnecting:
//   Low     a picture two thirds the desktop's size, at a low bitrate: the
//           least to encode, send and decode, for slow Wi-Fi
//   Medium  the whole desktop at a Wi-Fi bitrate
//   High    the whole desktop at a high bitrate: the sharpest text
// The phone still lowers the bitrate under any of them when the link falls
// behind (LinuxStream.adapt), so a frame is dropped before one is late.
//
// Design record: doc/linux-browser.md. The wire: LinuxStream.java's header.

import { avcC, codecString, sameBytes, splitAccessUnit } from './h264.js';
import { ALTS, charKeysym, isModifier, keyDown, XK_ISO_LEVEL3_SHIFT } from './keysym.js';
import { MsePlayer } from './mse.js';

const $ = (id) => document.getElementById(id);
const PLATFORM = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || navigator.userAgent;
const MAC = /mac|iphone|ipad/i.test(PLATFORM);

// ── words ──────────────────────────────────────────────────────────────

const TEXT = {
  pairLede: 'Find it on your phone: LinuxOnDroid › Settings › Open in a browser.',
  pairLedeNamed: (name) => `Find it on ${name}: LinuxOnDroid › Settings › Open in a browser.`,
  pairFine: 'Linux stays on your phone. This browser shows it, and nothing is installed here.',
  wrong: 'That code did not match. Check the phone and try again.',
  locked: (t) => `Too many tries. Try again in ${t}.`,
  pairClosed: 'Open Settings › Open in a browser on the phone, then type the code it shows. A code works while that page is open and for 10 minutes after.',
  pairUsedUp: 'Too many wrong codes were tried. The phone can make a new one, in the same place.',
  signedOut: 'This browser was signed out on the phone. Enter the code to come back in.',
  offline: 'The phone did not answer. Check it is on the same Wi-Fi as this computer.',
  connectingTitle: 'Connecting',
  connectingText: (name) => `Reaching ${name}`,
  startingTitle: 'Starting Linux',
  startingText: 'The desktop is starting on the phone. It takes a few seconds.',
  drawingTitle: 'Almost there',
  drawingText: 'Linux is up. The first picture is on its way.',
  reconnectTitle: 'Reconnecting',
  reconnectText: (name) => `The connection to ${name} dropped. Trying again.`,
  unreachableTitle: 'Cannot reach the phone',
  unreachableText: 'Check that the phone and this computer are on the same Wi-Fi, and that Open in a browser is on in LinuxOnDroid’s Settings.',
  stoppedTitle: 'Linux is not running',
  stoppedText: 'It was shut down or logged out of on the phone.',
  replacedTitle: 'Linux opened somewhere else',
  replacedText: 'Another window is showing the desktop now. Bring it back here whenever you like.',
  missingTitle: 'Linux is not installed yet',
  missingText: 'Open LinuxOnDroid on the phone to install it, then try again.',
  installingTitle: 'Linux is still being set up',
  installingText: 'LinuxOnDroid is installing Linux on the phone. Try again when it is done.',
  unfinishedTitle: 'Linux did not finish setting up',
  unfinishedText: 'Open LinuxOnDroid on the phone to finish it, then try again.',
  failedTitle: 'Linux could not be shown',
  failedText: 'Something went wrong on the phone.',
  disconnectedTitle: 'Disconnected',
  disconnectedText: 'Linux keeps running on the phone.',
  offTitle: 'Turned off on the phone',
  offText: 'Open in a browser was switched off in LinuxOnDroid’s Settings. Switch it on again there, then try again.',
  reload: 'Reload the page',
  oldTitle: 'This browser cannot show Linux',
  oldText: 'It needs WebCodecs or Media Source Extensions, which current Chrome, Edge, Firefox and Safari all have.',
  playerTitle: 'The picture could not be played',
  playerText: (why) => `This browser’s video player stopped (${why}). The secure address, which starts with https, uses the computer’s video decoder instead.`,
  chooseTitle: 'Open Linux securely',
  chooseText: 'The phone made its own certificate, so the browser warns about it the first time. Choose Advanced, then Continue.',
  chooseSecure: 'Continue securely',
  choosePlain: 'Continue without encryption',
  chooseFine: 'If the browser will not let you continue, come back here and choose Continue without encryption. Then others on this Wi-Fi could see the desktop and what you type.',
  plainNote: 'Not encrypted',
  plainTitle: 'Not encrypted: others on this Wi-Fi could see the desktop and what you type.',
  goSecure: 'Use the secure connection',
  soundTitleOn: 'Sound from Linux: on',
  soundTitleOff: 'Sound from Linux: off',
  soundOn: 'Sound on. Linux plays here.',
  soundOff: 'Sound off. The phone sends none.',
  soundClick: 'Click the desktop to hear Linux.',
  start: 'Start Linux',
  showHere: 'Show it here',
  tryAgain: 'Try again',
  tryNow: 'Try now',
  connectAgain: 'Connect again',
  enterCode: 'Enter the code again',
  mode: {
    low: ['Low', 'Fastest. A smaller picture and mono sound that keep up on slow Wi-Fi.'],
    medium: ['Medium', 'Balanced. The whole desktop at a Wi-Fi bitrate, stereo sound.'],
    high: ['High', 'Sharpest. The whole desktop at the highest bitrate, sound as Linux makes it.'],
  },
  fullOn: 'Full screen. Every key goes to Linux; hold Esc to leave.',
  fullOnNoLock: 'Full screen. Press Esc to leave.',
  fullTitle: 'Full screen (F11)',
  clipOn: 'Clipboard shared. Copy and paste work both ways.',
  clipOff: 'Clipboard not shared.',
  clipTitleOn: 'Clipboard shared with Linux',
  clipTitleOff: 'Clipboard not shared with Linux',
  clipWaiting: 'Linux copied something. Click here to bring it to this computer.',
  moreTitle: 'More',
  still: 'still',
  statsTitle: (s) => `Frames drawn each second, the stream's bitrate and the round trip to the phone.\n` +
    `Encoded on the phone in ${s.encodeMs} ms by ${s.encoder || 'its encoder'}${s.hw === false ? ' (software)' : ''}; ` +
    (s.player ? `on screen here ${s.decodeMs} ms after it arrived (the browser's video player).` : `decoded here in ${s.decodeMs} ms.`) +
    ` Picture ${s.size}.${s.soundKbps ? ` Sound ${s.sound}, ${s.soundKbps} kb/s.` : ''}`,
};

// ── the wire (LinuxStream.java) ────────────────────────────────────────

const MSG_VIDEO = 0x01;
const MSG_CURSOR = 0x02;
const MSG_AUDIO = 0x03;
const IN_POINTER = 0x10;
const IN_KEY = 0x11;
/** "A frame arrived": the phone keeps no more frames in the pipe than its round trip holds. */
const ACK = new Uint8Array([0x12]);
const WHEEL_STEP = 50;
const MIN_W = 640;
const MIN_H = 400;
const MAX_W = 3840;
const MAX_H = 2400;

/**
 * The three pictures. Bits a pixel at 60 Hz, a floor and a ceiling: ceilings
 * for motion, since a desktop is mostly still and still costs nothing.
 * Medium is the desktop app's own figure for Wi-Fi; High is two and a half
 * times it, which is where small text stops softening while it scrolls; Low
 * draws a third of the pixels fewer each way and spends little on them.
 */
const MODES = {
  low: { scale: 2 / 3, bpp: 0.05, min: 1.5e6, max: 8e6 },
  medium: { scale: 1, bpp: 0.08, min: 4e6, max: 25e6 },
  high: { scale: 1, bpp: 0.2, min: 10e6, max: 60e6 },
};

export function bitrateFor(w, h, mode) {
  const m = MODES[mode] || MODES.medium;
  return Math.round(Math.min(m.max, Math.max(m.min, w * h * m.scale * m.scale * 60 * m.bpp)));
}

/** The desktop's size for this window: its own, in CSS pixels, held even and within what the phone takes. */
function sizeFor() {
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, Math.round(v))) & ~1;
  return [clamp(window.innerWidth, MIN_W, MAX_W), clamp(window.innerHeight, MIN_H, MAX_H)];
}

const store = {
  get(k) {
    try {
      return localStorage.getItem(k);
    } catch {
      return null;
    }
  },
  set(k, v) {
    try {
      if (v == null) localStorage.removeItem(k);
      else localStorage.setItem(k, v);
    } catch {
      // private window, or storage off: the code is asked again next time
    }
  },
};

// ── the page's state ───────────────────────────────────────────────────

const body = document.body;
const canvas = $('screen');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
/** The http page's picture: the browser's own video player, over the canvas, which still takes the input. */
const video = $('video');
/** No WebCodecs here (plain http, or an older browser): the video player shows the picture. */
const USE_PLAYER = !('VideoDecoder' in window);
const PLAIN = location.protocol === 'http:';

let phoneName = '';
let token = store.get('lod.token');
let mode = MODES[store.get('lod.mode')] ? store.get('lod.mode') : 'medium';
let share = store.get('lod.clip') !== '0';
let soundOn = store.get('lod.sound') !== '0';
/** The page is going somewhere of its own accord (the secure address): no question asked on the way out. */
let leaving = false;

let ws = null;
/** A picture is on screen and input goes to the guest. */
let up = false;
/** A picture has been drawn at all: covers go over it rather than hide it. */
let hasPicture = false;
/** The phone said what it is encoding: sizes and qualities may be asked for. */
let configured = false;
/** The phone said why it is ending; the close that follows is not a loss. */
let told = false;
/** This page ended it (Disconnect, Forget): nothing comes back by itself. */
let quiet = false;
let everConnected = false;
let attempts = 0;
let retryTimer = 0;

// ── covers ─────────────────────────────────────────────────────────────

function cover(which) {
  for (const id of ['choose', 'pair', 'wait', 'ended']) $(id).hidden = id !== which;
  $('cover').classList.toggle('over', hasPicture);
  body.classList.toggle('up', which === null);
  // The bar is the desktop's: it goes with the picture.
  if (which !== null) {
    body.classList.remove('bar-on');
    closeMenu();
  }
}

function wait(title, text) {
  $('wait-title').textContent = title;
  $('wait-text').textContent = text;
  if (!up) cover('wait');
}

/** An end of some kind: a title, a sentence, and what can be done about it. */
function ended(title, text, goLabel, go, altLabel, alt) {
  up = false;
  body.classList.remove('bar-on');
  setDot('warn');
  $('ended-title').textContent = title;
  $('ended-text').textContent = text;
  const goBtn = $('ended-go');
  goBtn.textContent = goLabel;
  goBtn.onclick = go;
  const altBtn = $('ended-alt');
  altBtn.hidden = !altLabel;
  if (altLabel) {
    altBtn.textContent = altLabel;
    altBtn.onclick = alt;
  }
  cover('ended');
  goBtn.focus({ preventScroll: true });
}

let toastTimer = 0;
function toast(words, ms = 2600) {
  const t = $('toast');
  t.textContent = '';
  // "<b>Name</b>: rest", from our own table only.
  const m = /^\u0001(.*?)\u0001(.*)$/.exec(words);
  if (m) {
    const b = document.createElement('b');
    b.textContent = m[1];
    t.append(b, m[2]);
  } else {
    t.textContent = words;
  }
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

function setDot(kind) {
  const d = $('bar-dot');
  d.classList.toggle('ok', kind === 'ok');
  d.classList.toggle('warn', kind === 'warn');
}

// ── pairing: the code for a token ──────────────────────────────────────

const codeInput = $('code');
const codeBox = $('code-box');
const cells = [...codeBox.querySelectorAll('.cells i')];
let lockUntil = 0;
let lockTimer = 0;

function paintCode() {
  const v = codeInput.value;
  cells.forEach((c, i) => {
    c.textContent = v[i] || '';
    c.classList.toggle('filled', i < v.length);
    c.classList.toggle('next', i === v.length);
  });
}

function showPair(note) {
  up = false;
  closeSocket();
  $('pair-lede').textContent = phoneName ? TEXT.pairLedeNamed(phoneName) : TEXT.pairLede;
  $('pair-fine').textContent = TEXT.pairFine;
  $('pair-error').textContent = note || '';
  codeInput.value = '';
  codeBox.classList.remove('bad', 'busy');
  paintCode();
  cover('pair');
  setTimeout(() => codeInput.focus(), 60);
}

async function submitCode() {
  if (codeBox.classList.contains('busy') || Date.now() < lockUntil) return;
  codeBox.classList.add('busy');
  let r;
  let j = {};
  try {
    r = await fetch('pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ code: codeInput.value }),
    });
    j = await r.json().catch(() => ({}));
  } catch {
    codeBox.classList.remove('busy');
    $('pair-error').textContent = TEXT.offline;
    return;
  }
  codeBox.classList.remove('busy');
  if (r.ok && j.token) {
    token = j.token;
    store.set('lod.token', token);
    codeInput.blur();
    void learnName();
    // The code was typed to see Linux: this start is the person's own.
    connect(true);
    return;
  }
  codeBox.classList.remove('bad');
  void codeBox.offsetWidth; // the shake runs again on a second wrong code
  codeBox.classList.add('bad');
  codeInput.value = '';
  paintCode();
  if (j.error === 'locked') {
    lockUntil = Date.now() + (Number(j.retryIn) || 30) * 1000;
    tickLock();
  } else if (j.error === 'closed') {
    $('pair-error').textContent = TEXT.pairClosed;
  } else if (j.error === 'used') {
    $('pair-error').textContent = TEXT.pairUsedUp;
  } else {
    $('pair-error').textContent = TEXT.wrong;
  }
}

function tickLock() {
  clearTimeout(lockTimer);
  const left = Math.ceil((lockUntil - Date.now()) / 1000);
  if (left <= 0) {
    $('pair-error').textContent = '';
    codeInput.disabled = false;
    codeInput.focus();
    return;
  }
  codeInput.disabled = true;
  $('pair-error').textContent = TEXT.locked(`${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`);
  lockTimer = setTimeout(tickLock, 500);
}

codeInput.addEventListener('input', () => {
  codeInput.value = codeInput.value.replace(/\D/g, '').slice(0, 6);
  codeBox.classList.remove('bad');
  $('pair-error').textContent = '';
  paintCode();
  if (codeInput.value.length === 6) void submitCode();
});
codeInput.addEventListener('focus', () => codeBox.classList.add('focus'));
codeInput.addEventListener('blur', () => codeBox.classList.remove('focus'));
$('pair-form').addEventListener('submit', (e) => {
  e.preventDefault();
  if (codeInput.value.length === 6) void submitCode();
});

// ── the picture ────────────────────────────────────────────────────────

let dw = 0;
let dh = 0;
let cw = 0;
let ch = 0;
let decoder = null;
let description = null;
let sps = null;
let pps = null;
let needKey = true;
let askedKeyAt = 0;
let drawn = 0;
let decodeMs = 0;
/** When each frame went into the decoder, by its timestamp: how long decoding takes here. */
const decodeStart = new Map();
let encoderName = '';
let encoderHw = true;

function send(m) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(m);
}

function askKey() {
  const now = performance.now();
  if (now - askedKeyAt < 500) return;
  askedKeyAt = now;
  send(JSON.stringify({ t: 'keyframe' }));
}

function draw(frame) {
  const t0 = decodeStart.get(frame.timestamp);
  if (t0 !== undefined) {
    decodeStart.delete(frame.timestamp);
    decodeMs = decodeMs * 0.9 + (performance.now() - t0) * 0.1;
  }
  // A frame from a stream this page has let go of (the phone said it was
  // over, or another window took the desktop) draws nothing and wakes
  // nothing: the card saying so stays.
  if (dw > 0 && ws && !told) {
    // Resized here, with the first picture at the new size in hand: setting
    // a canvas's size clears it, and the old picture should stay up until
    // there is a new one to replace it.
    if (canvas.width !== dw || canvas.height !== dh) {
      canvas.width = dw;
      canvas.height = dh;
      ctx.imageSmoothingQuality = 'high';
    }
    ctx.drawImage(frame, 0, 0, cw || frame.displayWidth, ch || frame.displayHeight, 0, 0, dw, dh);
    drawn++;
    if (!up) becameUp();
  }
  frame.close();
}

function newDecoder() {
  if (decoder && decoder.state !== 'closed') decoder.close();
  decoder = new VideoDecoder({
    output: draw,
    error: () => {
      description = null;
      needKey = true;
      askKey();
    },
  });
}

/** The http page's player, for one stream. */
let player = null;

function newPlayer() {
  body.classList.add('player');
  return new MsePlayer(video, {
    fps: 60,
    onFrame: (meta, arrivedAt) => {
      // How long after its bytes arrived a frame was on screen: this path's
      // cost against WebCodecs, shown where the decode time is.
      if (arrivedAt !== undefined) decodeMs = decodeMs * 0.9 + Math.max(0, meta.presentationTime - arrivedAt) * 0.1;
      if (!ws || told) return;
      drawn++;
      if (!up) becameUp();
    },
    askKey,
    onFail: (why) => {
      closeSocket();
      quiet = true;
      ended(TEXT.playerTitle, TEXT.playerText(why), TEXT.goSecure, goSecure, TEXT.reload, () => location.reload());
    },
  });
}

/** The last picture into the canvas under the player, so that it stays while the player is let go. */
function keepLastPicture() {
  if (video.readyState < 2 || !video.videoWidth) return;
  const w = dw || video.videoWidth;
  const h = dh || video.videoHeight;
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  try {
    ctx.drawImage(video, 0, 0, w, h);
  } catch {
    // nothing to draw after all
  }
}

function onVideo(flags, pts, payload) {
  const unit = splitAccessUnit(payload);
  if (unit.sps) sps = unit.sps;
  if (unit.pps) pps = unit.pps;
  if (!unit.data.length) return; // parameter sets only
  const isKey = unit.key || (flags & 1) !== 0;
  if (USE_PLAYER) {
    if (!player) player = newPlayer();
    player.push(unit.data, isKey, sps, pps, performance.now());
    return;
  }
  if (isKey && sps && pps) {
    const d = avcC(sps, pps);
    if (!decoder || decoder.state === 'closed' || !sameBytes(d, description)) {
      if (!decoder || decoder.state === 'closed') newDecoder();
      decoder.configure({
        codec: codecString(sps),
        description: d,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      });
      description = d;
    }
    needKey = false;
  }
  if (needKey || !decoder || decoder.state !== 'configured') {
    askKey();
    return;
  }
  // Behind by more than a couple of frames is a decoder that cannot keep up:
  // start over from a key frame rather than show a growing delay.
  if (decoder.decodeQueueSize > 3 && !isKey) {
    needKey = true;
    askKey();
    return;
  }
  if (decodeStart.size > 240) decodeStart.clear();
  decodeStart.set(pts, performance.now());
  decoder.decode(new EncodedVideoChunk({ type: isKey ? 'key' : 'delta', timestamp: pts, data: unit.data }));
}

function becameUp() {
  up = true;
  hasPicture = true;
  everConnected = true;
  attempts = 0;
  cover(null);
  setDot('ok');
  showBar(3200);
  canvas.focus({ preventScroll: true });
  void holdScreen();
  if (soundOn) {
    // Allowed now if this page has been clicked or typed in before (the
    // code); a page brought back by a reload waits for its first click.
    startSound();
    setTimeout(() => {
      if (up && soundOn && !canPlaySound() && !soundHinted) {
        soundHinted = true;
        toast(TEXT.soundClick, 3600);
      }
    }, 800);
  }
}

function setCursor(hotX, hotY, w, h, rgba) {
  if (!w || !h) {
    canvas.style.cursor = 'none';
    return;
  }
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(rgba.buffer, rgba.byteOffset, w * h * 4), w, h), 0, 0);
  canvas.style.cursor = `url(${c.toDataURL('image/png')}) ${Math.min(hotX, w - 1)} ${Math.min(hotY, h - 1)}, default`;
}

// ── the pointer ────────────────────────────────────────────────────────

let buttons = 0;
let lastX = 0;
let lastY = 0;

/** Where a point of the window falls on the desktop, the picture fitted inside it. */
function toDesktop(clientX, clientY) {
  const r = canvas.getBoundingClientRect();
  if (!dw || !r.width) return [0, 0];
  const s = Math.min(r.width / dw, r.height / dh);
  const ox = (r.width - dw * s) / 2;
  const oy = (r.height - dh * s) / 2;
  const x = Math.floor((clientX - r.left - ox) / s);
  const y = Math.floor((clientY - r.top - oy) / s);
  return [Math.max(0, Math.min(dw - 1, x)), Math.max(0, Math.min(dh - 1, y))];
}

function pointer(x, y, mask) {
  lastX = x;
  lastY = y;
  send(new Uint8Array([IN_POINTER, mask & 0xff, x >> 8, x & 0xff, y >> 8, y & 0xff]));
}

/** The browser's buttons as RFB's: left 1, middle 2, right 4, back 128 (X's button 8). */
const rfbButtons = (b) => (b & 1 ? 1 : 0) | (b & 4 ? 2 : 0) | (b & 2 ? 4 : 0) | (b & 8 ? 128 : 0);

function onPointer(e) {
  if (!up) return;
  const [x, y] = toDesktop(e.clientX, e.clientY);
  if (e.type === 'pointerdown') {
    canvas.setPointerCapture(e.pointerId);
    canvas.focus({ preventScroll: true });
    closeMenu();
    startSound();
  }
  if ((e.type === 'pointerup' || e.type === 'pointercancel') && canvas.hasPointerCapture(e.pointerId)) {
    canvas.releasePointerCapture(e.pointerId);
  }
  buttons = rfbButtons(e.buttons);
  pointer(x, y, buttons);
  // The bar comes down when the pointer rests at the very top, and goes once
  // the pointer has left the strip it sits in.
  if (e.type === 'pointermove' && !buttons && e.pointerType === 'mouse') {
    if (e.clientY <= 3) showBar(0);
    else if (body.classList.contains('bar-on') && e.clientY > barBottom() + 28) hideBarSoon();
  }
  e.preventDefault();
}

let wheelX = 0;
let wheelY = 0;
function onWheel(e) {
  e.preventDefault();
  if (!up) return;
  const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 800 : 1;
  wheelX += e.deltaX * k;
  wheelY += e.deltaY * k;
  const [x, y] = toDesktop(e.clientX, e.clientY);
  const click = (bit) => {
    pointer(x, y, buttons | bit);
    pointer(x, y, buttons);
  };
  while (Math.abs(wheelY) >= WHEEL_STEP) {
    click(wheelY < 0 ? 8 : 16);
    wheelY -= Math.sign(wheelY) * WHEEL_STEP;
  }
  while (Math.abs(wheelX) >= WHEEL_STEP) {
    click(wheelX < 0 ? 32 : 64);
    wheelX -= Math.sign(wheelX) * WHEEL_STEP;
  }
}

canvas.addEventListener('pointermove', onPointer);
canvas.addEventListener('pointerdown', onPointer);
canvas.addEventListener('pointerup', onPointer);
canvas.addEventListener('pointercancel', onPointer);
canvas.addEventListener('wheel', onWheel, { passive: false });
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
// Back and forward on a mouse belong to Linux, not to this page's history.
for (const type of ['mouseup', 'auxclick']) {
  canvas.addEventListener(type, (e) => {
    if (e.button === 3 || e.button === 4) e.preventDefault();
  });
}

// ── keys ───────────────────────────────────────────────────────────────

const down = new Map();

function key(keysym, isDown) {
  send(new Uint8Array([IN_KEY, isDown ? 1 : 0, (keysym >>> 24) & 0xff, (keysym >>> 16) & 0xff, (keysym >>> 8) & 0xff, keysym & 0xff]));
}

/** Keys for the desktop: unless something of the page's own (the bar, a card) has them. */
function forGuest(e) {
  const t = e.target;
  return up && (t === canvas || t === body || t === document.documentElement);
}

/**
 * Paste is the browser's: let it happen, and the 'paste' event brings this
 * computer's clipboard with it, with no permission asked. That text goes to
 * Linux first, and then the keys that paste it there.
 */
let pendingPaste = null;
let pasteTimer = 0;
function isPaste(e) {
  if (e.code === 'KeyV' && !e.altKey && (MAC ? e.metaKey : e.ctrlKey)) return true;
  return e.code === 'Insert' && e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey;
}
function pasteKeys() {
  clearTimeout(pasteTimer);
  const p = pendingPaste;
  pendingPaste = null;
  if (!p) return;
  key(p, true);
  key(p, false);
}
window.addEventListener('paste', (e) => {
  if (!pendingPaste) return;
  e.preventDefault();
  const text = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
  if (text) {
    send(JSON.stringify({ t: 'clip', text: text.replace(/\r\n?/g, '\n') }));
    pastedHere = true;
  }
  pasteKeys();
});

window.addEventListener(
  'keydown',
  (e) => {
    if (e.key === 'F11' && !e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey) {
      e.preventDefault();
      if (!e.repeat) void toggleFullscreen();
      return;
    }
    if (!forGuest(e) || e.isComposing || e.keyCode === 229) return;
    // A key is a moment a page may copy, and start sound: both kept for one.
    if (!navigator.clipboard) flushCopy();
    startSound();
    if (share && isPaste(e) && !e.repeat) {
      // No preventDefault: the browser's paste is what carries the text.
      pendingPaste = e.code === 'Insert' ? 0xff63 : charKeysym('v');
      clearTimeout(pasteTimer);
      // Nothing to paste (an image, an empty clipboard): the keys alone.
      pasteTimer = setTimeout(pasteKeys, 150);
      return;
    }
    e.preventDefault();
    const a = keyDown(e, MAC);
    if (a.kind === 'none') return;
    if (a.kind === 'type') {
      const alts = [...down.values()].filter((k) => ALTS.includes(k));
      for (const k of alts) key(k, false);
      key(a.keysym, true);
      key(a.keysym, false);
      for (const k of alts) key(k, true);
      return;
    }
    const was = down.get(e.code);
    if (was !== undefined && was !== a.keysym) key(was, false);
    down.set(e.code, a.keysym);
    key(a.keysym, true);
  },
  true,
);

window.addEventListener(
  'keyup',
  (e) => {
    if (!up) return;
    const k = down.get(e.code);
    if (k !== undefined) {
      e.preventDefault();
      down.delete(e.code);
      key(k, false);
    }
    // A Mac reports no keyup for a key pressed while Cmd was held: they all
    // go up with Cmd, or the guest would think them held for ever.
    if (MAC && (e.code === 'MetaLeft' || e.code === 'MetaRight')) {
      for (const [code, ks] of [...down]) {
        if (!isModifier(ks) && ks !== XK_ISO_LEVEL3_SHIFT) {
          down.delete(code);
          key(ks, false);
        }
      }
    }
  },
  true,
);

/** Everything held, let go: the window lost the keyboard, and with it every keyup. */
function releaseAll() {
  for (const ks of down.values()) key(ks, false);
  down.clear();
  if (buttons) {
    buttons = 0;
    pointer(lastX, lastY, 0);
  }
}
window.addEventListener('blur', releaseAll);

// ── the clipboard ──────────────────────────────────────────────────────

/** The guest holds, right now, text this computer gave it. */
let pastedHere = false;
let waitingCopy = null;

function fromGuest(text) {
  pastedHere = false;
  if (!share) return;
  if (!navigator.clipboard || !navigator.clipboard.writeText) {
    // Plain http has no clipboard API: the browser's own copy carries it, at
    // the next click or key, which is when a page may copy.
    waitingCopy = text;
    if (document.hasFocus()) toast(TEXT.clipWaiting, 4000);
    return;
  }
  navigator.clipboard.writeText(text).then(
    () => {
      waitingCopy = null;
    },
    () => {
      // Not focused, or the browser wants a click first: kept for then.
      waitingCopy = text;
      if (document.hasFocus()) toast(TEXT.clipWaiting, 4000);
    },
  );
}

/** The browser's own copy, with our text in it: allowed in a click or a key, on any page. */
function copyByCommand(text) {
  const put = (e) => {
    e.clipboardData.setData('text/plain', text);
    e.preventDefault();
  };
  document.addEventListener('copy', put, true);
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch {
    ok = false;
  }
  document.removeEventListener('copy', put, true);
  return ok;
}

function flushCopy() {
  if (waitingCopy == null) return;
  if (navigator.clipboard && navigator.clipboard.writeText) {
    fromGuest(waitingCopy);
  } else if (copyByCommand(waitingCopy)) {
    waitingCopy = null;
  }
}
window.addEventListener('focus', flushCopy);
canvas.addEventListener('pointerdown', flushCopy);

function setShare(on, say) {
  share = on;
  store.set('lod.clip', on ? '1' : '0');
  const b = $('clip');
  b.setAttribute('aria-pressed', String(on));
  b.title = on ? TEXT.clipTitleOn : TEXT.clipTitleOff;
  b.setAttribute('aria-label', b.title);
  // Off means off: this computer's text, if that is what Linux has, goes.
  if (!on && pastedHere) {
    send(JSON.stringify({ t: 'clip', text: '' }));
    pastedHere = false;
  }
  if (say) toast(on ? TEXT.clipOn : TEXT.clipOff);
}

// ── sound ──────────────────────────────────────────────────────────────
//
// Linux's speakers, in the form the picture's quality gives it
// (LinuxStreamAudio): High is PCM as the guest makes it, 48 kHz stereo;
// Medium and Low are Opus, 96 kb/s stereo and 32 kb/s mono, where this page
// can decode it (WebCodecs' AudioDecoder, on an https page), and smaller PCM
// otherwise, 24 kHz stereo and 16 kHz mono. The phone says which before the
// first run and at every change ({"t":"sound"}). Each run is played as it
// lands, a little ahead: enough in hand that the next is never late, which
// the gaps between runs say (the tap hands sound over in bursts). A run that
// would play far behind its picture is dropped instead. Asked for only while
// this page can play it: the phone reads and sends nothing otherwise, and
// nothing while sound is off.

const SOUND_RATE = 48000;
const CODEC_OPUS = 1;
let actx = null;
/** When, on actx's clock, the next run starts. */
let nextAt = 0;
/** What the phone was last told: whether to send sound. */
let soundAsked = false;
let lastRunAt = 0;
/** The longest gap between runs lately, in seconds; a slow decay forgets an old one. */
let runGap = 0;
let soundHinted = false;
/** Runs played, for the tests' eyes (test/linux-browser-test.mjs). */
let soundRuns = 0;
/** This page can decode Opus: decided at the start, said to the phone with every ask. */
let soundOpus = false;
/** What the phone says it sends now. */
let soundFmt = { codec: 'pcm', rate: SOUND_RATE, channels: 2, kbps: 1536, mode: '' };
let audioDec = null;

async function canOpus() {
  if (typeof AudioDecoder === 'undefined') return false;
  try {
    return (await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: SOUND_RATE, numberOfChannels: 2 })).supported === true;
  } catch {
    return false;
  }
}

const soundCodecs = () => (soundOpus ? ['opus', 'pcm'] : ['pcm']);

function canPlaySound() {
  return soundOn && !!actx && actx.state === 'running';
}

/** Tell the phone, when what this page can play has changed. */
function syncSound() {
  const want = canPlaySound();
  if (want === soundAsked || !ws || ws.readyState !== WebSocket.OPEN) return;
  soundAsked = want;
  send(JSON.stringify({ t: 'audio', on: want, codecs: soundCodecs() }));
}

/** The phone's word on what follows: Opus gets a decoder, PCM none. */
function onSoundFormat(m) {
  soundFmt = {
    codec: m.codec === 'opus' ? 'opus' : 'pcm',
    rate: Number(m.rate) || SOUND_RATE,
    channels: Number(m.channels) === 1 ? 1 : 2,
    kbps: Number(m.kbps) || 0,
    mode: String(m.mode || ''),
  };
  closeAudioDecoder();
  if (soundFmt.codec !== 'opus' || typeof AudioDecoder === 'undefined') return;
  try {
    audioDec = new AudioDecoder({ output: playDecoded, error: () => closeAudioDecoder() });
    audioDec.configure({ codec: 'opus', sampleRate: soundFmt.rate, numberOfChannels: soundFmt.channels });
  } catch {
    audioDec = null;
  }
}

function closeAudioDecoder() {
  const d = audioDec;
  audioDec = null;
  if (d && d.state !== 'closed') {
    try {
      d.close();
    } catch {
      // closed already
    }
  }
}

function describeSound(f) {
  if (f.codec === 'opus') return `Opus ${f.kbps} kb/s ${f.channels === 1 ? 'mono' : 'stereo'}`;
  return `${Math.round(f.rate / 1000)} kHz ${f.channels === 1 ? 'mono' : 'stereo'}`;
}

/** In a click or a key: the moment a browser lets a page start sound. */
function startSound() {
  if (!soundOn) return;
  if (!actx) {
    try {
      actx = new AudioContext({ latencyHint: 'interactive', sampleRate: SOUND_RATE });
    } catch {
      actx = null;
      return;
    }
    actx.onstatechange = syncSound;
  }
  if (actx.state === 'suspended') actx.resume().then(syncSound, () => {});
  else syncSound();
}

/** One run from the phone: an Opus packet to decode, or PCM at the said rate and channels. */
function onSound(codec, pts, data) {
  if (!canPlaySound()) return;
  if (codec === CODEC_OPUS) {
    if (!audioDec || audioDec.state !== 'configured') return;
    try {
      audioDec.decode(new EncodedAudioChunk({ type: 'key', timestamp: pts, data }));
    } catch {
      closeAudioDecoder();
    }
    return;
  }
  const ch = soundFmt.channels;
  const frames = Math.floor(data.length / (2 * ch));
  if (!frames) return;
  const buf = actx.createBuffer(ch, frames, soundFmt.rate);
  const d = new DataView(data.buffer, data.byteOffset, data.byteLength);
  for (let c = 0; c < ch; c++) {
    const out = buf.getChannelData(c);
    for (let i = 0, o = c * 2; i < frames; i++, o += 2 * ch) out[i] = d.getInt16(o, true) / 32768;
  }
  play(buf);
}

/** What the Opus decoder made, into a buffer to play. */
function playDecoded(ad) {
  try {
    if (!canPlaySound()) return;
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
    play(buf);
  } finally {
    ad.close();
  }
}

/** A run onto actx's clock, right after the one before, with enough in hand. */
function play(buf) {
  const t = performance.now();
  const gap = lastRunAt ? (t - lastRunAt) / 1000 : 0;
  lastRunAt = t;
  // A gap of over half a second is silence the phone did not send, not a burst.
  runGap = gap < 0.5 ? Math.max(gap, runGap * 0.995) : runGap;
  const cushion = Math.min(0.35, Math.max(0.04, runGap * 1.2));
  const now = actx.currentTime;
  if (nextAt - now < 0.005) nextAt = now + cushion; // the first run, or ran dry
  else if (nextAt - now > cushion + 0.25) return; // far behind the picture: this run goes
  const src = actx.createBufferSource();
  src.buffer = buf;
  src.connect(actx.destination);
  src.start(nextAt);
  nextAt += buf.duration;
  soundRuns++;
}
window.lodSound = () => ({
  on: soundOn, state: actx ? actx.state : 'none', asked: soundAsked, runs: soundRuns,
  opus: soundOpus, format: describeSound(soundFmt), decoder: audioDec ? audioDec.state : 'none',
});

function setSound(on, say) {
  soundOn = on;
  store.set('lod.sound', on ? '1' : '0');
  const b = $('sound');
  b.setAttribute('aria-pressed', String(on));
  b.title = on ? TEXT.soundTitleOn : TEXT.soundTitleOff;
  b.setAttribute('aria-label', b.title);
  if (on) {
    startSound();
  } else if (actx) {
    const c = actx;
    actx = null;
    nextAt = 0;
    c.close().catch(() => {});
  }
  syncSound();
  if (say) toast(on ? TEXT.soundOn : TEXT.soundOff);
}

$('sound').addEventListener('click', () => {
  setSound(!soundOn, true);
  canvas.focus({ preventScroll: true });
});

// ── the three pictures ─────────────────────────────────────────────────

let sentBitrate = 0;

function modeParams(w, h) {
  return { mode, bitrate: bitrateFor(w, h, mode), scale: MODES[mode].scale, fps: 60 };
}

function paintModes() {
  const seg = $('modes');
  seg.dataset.mode = mode;
  for (const b of seg.querySelectorAll('button')) {
    const on = b.dataset.mode === mode;
    b.setAttribute('aria-checked', String(on));
    b.tabIndex = on ? 0 : -1;
    const [name, what] = TEXT.mode[b.dataset.mode];
    b.title = `${name} · ${what}`;
  }
}

function setMode(next, say) {
  if (!MODES[next]) return;
  const changed = next !== mode;
  mode = next;
  store.set('lod.mode', mode);
  paintModes();
  if (changed && configured && dw > 0) {
    const p = modeParams(dw, dh);
    sentBitrate = p.bitrate;
    send(JSON.stringify({ t: 'quality', ...p }));
  }
  if (say) {
    const [name, what] = TEXT.mode[mode];
    toast(`\u0001${name}\u0001 · ${what}`);
  }
}

$('modes').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-mode]');
  if (!b) return;
  setMode(b.dataset.mode, true);
  canvas.focus({ preventScroll: true });
});
$('modes').addEventListener('keydown', (e) => {
  const order = ['low', 'medium', 'high'];
  const i = order.indexOf(mode);
  let n = -1;
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown') n = Math.min(2, i + 1);
  if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') n = Math.max(0, i - 1);
  if (n < 0) return;
  e.preventDefault();
  e.stopPropagation();
  setMode(order[n], true);
  $('modes').querySelector(`[data-mode="${order[n]}"]`).focus();
});

// ── the bar ────────────────────────────────────────────────────────────

let barTimer = 0;

function barBottom() {
  const r = $('bar').getBoundingClientRect();
  return r.bottom;
}

function barHeld() {
  const bar = $('bar');
  return !$('menu').hidden || bar.matches(':hover') || bar.contains(document.activeElement);
}

function showBar(ms) {
  if (!up) return;
  body.classList.add('bar-on');
  clearTimeout(barTimer);
  if (ms) barTimer = setTimeout(hideBarSoon, ms);
}

function hideBarSoon() {
  clearTimeout(barTimer);
  barTimer = setTimeout(() => {
    if (!barHeld()) body.classList.remove('bar-on');
  }, 250);
}

$('bar').addEventListener('pointerleave', (e) => {
  if (e.clientY > barBottom()) hideBarSoon();
});

function closeMenu() {
  const menu = $('menu');
  if (menu.hidden) return;
  menu.hidden = true;
  $('more').setAttribute('aria-expanded', 'false');
}

$('more').addEventListener('click', () => {
  const menu = $('menu');
  menu.hidden = !menu.hidden;
  $('more').setAttribute('aria-expanded', String(!menu.hidden));
  if (!menu.hidden) menu.querySelector('button').focus();
});
$('menu').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    e.stopPropagation();
    closeMenu();
    canvas.focus({ preventScroll: true });
  }
});
$('m-disconnect').addEventListener('click', () => {
  closeMenu();
  quiet = true;
  closeSocket();
  ended(TEXT.disconnectedTitle, TEXT.disconnectedText, TEXT.connectAgain, () => connect(true));
});
$('m-forget').addEventListener('click', async () => {
  closeMenu();
  quiet = true;
  const t = token;
  token = null;
  store.set('lod.token', null);
  closeSocket();
  try {
    await fetch('forget', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: t || '' }),
    });
  } catch {
    // the phone forgets it anyway when it is signed out there
  }
  showPair();
});
$('clip').addEventListener('click', () => {
  setShare(!share, true);
  canvas.focus({ preventScroll: true });
});
$('full').addEventListener('click', () => void toggleFullscreen());

// ── full screen, and the keys it frees ─────────────────────────────────

async function toggleFullscreen() {
  if (document.fullscreenElement) {
    await document.exitFullscreen().catch(() => {});
    return;
  }
  try {
    await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
  } catch {
    return;
  }
  // Alt+Tab, the Windows key, Ctrl+W: in full screen they can be Linux's.
  let locked = false;
  try {
    if (navigator.keyboard && navigator.keyboard.lock) {
      await navigator.keyboard.lock();
      locked = true;
    }
  } catch {
    // not this browser
  }
  toast(locked ? TEXT.fullOn : TEXT.fullOnNoLock, 3600);
}

document.addEventListener('fullscreenchange', () => {
  const on = !!document.fullscreenElement;
  body.classList.toggle('fullscreen', on);
  if (!on && navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock();
  canvas.focus({ preventScroll: true });
});

// ── the screen stays on while something moves ──────────────────────────

let screenLock = null;
let lastMotion = 0;

async function holdScreen() {
  if (screenLock || !('wakeLock' in navigator) || document.visibilityState !== 'visible') return;
  try {
    screenLock = await navigator.wakeLock.request('screen');
    screenLock.addEventListener('release', () => {
      screenLock = null;
    });
  } catch {
    // a battery saver said no: the screen sleeps as it would
  }
}

function letScreenGo() {
  if (screenLock) {
    screenLock.release().catch(() => {});
    screenLock = null;
  }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && up) void holdScreen();
});

// ── the window's size is the desktop's ─────────────────────────────────

let asked = '';
let settle = 0;

function askSize() {
  const [w, h] = sizeFor();
  const want = `${w}x${h}`;
  if (!configured || want === asked || (w === dw && h === dh)) return;
  asked = want;
  send(JSON.stringify({ t: 'resize', w, h }));
}

window.addEventListener('resize', () => {
  clearTimeout(settle);
  settle = setTimeout(askSize, 400);
});

// ── the connection ─────────────────────────────────────────────────────

let rtt = 0;
let phoneStats = { kbps: 0, encodeMs: 0, soundKbps: 0, at: 0 };

function closeSocket() {
  const s = ws;
  ws = null;
  if (s) {
    s.onclose = null;
    s.onmessage = null;
    try {
      s.close();
    } catch {
      // gone already
    }
  }
  // Frames still in the decoder belong to that stream: dropped with it. The
  // next stream starts at a key frame with a decoder of its own.
  if (decoder && decoder.state !== 'closed') {
    try {
      decoder.close();
    } catch {
      // closed already
    }
  }
  decoder = null;
  if (player) {
    keepLastPicture();
    player.close();
    player = null;
  }
  // Sound in hand belongs to that stream too; the next one asks again.
  nextAt = 0;
  soundAsked = false;
  closeAudioDecoder();
  description = null;
  needKey = true;
  decodeStart.clear();
  releaseLocal();
  configured = false;
}

/** Forget held keys without telling a phone that is already gone. */
function releaseLocal() {
  down.clear();
  buttons = 0;
  pendingPaste = null;
}

function connect(start, comingBack = false) {
  clearTimeout(retryTimer);
  closeSocket();
  quiet = false;
  told = false;
  needKey = true;
  description = null;
  if (!up && !comingBack) wait(TEXT.connectingTitle, TEXT.connectingText(phoneName || 'the phone'));
  let socket;
  try {
    // The token with the request: the phone checks it before it reads a
    // single frame. Over TLS where the page is (plain only where it is),
    // and never in this page's own address.
    socket = new WebSocket(`${PLAIN ? 'ws' : 'wss'}://${location.host}/stream?t=${encodeURIComponent(token || '')}`);
  } catch {
    lost();
    return;
  }
  socket.binaryType = 'arraybuffer';
  ws = socket;
  socket.onopen = () => {
    const [w, h] = sizeFor();
    const p = modeParams(w, h);
    sentBitrate = p.bitrate;
    // Sound only when this page can play it now: asked for later otherwise.
    soundAsked = canPlaySound();
    socket.send(JSON.stringify({ t: 'hello', v: 1, start, w, h, acks: true, audio: soundAsked, codecs: soundCodecs(), ...p }));
  };
  socket.onmessage = (ev) => {
    if (socket !== ws) return;
    if (typeof ev.data === 'string') {
      let m;
      try {
        m = JSON.parse(ev.data);
      } catch {
        return;
      }
      onText(m);
      return;
    }
    const b = new Uint8Array(ev.data);
    if (b[0] === MSG_VIDEO && b.length > 10) {
      // Acknowledged on arrival, before decoding: it is the link the phone
      // paces itself to.
      if (!(b[1] & 2)) socket.send(ACK);
      const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
      onVideo(b[1], Number(v.getBigUint64(2)), b.subarray(10));
    } else if (b[0] === MSG_CURSOR && b.length >= 9) {
      const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
      const w = v.getUint16(5);
      const h = v.getUint16(7);
      if (b.length >= 9 + w * h * 4) setCursor(v.getUint16(1), v.getUint16(3), w, h, b.subarray(9, 9 + w * h * 4));
    } else if (b[0] === MSG_AUDIO && b.length > 10) {
      onSound(b[1], Number(new DataView(b.buffer, b.byteOffset, b.byteLength).getBigUint64(2)), b.subarray(10));
    }
  };
  socket.onclose = () => {
    if (socket !== ws) return;
    ws = null;
    releaseLocal();
    configured = false;
    if (told || quiet) return;
    lost();
  };
}

/** The stream went without the phone saying why: the Wi-Fi, most likely. Back by itself. */
function lost() {
  up = false;
  attempts++;
  letScreenGo();
  setDot('warn');
  // Never reached, or not for a while: the phone is elsewhere, or answers
  // with a certificate this page has not been told to trust (a reload shows
  // the browser's question again).
  if ((!everConnected && attempts >= 3) || attempts >= 8) {
    ended(TEXT.unreachableTitle, TEXT.unreachableText, TEXT.tryAgain, () => {
      attempts = 0;
      connect(false);
    }, TEXT.reload, () => location.reload());
    return;
  }
  wait(TEXT.reconnectTitle, TEXT.reconnectText(phoneName || 'the phone'));
  const delay = Math.min(10000, 500 * 2 ** Math.min(5, attempts - 1));
  // A desktop someone logged out of on the phone is not booted up again
  // behind them: a comeback never starts it; the card that says so does.
  retryTimer = setTimeout(() => connect(false, true), delay);
}

function onText(m) {
  switch (m.t) {
    case 'state': {
      const state = String(m.state);
      const message = typeof m.message === 'string' ? m.message : '';
      if (state === 'starting') {
        wait(TEXT.startingTitle, TEXT.startingText);
      } else if (state === 'running') {
        if (!up) wait(TEXT.drawingTitle, TEXT.drawingText);
      } else {
        told = true;
        // Over: this page lets go of the stream itself, so that nothing
        // still in flight can draw the desktop back over what it says.
        closeSocket();
        letScreenGo();
        const again = () => connect(true);
        if (state === 'unauthorized') {
          token = null;
          store.set('lod.token', null);
          showPair(TEXT.signedOut);
        } else if (state === 'stopped') {
          ended(TEXT.stoppedTitle, message || TEXT.stoppedText, TEXT.start, again);
        } else if (state === 'replaced') {
          ended(TEXT.replacedTitle, TEXT.replacedText, TEXT.showHere, again);
        } else if (state === 'off') {
          ended(TEXT.offTitle, TEXT.offText, TEXT.tryAgain, () => connect(false));
        } else if (state === 'missing') {
          ended(TEXT.missingTitle, TEXT.missingText, TEXT.tryAgain, again);
        } else if (state === 'installing') {
          ended(TEXT.installingTitle, TEXT.installingText, TEXT.tryAgain, again);
        } else if (state === 'unfinished') {
          ended(TEXT.unfinishedTitle, TEXT.unfinishedText, TEXT.tryAgain, again);
        } else {
          ended(TEXT.failedTitle, message || TEXT.failedText, TEXT.tryAgain, again);
        }
      }
      break;
    }
    case 'video': {
      dw = Number(m.dw);
      dh = Number(m.dh);
      cw = Number(m.cw);
      ch = Number(m.ch);
      encoderName = String(m.encoder || '');
      encoderHw = m.hw !== false;
      needKey = true;
      canvas.classList.toggle('smooth', cw < dw - 1 || ch < dh - 1);
      configured = true;
      everConnected = true;
      // A new size is a new ceiling: the picture's bitrate goes with its pixels.
      const want = bitrateFor(dw, dh, mode);
      if (sentBitrate && Math.abs(want - sentBitrate) / sentBitrate > 0.1) {
        sentBitrate = want;
        send(JSON.stringify({ t: 'quality', ...modeParams(dw, dh) }));
      }
      // The window decides the size: tell the phone if it is not this. What
      // was asked and got is done with; what was asked and not got (held to
      // the phone's limits) is not asked again until the window changes.
      if (asked === `${dw}x${dh}`) asked = '';
      askSize();
      break;
    }
    case 'clip':
      if (typeof m.text === 'string') fromGuest(m.text);
      break;
    case 'sound':
      onSoundFormat(m);
      break;
    case 'stats':
      phoneStats = {
        kbps: Number(m.kbps) || 0,
        encodeMs: Number(m.encodeMs) || 0,
        soundKbps: Number(m.soundKbps) || 0,
        at: performance.now(),
      };
      break;
    case 'pong':
      if (typeof m.n === 'number') rtt = Math.round(performance.now() - m.n);
      break;
    default:
  }
}

// Once a second: the numbers on the bar, and whether the screen should stay on.
setInterval(() => {
  const now = performance.now();
  const fps = drawn;
  drawn = 0;
  if (fps > 0) lastMotion = now;
  if (!up) return;
  const fresh = now - phoneStats.at < 2500;
  const mbps = fresh ? Math.round(phoneStats.kbps / 100) / 10 : 0;
  $('stats').textContent = `${fps ? `${fps} fps` : TEXT.still} · ${mbps} Mb/s · ${rtt} ms`;
  $('stats').title = TEXT.statsTitle({
    encodeMs: phoneStats.encodeMs,
    encoder: encoderName,
    hw: encoderHw,
    player: USE_PLAYER,
    decodeMs: Math.round(decodeMs * 10) / 10,
    size: cw && (cw < dw - 1 || ch < dh - 1) ? `${cw}×${ch} drawn at ${dw}×${dh}` : `${dw}×${dh}`,
    soundKbps: fresh ? phoneStats.soundKbps : 0,
    sound: describeSound(soundFmt),
  });
  // As a video player does: awake while there is motion, asleep a while after.
  if (now - lastMotion < 120000) void holdScreen();
  else letScreenGo();
}, 1000);

// Every two seconds while a stream is open, whatever is on screen: it is
// also how the phone knows this page is still here (it lets go of a browser
// silent for over a minute, and of the wake lock it holds for it).
setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN) send(JSON.stringify({ t: 'ping', n: performance.now() }));
}, 2000);

// Closing the tab, or going elsewhere in it, while Linux is on screen: asked
// first, as a page with unsaved work asks. The browser words the question.
window.addEventListener('beforeunload', (e) => {
  if (!up || leaving) return;
  e.preventDefault();
  e.returnValue = '';
});

window.addEventListener('pagehide', () => {
  quiet = true;
  closeSocket();
});

// ── start ──────────────────────────────────────────────────────────────

function unsupported(title, text) {
  $('ended-title').textContent = title;
  $('ended-text').textContent = text;
  $('ended-go').hidden = true;
  $('ended-alt').hidden = true;
  cover('ended');
}

// ── plain http: the secure address first ───────────────────────────────

function secureUrl() {
  return `https://${location.host}${location.pathname}`;
}

function goSecure() {
  store.set('lod.plain', null);
  leaving = true;
  location.href = secureUrl();
}

/**
 * Whether this browser takes the phone's certificate already (it was let
 * through once): a request to the same address over https that comes back at
 * all. One that has not been fails at the handshake, without a word to anyone.
 */
async function secureWorks() {
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), 2500);
  try {
    await fetch(`https://${location.host}/hello`, { mode: 'no-cors', cache: 'no-store', signal: stop.signal });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/** The way in, asked once in this browser: https (a warning, the first time) or this page as it is. */
function choose() {
  $('choose-title').textContent = TEXT.chooseTitle;
  $('choose-text').textContent = TEXT.chooseText;
  $('choose-fine').textContent = TEXT.chooseFine;
  const secure = $('choose-secure');
  secure.textContent = TEXT.chooseSecure;
  // A link of its own, so that Back comes here again if the browser refuses it.
  secure.onclick = () => {
    leaving = true;
    location.href = secureUrl();
  };
  const plain = $('choose-plain');
  plain.textContent = TEXT.choosePlain;
  plain.onclick = () => {
    store.set('lod.plain', '1');
    void begin();
  };
  cover('choose');
  secure.focus({ preventScroll: true });
}

async function boot() {
  paintModes();
  setShare(share, false);
  $('full').title = TEXT.fullTitle;
  $('full').setAttribute('aria-label', TEXT.fullTitle);
  $('more').title = TEXT.moreTitle;
  $('more').setAttribute('aria-label', TEXT.moreTitle);
  const sb = $('sound');
  sb.setAttribute('aria-pressed', String(soundOn));
  sb.title = soundOn ? TEXT.soundTitleOn : TEXT.soundTitleOff;
  sb.setAttribute('aria-label', sb.title);
  if (!('VideoDecoder' in window) && !MsePlayer.supported()) {
    unsupported(TEXT.oldTitle, TEXT.oldText);
    return;
  }
  if (PLAIN) {
    const chip = $('bar-plain');
    chip.textContent = TEXT.plainNote;
    chip.title = TEXT.plainTitle;
    chip.hidden = false;
    $('m-secure').textContent = TEXT.goSecure;
    $('m-secure').hidden = false;
    if (store.get('lod.plain') !== '1') {
      wait(TEXT.connectingTitle, TEXT.connectingText('the phone'));
      if (await secureWorks()) {
        leaving = true;
        location.replace(secureUrl());
        return;
      }
      choose();
      return;
    }
  }
  await begin();
}

async function begin() {
  wait(TEXT.connectingTitle, TEXT.connectingText('the phone'));
  soundOpus = await canOpus();
  await learnName();
  // A page loaded again (a reload, a tab the browser brought back) shows the
  // desktop if it is running and asks before starting one: only a person's
  // own action starts Linux.
  if (token) connect(false);
  else showPair();
}

$('m-secure').addEventListener('click', () => {
  closeMenu();
  goSecure();
});

/** The phone's name, which it gives only to a browser it let in. */
async function learnName() {
  if (!token) return;
  try {
    const r = await fetch('hello', { cache: 'no-store', headers: { Authorization: `Bearer ${token}` } });
    const j = await r.json();
    phoneName = typeof j.name === 'string' ? j.name : '';
  } catch {
    phoneName = '';
  }
  if (phoneName) {
    document.title = `Linux · ${phoneName}`;
    $('bar-name').textContent = phoneName;
  }
}

void boot();
