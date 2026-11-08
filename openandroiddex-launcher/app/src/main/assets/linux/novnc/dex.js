/*
 * The Linux viewer: the connection, the picture, and the controls around it.
 *
 * What this file owns: booting RFB, the status surface the Android side polls,
 * the coordinate model, zoom and pan, and the chrome state machine (the docked
 * bar, the floating bubble, the panel it opens into). Touch gestures live in
 * dex-input.js and the keyboard in dex-keys.js — each is a state machine with
 * its own constant table, and splitting exactly there is what makes either
 * reviewable on its own.
 *
 * Two facts about noVNC shape everything here.
 *
 * There is no public method to send a pointer event — in this version or any
 * other. `_sendMouse` is private and `RFB.messages.pointerEvent` needs the
 * private socket and display. So every pointer action we invent enters through
 * the DOM as a synthetic MouseEvent at RFB's own canvas (dex-input.js), and
 * `sendKey` — which IS public — carries every key.
 *
 * And noVNC measures a click against `canvas.getBoundingClientRect()` while
 * dividing by a scale it keeps itself. A CSS scale changes the first and not
 * the second, so every click lands off by the factor, drifting further from
 * the transform origin, with nothing in the console. Hence: #screen is SIZED
 * to zoom it and TRANSLATED to pan it, and the scale used for coordinates is
 * re-measured from noVNC's own rendered box every time, never remembered.
 */

import RFB from './core/rfb.js';
import { initInput } from './dex-input.js';
import { initKeys } from './dex-keys.js';
import { guestSize, sharpScale } from './dex-size.js';

const $ = (id) => document.getElementById(id);

/* ── the two string tables ──────────────────────────────────────────────── */

/*
 * #status is a contract, not copy. LinuxActivity.probeVncHealth() reads this
 * element's textContent every 1500 ms and decides the session is dead if it
 * contains "closed", "Failed" or "went wrong" — a substring match, on English.
 * So these are frozen literals, never interpolated (the desktop name and a
 * security failure's reason are server-supplied text that could carry one of
 * those tokens while we are still connecting), and never localised.
 */
const MACHINE = Object.freeze({
  loading: 'Loading',
  connecting: 'Connecting',
  connected: 'Connected',
  closed: 'The connection is closed',
  broke: 'Something went wrong, the connection is closed',
  failed: 'Authentication Failed',
});

/* Everything the user actually reads. English, in one place, so the i18n this
   page does not yet have is a single object swap rather than a hunt. */
const TEXT = Object.freeze({
  starting: 'Starting…',
  connecting: 'Connecting…',
  connected: 'Connected',
  reconnecting: 'Reconnecting…',
  modeDirect: 'Interaction method: Direct',
  modeTouch: 'Interaction method: Touch',
  modeMouse: 'Interaction method: Mouse',
  kbShow: 'Show keyboard',
  kbHide: 'Hide keyboard',
  fit: 'Fit',
  coachCollapse: 'Tap the chevron to free the whole screen.',
  coachBubble: 'Tap the bubble for controls. Hold and drag to move it.',
  toastMouse: 'Drag anywhere to move the pointer.',
  toastTouch: 'Tap the desktop the way you would touch it.',
  toastDirect: 'The pointer goes where you point.',
  toastCad: 'Ctrl+Alt+Del sent',
  toastMiddle: 'Next tap is a middle click',
  toastRight: 'Next tap is a right click',
  footDesktop: 'A mouse is connected. These methods apply to touch only.',
  /* The markup's own wording, so the footnote can go back to it if this window
     stops being a mouse-driven one (see setDesktopChrome). */
  footTouch: 'A keyboard or mouse plugged into the desktop always works as it normally does.',
  fullOn: 'Leave fullscreen',
  fullOff: 'Fullscreen',
  clipOn: 'Clipboard shared with this computer',
  clipOff: 'Clipboard not shared',
});

/* ── constants ──────────────────────────────────────────────────────────── */

const ZOOM_STEP = 1.25;
const ZOOM_MIN = 0.25;
const ZOOM_MAX = 4.0;
/* The most a computer's window may ask the guest's screen to be — the daemon
   holds a start to the same, because every pixel is drawn by the phone's CPU
   and encoded again for every viewer. */
const PC_MAX_W = 3840;
const PC_MAX_H = 2400;
/* How long a window's new size has to hold before the guest is asked to match
   it: a phone turning is one change, a window being dragged is many — noVNC's
   own wait. See "the guest's screen follows the window". */
const FOLLOW_PHONE_MS = 150;
const FOLLOW_DESKTOP_MS = 500;
const FOLLOW_SLOP = 1;        // CSS px a box may move by and still be the same box
const FOLLOW_TRIES = 50;      // 100 ms apart: Xvnc says it can resize in its first update
const KB_GONE_MS = 1000;      // a keyboard that resizes the page takes this to give it back
const KB_LEARN_MS = 2000;     // and this, after it is raised, to show which kind it is
const IDLE_TUCK_MS = 3000;
const COACH_MS = 7000;
const TOAST_MS = 4000;
const EDGE_MARGIN = 12;
const BUBBLE_KEEPOUT = 96;   // XFCE's panels live at the top and bottom edges
const MOVE_SLOP = 12;
const SAVE_MS = 400;

/* ── persistence ────────────────────────────────────────────────────────── */

/*
 * localStorage is the only memory that survives here. The WebView is destroyed
 * and rebuilt whenever the runtime pid changes, reloaded by the health probe,
 * and the whole activity is recreated by a density change — but the origin
 * (http://127.0.0.1:6080) never moves.
 */
const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem('dexlinux.' + key);
      return v === null ? fallback : v;
    } catch (e) { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem('dexlinux.' + key, String(value)); } catch (e) { /* private mode */ }
  },
};

/* ── which window this is ───────────────────────────────────────────────── */

/*
 * 'phone' — the app-list entry (LinuxAppActivity), where this page IS the
 * phone's screen and there is no pointer at all — or 'desktop', a freeform
 * window on the DeX display with a real mouse driving it.
 *
 * It arrives in the URL because only LinuxActivity can know it: the two are
 * told apart by DISPLAY ID (see its onPhone), and nothing inside a WebView can
 * read one. Both ways of guessing are wrong here, in opposite directions —
 * `pointer: coarse` is what the scrcpy display reports for injected events, and
 * the viewport is phone-narrow in CSS px on a 1440p desktop at three of the
 * five display-size presets. An unmarked URL means desktop, which is what every
 * page load before this parameter existed was.
 *
 * 'pc' is the third window, on a computer rather than on the phone: the desktop
 * app's (or a browser tab, from `dexremote desktop`), reached through remote
 * access. A mouse and a keyboard of its own, so Direct and nothing else — see
 * the window section below.
 */
let ctx = 'desktop';

function readCtx() {
  try {
    const c = new URLSearchParams(location.search).get('ctx');
    return c === 'phone' || c === 'pc' ? c : 'desktop';
  } catch (e) { return 'desktop'; }
}

/* ── the window around a computer's page ────────────────────────────────── */

/*
 * On a computer this page is an iframe in the desktop app's window, from
 * another origin, so neither can reach into the other: they post messages,
 * all small and none secret. This page says how the connection is and passes
 * up the key that belongs to the window; the window says what the path to the
 * phone can carry and whether it is fullscreen. A message is acted on only if
 * that window sent it. In a plain browser tab there is no window around the
 * page, and the browser's own fullscreen stands in.
 */
let host = null;

function tellHost(msg) {
  if (!host) return;
  try { host.postMessage(Object.assign({ oadx: 1 }, msg), '*'); } catch (e) { /* closing */ }
}

/* What the path can carry, in noVNC's two documented knobs. Until the window
   says, noVNC's own defaults stand. */
let quality = null;

function applyQuality() {
  if (!rfb || !quality) return;
  rfb.qualityLevel = quality.q;
  rfb.compressionLevel = quality.c;
}

function onHostMessage(e) {
  if (!host || e.source !== host) return;
  const m = e.data;
  if (!m || m.oadx !== 1) return;
  if (Number.isInteger(m.quality) && Number.isInteger(m.compression)) {
    quality = { q: clamp(m.quality, 0, 9), c: clamp(m.compression, 0, 9) };
    applyQuality();
  }
  if (typeof m.fullscreen === 'boolean') setFullscreenChrome(m.fullscreen);
  if (typeof m.link === 'string') document.querySelector('.status-pill').title = m.link;
  if (m.focus === true) refocus();
  if (typeof m.share === 'boolean') setShare(m.share);
  if (typeof m.paste === 'string') paste(m.paste);
}

/*
 * The clipboard, both ways. The window holds the computer's clipboard and the
 * switch; this page holds the guest's end. Text from the window goes to Xvnc
 * as this client's clipboard — Xvnc then owns CLIPBOARD and PRIMARY in the
 * guest and hands it to whichever program pastes — and whatever Xvnc
 * announces from the guest goes up to the window. Nothing here can read or
 * write the computer's clipboard, and the switch is the window's to keep:
 * this page draws it and asks for it to flip.
 */
let share = null;           // the window's word; until it says, no button
let pasteWaiting = null;    // what came before the guest could take it whole
let pasteTimer = 0;
let connectedAt = 0;
let fromHost = false;       // the guest is holding, right now, what the window gave it

function setShare(on) {
  const was = share;
  share = on;
  for (const id of ['clip', 'p-clip']) {
    const el = $(id);
    el.hidden = false;
    el.classList.toggle('on', on);
    el.setAttribute('aria-pressed', String(on));
    el.setAttribute('aria-label', on ? TEXT.clipOn : TEXT.clipOff);
    el.title = on ? TEXT.clipOn : TEXT.clipOff;
  }
  if (on) return;
  pasteWaiting = null;
  /* Off means off: the computer's text, if that is what the guest has, does
     not stay there to be pasted. The guest's own clipboard is left alone. */
  if (was && fromHost && rfb && !ui.down) rfb.clipboardPasteFrom('');
  fromHost = false;
}

/*
 * noVNC has two ways to hand Xvnc a paste and picks by what the server said it
 * can take — which Xvnc says in answer to the encodings, a round trip after
 * 'connect'. Asked sooner, noVNC takes the old way, Latin-1, and every other
 * character arrives as a byte of nonsense (measured: "—" landed as U+0014).
 * Nothing public says when that answer came, and the canvas is no clue — it is
 * sized from ServerInit, before it — so a paste waits for noVNC 1.3's own
 * record of it, or, where that is missing, for a second and a half.
 */
function guestTakesUtf8() {
  const formats = rfb && rfb._clipboardServerCapabilitiesFormats;
  if (formats === undefined || formats === null) return Date.now() - connectedAt > 1500;
  return !!formats[1] || Date.now() - connectedAt > 5000;
}

function paste(text) {
  if (!share || !rfb || ui.down) return;
  pasteWaiting = text;
  flushPaste();
}

function flushPaste() {
  clearTimeout(pasteTimer);
  if (pasteWaiting === null || !rfb || ui.down || !share) return;
  if (!connectedAt || !guestTakesUtf8()) {
    pasteTimer = setTimeout(flushPaste, 50);
    return;
  }
  const text = pasteWaiting;
  pasteWaiting = null;
  rfb.clipboardPasteFrom(text);
  fromHost = true;
}

function onGuestClipboard(e) {
  const text = e.detail && typeof e.detail.text === 'string' ? e.detail.text : null;
  if (text === null) return;
  fromHost = false;
  /* noVNC 1.3 turns only the first CRLF Xvnc sends into a LF. */
  if (share) tellHost({ clip: text.replace(/\r\n?/g, '\n') });
}

/*
 * F11 is the window's, as it is in every browser: passed up and never to the
 * guest. Bare only — with a modifier it is a shortcut meant for an app in the
 * desktop. Its repeats and its release go nowhere either, so the guest is
 * never handed half a key.
 */
function onHostKey(e) {
  if (e.key !== 'F11' || e.ctrlKey || e.altKey || e.metaKey || e.shiftKey) return;
  e.preventDefault();
  e.stopImmediatePropagation();
  if (e.type === 'keydown' && !e.repeat) toggleFullscreen();
}

function toggleFullscreen() {
  if (host) {
    tellHost({ fullscreen: 'toggle' });
    return;
  }
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else if (document.documentElement.requestFullscreen) {
    document.documentElement.requestFullscreen().catch(() => {});
  }
}

/*
 * Fullscreen is for the picture. The bar folds into the bubble, so a desktop
 * started at the screen's size fills it at one pixel per pixel, and comes back
 * as it was when fullscreen ends. Neither is a choice the user made, so
 * neither is remembered.
 */
let chromeBeforeFull = null;

function setFullscreenChrome(on) {
  for (const id of ['full', 'p-full']) {
    $(id).classList.toggle('on', on);
    $(id).setAttribute('aria-label', on ? TEXT.fullOn : TEXT.fullOff);
  }
  if (on && chromeBeforeFull === null) {
    chromeBeforeFull = ui.chrome === 'docked' ? 'docked' : 'bubble';
    if (ui.chrome === 'docked') setChrome('bubble', false);
  } else if (!on && chromeBeforeFull !== null) {
    const back = chromeBeforeFull;
    chromeBeforeFull = null;
    if (back === 'docked' && ui.chrome !== 'docked') setChrome('docked', false);
  }
}

/* ── state ──────────────────────────────────────────────────────────────── */

const ui = {
  chrome: 'docked',   // 'docked' | 'bubble' | 'panel'
  sheet: false,
  kb: false,
  keys: false,
  zoomUI: false,
  /*
   * 'direct' | 'touch' | 'mouse'. Only ever affects TOUCH: a real mouse is
   * never remapped in any of the three. The default depends on which window
   * this is — see defaultMode. This value is only what the page holds between
   * parse and boot(), which settles it before anything can read it.
   */
  mode: 'direct',
  zoom: 'fit',        // 'fit' | number (CSS px per framebuffer px)
  down: false,        // the connection has gone
};

let rfb = null;
let canvas = null;
let panX = 0, panY = 0;
let fitScale = 1;
let availH = 0;   // stage content height once the chrome band is reserved
let saveTimer = 0;
let coachTimer = 0;
let toastTimer = 0;
let tuckTimer = 0;

const stage = $('stage');
const screenEl = $('screen');
const bar = $('bar');
const bubble = $('bubble');
const panel = $('panel');
const strip = $('zoom-strip');
const sheet = $('sheet');
const scrim = $('scrim');
const statusEl = $('status');
const label = $('label');
const dot = $('dot');
const coachEl = $('coach');
const toastEl = $('toast');

let bubblePos = readBubblePos();

/* ── the connection ─────────────────────────────────────────────────────── */

function connect() {
  const params = new URLSearchParams(location.search);
  const password = params.get('password') || undefined;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = proto + '://' + location.host + '/websockify';

  setStatus('connecting', TEXT.connecting, '');

  rfb = new RFB(screenEl, url, { credentials: { password } });

  /* All documented API. scaleViewport waits for a framebuffer — autoscale
     divides by the framebuffer size and there is not one yet. */
  rfb.clipViewport = false;    // mutually exclusive with scaleViewport by design
  rfb.scaleViewport = false;
  /* On a computer noVNC itself keeps the guest's screen the window's size: it
     asks Xvnc for the box once it is connected and after every resize, half a
     second after the last. Only there — it asks in CSS px, which would drag a
     portrait phone's XFCE to ~360x760 — so the phone's window and the desktop
     display's ask for sizes of their own (followWindow). */
  rfb.resizeSession = ctx === 'pc';
  rfb.dragViewport = false;    // hijacks button 0 wholesale
  rfb.focusOnClick = false;    // we own focus — a tap must not steal it from the sink
  rfb.showDotCursor = false;
  applyQuality();

  canvas = screenEl.querySelector('canvas');

  rfb.addEventListener('connect', () => {
    connectedAt = Date.now();
    setStatus('connected', TEXT.connecting, '');
    canvas = canvas || screenEl.querySelector('canvas');
    watchFramebuffer();
    /* A computer's keyboard types into the desktop from the first key, with
       no click on the picture first. */
    if (ctx === 'pc') rfb.focus();
  });

  /*
   * A CLEAN disconnect gets a dead token too, deliberately. Stock vnc_lite
   * says "Disconnected" for a clean close, which contains none of the three
   * tokens the Activity looks for — and because Display leaves the canvas at
   * its last size, canvas.width stays non-zero and the probe reports a healthy
   * session forever. The user is left staring at a frozen last frame with no
   * error and no Retry. Any disconnect says "closed".
   */
  rfb.addEventListener('disconnect', (e) => {
    down(e.detail && e.detail.clean ? 'closed' : 'broke');
  });
  rfb.addEventListener('securityfailure', () => down('failed'));
  rfb.addEventListener('credentialsrequired', () => down('failed'));
  /* Only a computer's window has anywhere to put it. */
  if (host) rfb.addEventListener('clipboard', onGuestClipboard);

  /* The desktop name is server-supplied text. It may go on the visible label;
     it may never go near #status. */
  rfb.addEventListener('desktopname', (e) => {
    const name = e.detail && e.detail.name;
    if (name && !ui.down) label.title = String(name);
  });

  input = initInput(api);
  keys = initKeys(api);
}

/*
 * canvas.width crossing zero is the "we have a real framebuffer" signal — the
 * same one the Android probe trusts. noVNC 1.3.0 fires no public event for a
 * remote resize, and width is a reflected attribute, so an observer sees both
 * the first frame and every later RandR change.
 */
function watchFramebuffer() {
  if (!canvas) return;
  new MutationObserver(onFramebuffer).observe(canvas, {
    attributes: true, attributeFilter: ['width', 'height'],
  });
  onFramebuffer();
}

let painted = false;
let shape = '';   // the framebuffer size the zoom was last read for
function onFramebuffer() {
  if (!canvas || !canvas.width) return;
  const now = canvas.width + 'x' + canvas.height;
  let reshaped = false;
  if (!painted) {
    painted = true;
    shape = now;
    ui.zoom = readZoom();
    /* Size #screen BEFORE handing scaling over: autoscale measures the box it
       is given, and an auto-width div with a canvas inside it has not settled
       on one yet. Enabling it first makes noVNC scale to nothing for a frame. */
    layout();
    rfb.scaleViewport = true;
    label.textContent = TEXT.connected;
    dot.classList.add('live');
    dot.classList.remove('bad');
    /* The state is the dot's job once it is green; the word is noise over
       someone's desktop. */
    setTimeout(() => label.classList.add('quiet'), 2000);
    if (input) input.recentre();
    firstRunCoach();
  } else if (now !== shape) {
    /* The desktop changed shape: the phone turned, the window was resized, or
       another viewer asked. It opens at the zoom last used for this shape —
       fit, unless someone zoomed it — for the reason zoomKey gives, centred:
       a pan into the old shape means nothing in the new one. */
    shape = now;
    ui.zoom = readZoom();
    panX = panY = 0;
    reshaped = true;
  }
  layout();
  /* The cursor ring is placed in client coordinates, so only once the picture
     has been laid out at its new size. */
  if (reshaped && input) input.recentre();
  /* The first frame is the first moment the desktop's size is known. One that
     was started for another window, or sized since by another viewer, is asked
     to fit this one. */
  if (!followed) {
    followed = true;
    followWindow(true);
  }
}

function down(kind) {
  if (ui.down) return;
  ui.down = true;
  clearTimeout(followTimer);
  document.body.classList.add('is-down');
  setStatus(kind, TEXT.reconnecting, 'bad');
  if (input) input.releaseAll();
  if (keys) keys.clearLatches();
  setKb(false);
  closeSheet();
  setChrome('docked', false);
}

/*
 * The page never reconnects itself. LinuxActivity owns the only retry ladder —
 * it reloads this WebView after two dead polls, on a budget shared with its
 * own load retries. A second loop in here would race it, burn that budget
 * invisibly, and put two sockets on one Xvnc.
 */
function setStatus(machineKey, visible, dotClass) {
  statusEl.textContent = MACHINE[machineKey];
  /* The app's window reconnects on a computer, the way LinuxActivity's retry
     ladder does on the phone, and this is the only way it can tell. */
  tellHost({ status: machineKey });
  label.textContent = visible;
  label.classList.remove('quiet');
  dot.classList.remove('live', 'bad');
  if (dotClass) dot.classList.add(dotClass);
}

/* ── coordinates, zoom and pan ──────────────────────────────────────────── */

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

const fb = () => ({ w: canvas ? canvas.width : 0, h: canvas ? canvas.height : 0 });

/*
 * The scale is re-measured from noVNC's own rendered box on every call, never
 * read back from ui.zoom. noVNC rescales from a ResizeObserver, so its number
 * lands a frame after we resize #screen and absX() divides by ITS number.
 * Deriving ours from its box is the only mapping that cannot drift.
 */
function zNow() {
  if (!canvas || !canvas.width) return 1;
  return canvas.getBoundingClientRect().width / canvas.width;
}

function toFb(cx, cy) {
  const r = canvas.getBoundingClientRect();
  const s = r.width / canvas.width;
  return { x: (cx - r.left) / s, y: (cy - r.top) / s };
}

/*
 * The exact inverse of toFb, with no bias. An anchored zoom needs
 * toClientRaw(toFb(p)) === p — feeding the biased form back in pans the
 * picture by half a pixel on every step, which accumulates.
 */
function toClientRaw(fx, fy) {
  const r = canvas.getBoundingClientRect();
  const s = r.width / canvas.width;
  return { x: r.left + fx * s, y: r.top + fy * s };
}

/* +.5 centres the point inside the target pixel before absX() truncates.
   Wire coordinates only. */
const toClient = (fx, fy) => toClientRaw(fx + 0.5, fy + 0.5);

function cssPx(name) {
  return parseFloat(getComputedStyle(document.documentElement)
    .getPropertyValue(name)) || 0;
}

/* The band of the stage the chrome is sitting on. --vv-top is in it because
   the bar is positioned at that offset: when the platform pans the window for
   the IME instead of resizing it, the bar moves down and the reserved band has
   to grow by exactly as much. */
function stageInsets() {
  return {
    top: ui.chrome === 'docked' ? cssPx('--vv-top') + bar.offsetHeight : 0,
    bottom: cssPx('--kb-h')
      + (ui.keys ? document.getElementById('keys-row').offsetHeight : 0),
  };
}

function layout() {
  if (ctx === 'pc') {
    layoutToFill();
    return;
  }
  const { w: fw, h: fh } = fb();
  if (!fw) return;
  const ins = stageInsets();
  /*
   * The chrome sits ON the stage, so the space it takes has to come out of the
   * BOX the picture is centred in, not only out of the size it is fitted to.
   * Padding does that with the grid centring already in place: reserve it here
   * and `place-items: center` lands the picture flush under the bar instead of
   * splitting the shortfall evenly and hiding the guest's top panel behind it.
   */
  stage.style.paddingTop = ins.top + 'px';
  stage.style.paddingBottom = ins.bottom + 'px';
  /* The stage's own fractional size, not clientWidth/clientHeight, which are
     rounded: the desktop is sized to the fractional box (steadyBox), and a
     fit to a rounded one fell up to a pixel and a half short on a phone on
     its side (measured on the S25: 366.48 tall read as 366, and the ratio
     carries the shortfall across). */
  const sr = stage.getBoundingClientRect();
  const availW = Math.max(1, sr.width);
  availH = Math.max(1, sr.height - ins.top - ins.bottom);
  fitScale = Math.min(availW / fw, availH / fh);
  /*
   * A desktop sized for this window on the desktop display fits at whole device
   * pixels per guest pixel, give or take the pixel its size was rounded down by.
   * Fitted exactly instead, it would be drawn a hair larger than that, and a
   * scale of 1.001 resamples every line of text in it. Within 2 % of sharp,
   * sharp wins; anything else, someone else's size included, is fitted.
   */
  if (ctx === 'desktop') {
    const sharp = sharpScale(window.devicePixelRatio || 1);
    if (sharp <= fitScale && sharp >= fitScale * 0.98) fitScale = sharp;
  }
  const z = ui.zoom === 'fit' ? fitScale : clamp(ui.zoom, ZOOM_MIN, ZOOM_MAX);
  screenEl.style.width = fw * z + 'px';
  screenEl.style.height = fh * z + 'px';
  /*
   * noVNC rescales its canvas from a ResizeObserver, which lands a frame later
   * — and until it does, every measurement below still reads the OLD scale.
   * Re-asserting the public property re-runs its scale update NOW, against the
   * box just sized, so the anchor correction in setZoom(), the pinch focal and
   * the cursor ring all measure the scale they are about to be drawn at.
   * Guarded on the getter so the deliberate ordering at first paint — size the
   * box, THEN hand scaling over — is preserved.
   */
  if (rfb && rfb.scaleViewport) rfb.scaleViewport = true;
  clampPan();
  applyPan();
  paintZoomValue();
  /* The ring is placed in client coordinates, so anything that moves the
     picture has to move it too. */
  if (input) input.paintCursor();
  /* Everything that can change the window's box comes through here. */
  followWindow(false);
}

/*
 * On a computer the box is not fitted to the picture: the box is the stage, and
 * the guest's screen is made its size (resizeSession), so the desktop fills the
 * window at one pixel per pixel — and follows it through every resize, the bar
 * folding away in fullscreen included. Until the guest has caught up, noVNC
 * scales the old picture into the new box. Sized before there is a framebuffer
 * too: noVNC asks for this box's size the moment it learns the guest can
 * resize, which is before the first frame.
 *
 * Only the bar takes height off it. The keys row and an IME are passing
 * things, laid over the picture rather than re-laying XFCE out every time one
 * opens.
 */
function layoutToFill() {
  const top = stageInsets().top;
  stage.style.paddingTop = top + 'px';
  stage.style.paddingBottom = '0px';
  availH = Math.max(1, stage.clientHeight - top);
  screenEl.style.width = Math.min(Math.max(1, stage.clientWidth), PC_MAX_W) + 'px';
  screenEl.style.height = Math.min(availH, PC_MAX_H) + 'px';
  if (rfb && rfb.scaleViewport) rfb.scaleViewport = true;
  panX = 0;
  panY = 0;
  applyPan();
  if (input) input.paintCursor();
}

function panBounds() {
  const cw = screenEl.offsetWidth, ch = screenEl.offsetHeight;
  return {
    x: Math.max(0, (cw - stage.clientWidth) / 2),
    /* Against the CONTENT box, not the viewport: a picture zoomed past the fit
       must be able to bring its top rows out from under the bar. */
    y: Math.max(0, (ch - (availH || stage.clientHeight)) / 2),
  };
}

function clampPan() {
  const b = panBounds();
  panX = clamp(panX, -b.x, b.x);
  panY = clamp(panY, -b.y, b.y);
}

/* The one transform that is safe: a translation moves the box and the pixels
   by exactly the same amount, so noVNC's coordinate maths is untouched. */
function applyPan() {
  screenEl.style.transform = `translate3d(${panX}px, ${panY}px, 0)`;
}

function panBy(dx, dy) {
  panX += dx; panY += dy;
  clampPan();
  applyPan();
}

function setZoom(z, anchorClientX, anchorClientY) {
  const before = (anchorClientX !== undefined && canvas && canvas.width)
    ? toFb(anchorClientX, anchorClientY) : null;
  ui.zoom = z === 'fit' ? 'fit' : clamp(z, ZOOM_MIN, ZOOM_MAX);
  layout();
  if (before) {
    const want = toClientRaw(before.x, before.y);
    panBy(anchorClientX - want.x, anchorClientY - want.y);
  }
  saveZoom();
}

function zoomBy(factor) {
  const cur = ui.zoom === 'fit' ? fitScale : ui.zoom;
  const r = stage.getBoundingClientRect();
  setZoom(cur * factor, r.left + r.width / 2, r.top + r.height / 2);
}

function paintZoomValue() {
  const v = $('zoom-value');
  if (!v) return;
  v.textContent = ui.zoom === 'fit' ? TEXT.fit : Math.round(ui.zoom * 100) + '%';
}

/* Keyed by framebuffer size: a desktop of a new shape — the phone turned, the
   window resized, a new $GEO — must start at fit, not at a factor chosen for a
   desktop that no longer exists, and turning back finds that shape's zoom. */
function zoomKey() {
  const { w, h } = fb();
  return 'zoom.' + w + 'x' + h;
}
function readZoom() {
  const v = store.get(zoomKey(), 'fit');
  if (v === 'fit') return 'fit';
  const n = parseFloat(v);
  return Number.isFinite(n) ? clamp(n, ZOOM_MIN, ZOOM_MAX) : 'fit';
}
function saveZoom() {
  if (!canvas || !canvas.width) return;
  /* The key and the value now, not when the timer fires: the desktop can
     change shape in between, and this shape's zoom would land on that one. */
  const key = zoomKey(), z = ui.zoom;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => store.set(key, z), SAVE_MS);
}

/* ── the guest's screen follows the window ──────────────────────────────── */

/*
 * The desktop is the window's shape. Turn the phone and XFCE is laid out again
 * for the other orientation; drag a window on the desktop display wider and the
 * desktop gets the width. Until this, the phone had a fixed 1280x800 that it
 * letterboxed into either orientation — most of a portrait screen black — and
 * the desktop display kept whatever size the start gave it. How big is
 * dex-size.js; this is when, and how.
 *
 * How: Xvnc takes a SetDesktopSize from any client and tells all of them
 * (ExtendedDesktopSize); noVNC resizes its canvas, watchFramebuffer sees that,
 * and layout() fits the new picture. noVNC 1.3 only ever asks through
 * resizeSession, and only for its own box in CSS px — on a phone a third of
 * the desktop it needs — so this sends the same message itself.
 * RFB.messages.setDesktopSize is public; the socket it writes to and the
 * screen it names are not: the same private reach guestTakesUtf8 makes, and
 * checked the same way. Where they are missing, the desktop keeps the size it
 * started at, letterboxed, as before.
 *
 * When: the box changing, once the new one has held for a moment — and once
 * at the first frame, so that a page connecting to a desktop someone else
 * sized asks for its own. Never BECAUSE the desktop changed size: that is
 * another viewer's ask (a computer through remote access, or on the cable),
 * and answering it would be a tug of war between two screens. The last ask to
 * reach Xvnc wins — not always the window that changed last, since each waits
 * its own time first.
 */
let followed = false;      // the first frame has asked
let followBox = null;      // the box the guest was last sized for, CSS px
let followAsked = null;    // what was asked for it: a refused size is not asked twice
let followTimer = 0;
let followTries = 0;
let followOff = false;     // this noVNC cannot be asked
let kbResizes = false;     // this window's keyboard resizes the page rather than covering it
let kbUpAt = 0;            // when the keyboard was last raised
let kbBase = null;         // the box just before it was
let kbDownAt = 0;          // when the keyboard last went down

const follows = () => ctx === 'phone' || ctx === 'desktop';

/*
 * The part of the stage the desktop has for good, in CSS px: less the docked
 * bar and a keys row the user pinned. Not less the keyboard, nor the keys row
 * it brings up: those come and go, and a desktop laid out again every time the
 * keyboard opened would be worse than one fitted above it meanwhile.
 * Fractional, as the WebView has it — which is what the launcher starts the
 * desktop from as well (LinuxGuestSize.forWindow).
 */
function steadyBox() {
  const r = stage.getBoundingClientRect();
  const top = ui.chrome === 'docked' ? bar.getBoundingClientRect().height : 0;
  const row = ui.keys && store.get('keys', '0') === '1'
    ? $('keys-row').getBoundingClientRect().height : 0;
  return { w: r.width, h: r.height - top - row };
}

/** @param {boolean} now ask at once, without waiting for the box to hold */
function followWindow(now) {
  if (!painted || !follows() || followOff || ui.down) return;
  const box = steadyBox();
  if (box.w < 1 || box.h < 1) return;
  const dw = followBox ? Math.abs(box.w - followBox.w) : Infinity;
  const dh = followBox ? box.h - followBox.h : Infinity;
  if (ui.kb) {
    /*
     * The keyboard may resize the page rather than cover it, and then nothing
     * measured while it is up is the window's own size — a turn made while
     * typing included: rotated, the box changes width and is still the
     * keyboard's height short. Which kind it is shows in the moment after it
     * is raised: the box against the one it had just before (kbBase), the same
     * width and less height. Only then — a turn's own sizes on the way round
     * are no sign of anything (on the S25 a turn with the keyboard up passes
     * through a box taller than where it lands, and taking that for this kind
     * left the desktop at the passing size until the keyboard closed). Once
     * seen it is believed for good, and while the keyboard is up the desktop
     * keeps its size; closing it lays the page out again (setKb), and a turn
     * lands then. Where the keyboard covers the page — as the S25's do — the
     * box is the window's with it up or down, and a turn lands at once.
     */
    const fresh = performance.now() - kbUpAt < KB_LEARN_MS;
    if (fresh && kbBase && Math.abs(box.w - kbBase.w) < FOLLOW_SLOP && box.h <= kbBase.h - FOLLOW_SLOP) {
      kbResizes = true;
    }
    if (kbResizes || !followBox) return;
  } else if (kbResizes) {
    /* Down, but not necessarily gone: such a keyboard gives the page its
       height back only once it has finished going, so for a moment after, the
       box is still its doing. Measured again when the moment is over. */
    const left = KB_GONE_MS - (performance.now() - kbDownAt);
    if (left > 0) {
      clearTimeout(followTimer);
      followTimer = setTimeout(() => followWindow(false), left + 20);
      return;
    }
  }
  if (!now && dw < FOLLOW_SLOP && Math.abs(dh) < FOLLOW_SLOP) return;
  followBox = box;
  followAsked = null;
  followTries = 0;
  clearTimeout(followTimer);
  followTimer = 0;
  if (now) askToFollow();
  else followTimer = setTimeout(askToFollow, ctx === 'phone' ? FOLLOW_PHONE_MS : FOLLOW_DESKTOP_MS);
}

function askToFollow() {
  clearTimeout(followTimer);
  followTimer = 0;
  if (!followBox || !follows() || followOff || ui.down || !rfb || !canvas || !canvas.width) return;
  const want = guestSize(ctx, followBox.w, followBox.h, window.devicePixelRatio || 1);
  if (canvas.width === want.w && canvas.height === want.h) return;
  if (followAsked && followAsked.w === want.w && followAsked.h === want.h) return;
  const sent = askGuestSize(want.w, want.h);
  if (sent === 'later') {
    if (++followTries <= FOLLOW_TRIES) followTimer = setTimeout(askToFollow, 100);
    return;
  }
  if (sent === 'never') {
    followOff = true;
    return;
  }
  followAsked = want;
}

/*
 * The message resizeSession sends, for a size of our choosing: 'later' until
 * Xvnc has said it can — in its first update, a round trip after 'connect' —
 * and 'never' on a noVNC without the pieces.
 *
 * The screen id and flags are the ones noVNC's own ask carries. In 1.3 they
 * are byte views, which the message turns into 0; Xvnc takes that for a new
 * screen, lets the old one go and gives the new one its output, so the resize
 * lands all the same — as it always has for resizeSession.
 */
function askGuestSize(w, h) {
  const send = RFB.messages && RFB.messages.setDesktopSize;
  const sock = rfb._sock;
  if (typeof send !== 'function' || !sock || typeof sock.flush !== 'function') return 'never';
  if (rfb._supportsSetDesktopSize !== true) return 'later';
  try {
    send(sock, w, h, rfb._screenID, rfb._screenFlags);
  } catch (e) {
    return 'never';
  }
  return 'sent';
}

/* ── chrome ─────────────────────────────────────────────────────────────── */

/*
 * The single writer of ui.chrome. Nothing else adds or removes chrome state.
 *
 * `persist` is false exactly once — when a dead connection forces the bar back
 * up. That is the page's decision, not the user's, and it must not overwrite a
 * preference they set deliberately.
 */
function setChrome(next, persist = true) {
  const was = ui.chrome;
  ui.chrome = next;
  document.body.dataset.chrome = next;
  bubble.hidden = next === 'docked';
  panel.hidden = next !== 'panel';
  if (next !== 'docked') placeBubble();
  if (next === 'panel') placePanel();
  if (next === 'bubble') armTuck();
  else clearTimeout(tuckTimer);
  if (next !== 'bubble') bubble.classList.remove('tuck');
  if (ui.zoomUI) placeStrip();
  if (persist && next !== was && next !== 'panel') store.set('chrome', next);
  layout();
}

function readBubblePos() {
  let p = { edge: 'right', y: 0.62 };
  try {
    const raw = store.get('bubble', '');
    if (raw) {
      const j = JSON.parse(raw);
      if (j && (j.edge === 'left' || j.edge === 'right')) p.edge = j.edge;
      if (typeof j.y === 'number' && Number.isFinite(j.y)) p.y = clamp(j.y, 0, 1);
    }
  } catch (e) { /* keep the default */ }
  return p;
}

function bubbleTop() {
  const size = bubble.offsetHeight || 56;
  const h = stage.clientHeight;
  const lo = BUBBLE_KEEPOUT;
  const hi = Math.max(lo, h - BUBBLE_KEEPOUT - size);
  return clamp(bubblePos.y * h, lo, hi);
}

function placeBubble() {
  const size = bubble.offsetWidth || 56;
  bubble.style.top = bubbleTop() + 'px';
  bubble.style.left = bubblePos.edge === 'right'
    ? stage.clientWidth - size - EDGE_MARGIN + 'px'
    : EDGE_MARGIN + 'px';
  bubble.classList.toggle('at-right', bubblePos.edge === 'right');
  bubble.classList.toggle('at-left', bubblePos.edge === 'left');
}

/* The panel grows from the bubble, downward unless the bubble is low enough
   that downward would run off the screen. */
function placePanel() {
  const size = bubble.offsetHeight || 56;
  const top = bubbleTop();
  const h = stage.clientHeight;
  /* Open towards whichever side has more room, and cap the panel at what is
     actually there. The stylesheet's max-height measures the viewport, which
     says nothing about the space left at the anchor — in landscape the panel
     would simply run off the bottom. */
  const below = h - cssPx('--kb-h') - (top + size + 8) - EDGE_MARGIN;
  const above = (top - 8) - EDGE_MARGIN;
  const downward = below >= above;
  panel.style.maxHeight = Math.max(0, downward ? below : above) + 'px';
  panel.style.left = panel.style.right = 'auto';
  if (bubblePos.edge === 'right') panel.style.right = EDGE_MARGIN + 'px';
  else panel.style.left = EDGE_MARGIN + 'px';
  if (downward) {
    panel.style.top = top + size + 8 + 'px';
    panel.style.bottom = 'auto';
    panel.style.transformOrigin = bubblePos.edge === 'right' ? 'top right' : 'top left';
  } else {
    panel.style.bottom = h - top + 8 + 'px';
    panel.style.top = 'auto';
    panel.style.transformOrigin = bubblePos.edge === 'right' ? 'bottom right' : 'bottom left';
  }
}

function placeStrip() {
  if (ui.chrome === 'docked') {
    strip.style.top = strip.style.left = strip.style.right = '';
    strip.style.transform = '';
    return;
  }
  const size = bubble.offsetHeight || 56;
  strip.style.top = bubbleTop() + size / 2 + 'px';
  strip.style.transform = 'translateY(-50%)';
  if (bubblePos.edge === 'right') {
    strip.style.right = EDGE_MARGIN + size + 10 + 'px';
    strip.style.left = 'auto';
  } else {
    strip.style.left = EDGE_MARGIN + size + 10 + 'px';
    strip.style.right = 'auto';
  }
}

function armTuck() {
  clearTimeout(tuckTimer);
  bubble.classList.remove('tuck');
  tuckTimer = setTimeout(() => {
    if (ui.chrome === 'bubble') bubble.classList.add('tuck');
  }, IDLE_TUCK_MS);
}

function openPanel() { setChrome('panel'); }
function closePanel() { if (ui.chrome === 'panel') setChrome('bubble'); }

/* ── the sheet ──────────────────────────────────────────────────────────── */

function openSheet() {
  setKb(false);              // no layout case has to survive both
  ui.sheet = true;
  scrim.hidden = false;
  sheet.hidden = false;
  paintMode();
  requestAnimationFrame(() => sheet.classList.add('open'));
}

function closeSheet() {
  if (!ui.sheet) return;
  ui.sheet = false;
  sheet.classList.remove('open');
  scrim.hidden = true;
  setTimeout(() => { if (!ui.sheet) sheet.hidden = true; }, 320);
}

const MODES = ['direct', 'touch', 'mouse'];

/*
 * The default is a different answer in each window, and neither is a
 * preference.
 *
 * On the DESKTOP display there is a real pointer, and Direct is what a mouse
 * should do: it goes where it is pointed, which is also how this window behaved
 * before the control layer existed.
 *
 * On the PHONE there is no pointer to follow. Direct there means poking at a
 * desktop of 900-odd pixels across through a ~400 px window with a fingertip
 * that covers a 40 px circle of it: a 1 px window border, a menu item and a
 * scrollbar are all inside one touch. Mouse turns the panel into a trackpad —
 * the cursor moves relative, accelerated, and every click lands at the ring
 * you can see rather than under the finger hiding it — and it is the only one
 * of the three that can hit anything small. Touch is the middle answer and
 * stays one tap away.
 */
function defaultMode() {
  return ctx === 'phone' ? 'mouse' : 'direct';
}

/*
 * A method the user picked is remembered PER WINDOW, under its own key.
 *
 * One shared key was the obvious thing and it is wrong: both windows are the
 * same origin (http://127.0.0.1:6080), so they are the same localStorage, and a
 * phone session left behind Mouse for the next desktop session to inherit —
 * the exact default this exists to get right. Per window, a choice sticks where
 * it was made and each side still opens on its own default the first time.
 */
function savedMode() {
  let v = store.get('mode.' + ctx, null);
  /* Before the app-list entry there was one window, always the desktop's, and
     one un-suffixed key. Honour that choice rather than resetting it. */
  if (v === null && ctx === 'desktop') v = store.get('mode', null);
  return MODES.indexOf(v) >= 0 ? v : defaultMode();
}

/**
 * @param {string} mode one of MODES
 * @param {boolean} [persist] false while APPLYING a default — a default that
 *   wrote itself to storage would be indistinguishable from a choice the next
 *   time this window opened, and would outlive any change to defaultMode.
 */
function setMode(mode, persist) {
  ui.mode = MODES.indexOf(mode) >= 0 ? mode : defaultMode();
  document.body.dataset.mode = ui.mode;
  if (persist !== false) store.set('mode.' + ctx, ui.mode);
  if (keys) keys.clearLatches();
  /* Whatever the old method was holding down is not the new method's to keep. */
  if (input) { input.releaseAll(); input.recentre(); }
  paintMode();
  toast(ui.mode === 'mouse' ? TEXT.toastMouse
    : ui.mode === 'touch' ? TEXT.toastTouch : TEXT.toastDirect);
}

function paintMode() {
  for (const m of MODES) $('opt-' + m).setAttribute('aria-checked', String(ui.mode === m));
  const aria = ui.mode === 'mouse' ? TEXT.modeMouse
    : ui.mode === 'touch' ? TEXT.modeTouch : TEXT.modeDirect;
  $('mode').setAttribute('aria-label', aria);
  $('p-mode').setAttribute('aria-label', aria);
  /* The ring is the pointer only when we are the ones moving it. */
  $('vcur').hidden = ui.mode !== 'mouse';
  $('field-sens').style.opacity = ui.mode === 'mouse' ? '1' : '.45';
}

/* ── keyboard, keys row ─────────────────────────────────────────────────── */

function setKb(on) {
  if (!keys) return;
  /* When it came and went, for followWindow: what the box was before it came
     tells a keyboard that resizes the page from one that covers it, and one
     that resizes is still giving the height back for a moment after it goes. */
  if (!ui.kb && on) {
    kbUpAt = performance.now();
    kbBase = painted ? steadyBox() : null;
  }
  if (ui.kb && !on) kbDownAt = performance.now();
  ui.kb = !!on;
  document.body.classList.toggle('kb', ui.kb);
  $('kb').classList.toggle('on', ui.kb);
  $('p-kb').classList.toggle('on', ui.kb);
  const aria = ui.kb ? TEXT.kbHide : TEXT.kbShow;
  $('kb').setAttribute('aria-label', aria);
  $('p-kb').setAttribute('aria-label', aria);
  if (ui.kb) { keys.showKeyboard(); setKeys(true, true); }
  else { keys.hideKeyboard(); setKeys(store.get('keys', '0') === '1', true); }
}

function setKeys(on, transient) {
  ui.keys = !!on;
  $('keys-row').hidden = !ui.keys;
  $('keys').classList.toggle('on', ui.keys);
  $('p-keys').classList.toggle('on', ui.keys);
  document.body.classList.toggle('keys', ui.keys);
  if (!transient) store.set('keys', ui.keys ? '1' : '0');
  layout();
}

/* ── coach marks and toasts ─────────────────────────────────────────────── */

/* Neither is shown once a real mouse has been seen: on the DeX display the bar
   is small, permanent and self-evident, and a tooltip over someone's desktop
   is just something else to dismiss. */
/* They share a position, so only one is ever up. The toast is always the newer,
   action-triggered message and evicts a first-run coach mark; the coach mark
   evicts a stale toast on the way in. */
function coach(text) {
  if (document.body.classList.contains('is-desktop')) return;
  clearTimeout(toastTimer);
  toastEl.hidden = true;
  coachEl.textContent = text;
  coachEl.hidden = false;
  clearTimeout(coachTimer);
  coachTimer = setTimeout(() => { coachEl.hidden = true; }, COACH_MS);
}

function toast(text) {
  if (document.body.classList.contains('is-desktop')) return;
  clearTimeout(coachTimer);
  coachEl.hidden = true;
  toastEl.textContent = text;
  toastEl.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastEl.hidden = true; }, TOAST_MS);
}

function firstRunCoach() {
  if (store.get('coach', '') === 'done') return;
  if (ui.chrome === 'docked') coach(TEXT.coachCollapse);
}

/* ── wiring ─────────────────────────────────────────────────────────────── */

/*
 * Every control denies itself focus and hands it back afterwards. A chrome
 * button that takes focus is the whole of the "typing worked, then stopped"
 * bug: the sink loses it mid-sentence on the phone, and the canvas loses it on
 * the desktop, which kills the physical keyboard path noVNC handles for us.
 */
function refocus() {
  if (!rfb) return;
  if (ui.kb && keys) keys.focusSink();
  else rfb.focus();
}

function openRemote() {
  if (ctx === 'pc') return;   // LinuxActivity's; nothing on a computer catches it
  location.href = 'dexremote:open';
}

function control(el, handler) {
  if (!el) return;
  el.setAttribute('tabindex', '-1');
  el.addEventListener('pointerdown', (e) => e.preventDefault());
  el.addEventListener('click', (e) => {
    e.preventDefault();
    handler(e);
    refocus();
  });
}

function wire() {
  control($('mode'), openSheet);
  control($('p-mode'), openSheet);
  control($('kb'), () => setKb(!ui.kb));
  control($('p-kb'), () => setKb(!ui.kb));
  control($('keys'), () => setKeys(!ui.keys));
  control($('p-keys'), () => setKeys(!ui.keys));
  control($('zoom'), toggleZoomUI);
  control($('p-zoom'), toggleZoomUI);
  control($('collapse'), () => {
    setChrome('bubble');
    if (store.get('coach', '') !== 'done') {
      store.set('coach', 'done');
      bubble.classList.add('wiggle');
      setTimeout(() => bubble.classList.remove('wiggle'), 700);
      coach(TEXT.coachBubble);
    }
  });
  control($('dock'), () => setChrome('docked'));
  // Remote access lives in the app, not in this page: a navigation the
  // Activity intercepts opens its window. See LinuxActivity's WebViewClient.
  control($('remote'), openRemote);
  control($('p-remote'), openRemote);
  control($('full'), toggleFullscreen);
  control($('p-full'), toggleFullscreen);
  control($('clip'), () => tellHost({ share: 'toggle' }));
  control($('p-clip'), () => tellHost({ share: 'toggle' }));
  if (ctx === 'pc') {
    window.addEventListener('message', onHostMessage);
    if (host) {
      /* Capture, on window: ahead of noVNC's own handler on the canvas. */
      window.addEventListener('keydown', onHostKey, true);
      window.addEventListener('keyup', onHostKey, true);
    } else {
      document.addEventListener('fullscreenchange',
        () => setFullscreenChrome(!!document.fullscreenElement));
    }
  }

  control($('zoom-in'), () => zoomBy(ZOOM_STEP));
  control($('zoom-out'), () => zoomBy(1 / ZOOM_STEP));
  control($('zoom-value'), () => setZoom(ui.zoom === 'fit' ? 1 : 'fit'));

  control($('sheet-close'), closeSheet);
  control(scrim, closeSheet);
  for (const m of MODES) control($('opt-' + m), () => { setMode(m); setTimeout(closeSheet, 180); });
  control($('scroll-natural'), () => setScroll('natural'));
  control($('scroll-reverse'), () => setScroll('reverse'));
  control($('arm-middle'), () => { api.armed = 1; toast(TEXT.toastMiddle); closeSheet(); });
  control($('arm-right'), () => { api.armed = 2; toast(TEXT.toastRight); closeSheet(); });

  const sens = $('sens');
  sens.addEventListener('pointerdown', (e) => e.stopPropagation());
  sens.addEventListener('input', () => {
    api.sens = parseInt(sens.value, 10) || 3;
    store.set('sens', api.sens);
  });

  bubbleDrag();

  /* No history entry and no popstate handler. The Activity never calls
     WebView.goBack(), so Android's back button finishes the window and never
     reaches this page — pushing a state would only leave a phantom entry
     behind. The sheet closes on its scrim, the panel on a tap at the picture. */

  window.addEventListener('resize', onViewport);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', onViewport);
    window.visualViewport.addEventListener('scroll', onViewport);
  }
  window.addEventListener('pagehide', () => {
    if (canvas && canvas.width) store.set(zoomKey(), ui.zoom);
    if (input) input.releaseAll();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden' && input) input.releaseAll();
  });

  /* Suppresses Android's selection callout over the picture. It does NOT
     suppress a real right-click: that arrives as mousedown with button 2 and
     still reaches noVNC as VNC button 3. */
  document.addEventListener('contextmenu', (e) => e.preventDefault());

  /* One real mouse event is enough to know a mouse is driving — including one
     plugged into the PHONE, which the URL's context cannot tell us about. Only
     a move to the phone's display ever takes it back off. */
  const sawMouse = (e) => {
    if (e.isTrusted && e.pointerType === 'mouse') setDesktopChrome(true);
  };
  window.addEventListener('pointerdown', sawMouse, { capture: true, passive: true });
  window.addEventListener('pointermove', sawMouse, { capture: true, passive: true });
}

function toggleZoomUI() {
  ui.zoomUI = !ui.zoomUI;
  strip.hidden = !ui.zoomUI;
  $('zoom').classList.toggle('on', ui.zoomUI);
  $('p-zoom').classList.toggle('on', ui.zoomUI);
  if (ui.zoomUI) { placeStrip(); paintZoomValue(); }
}

function setScroll(dir) {
  api.scroll = dir;
  store.set('scroll', dir);
  $('scroll-natural').setAttribute('aria-checked', String(dir === 'natural'));
  $('scroll-reverse').setAttribute('aria-checked', String(dir === 'reverse'));
}

/* The bubble is dragged with pointer events, not the touch router: it lives in
   #chrome, which the router never sees, and pointer capture is exactly right
   for a single-finger drag of one element. */
function bubbleDrag() {
  let id = null, sx = 0, sy = 0, ox = 0, oy = 0, moved = false;

  bubble.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    id = e.pointerId;
    sx = e.clientX; sy = e.clientY;
    ox = bubble.offsetLeft; oy = bubble.offsetTop;
    moved = false;
    bubble.setPointerCapture(id);
    bubble.classList.add('drag');
    bubble.style.willChange = 'left, top';
    clearTimeout(tuckTimer);
    bubble.classList.remove('tuck');
  });

  bubble.addEventListener('pointermove', (e) => {
    if (e.pointerId !== id) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (!moved && Math.hypot(dx, dy) < MOVE_SLOP) return;
    moved = true;
    const size = bubble.offsetWidth;
    bubble.style.left = clamp(ox + dx, 0, stage.clientWidth - size) + 'px';
    bubble.style.top = clamp(oy + dy, 0, stage.clientHeight - size) + 'px';
    if (ui.chrome === 'panel') placePanel();
  });

  const end = (e) => {
    if (e.pointerId !== id) return;
    id = null;
    bubble.classList.remove('drag');
    bubble.style.willChange = '';
    if (!moved) {
      if (ui.chrome === 'panel') closePanel(); else openPanel();
      return;
    }
    const size = bubble.offsetWidth;
    const cx = bubble.offsetLeft + size / 2;
    bubblePos = {
      edge: cx > stage.clientWidth / 2 ? 'right' : 'left',
      y: clamp(bubble.offsetTop / Math.max(1, stage.clientHeight), 0, 1),
    };
    store.set('bubble', JSON.stringify(bubblePos));
    placeBubble();
    if (ui.chrome === 'panel') placePanel();
    if (ui.zoomUI) placeStrip();
    armTuck();
  };
  bubble.addEventListener('pointerup', end);
  bubble.addEventListener('pointercancel', end);
}

/*
 * One formula for both platform behaviours. Under adjustResize innerHeight
 * shrinks with the IME and this returns 0 — correct, because bottom:0 is
 * already above the keyboard. Under pan innerHeight holds and offsetTop moves,
 * so it returns the pan distance: the keys row lifts back into view and the
 * bar is pushed back down by exactly as much as the window went up.
 */
function measureKb() {
  const vv = window.visualViewport;
  if (!vv) return { h: 0, top: 0 };
  return { h: Math.max(0, window.innerHeight - vv.height - vv.offsetTop), top: vv.offsetTop };
}

function onViewport() {
  const m = measureKb();
  const root = document.documentElement.style;
  root.setProperty('--kb-h', m.h + 'px');
  root.setProperty('--vv-top', m.top + 'px');
  bubblePos.y = clamp(bubblePos.y, 0, 1);
  if (ui.chrome !== 'docked') placeBubble();
  if (ui.chrome === 'panel') placePanel();
  if (ui.zoomUI) placeStrip();
  layout();
}

/* ── the object the input and keyboard modules are handed ───────────────── */

let input = null;
let keys = null;

const api = {
  ui,
  /* A computer's window: the desktop is sized to it, so the viewer has no
     zoom of its own and Ctrl+scroll belongs to the guest. */
  get pc() { return ctx === 'pc'; },
  get rfb() { return rfb; },
  get canvas() { return canvas; },
  stage, screenEl,
  fb, zNow, toFb, toClient, toClientRaw, clamp,
  panBy, setZoom, zoomBy,
  get panX() { return panX; },
  get panY() { return panY; },
  layout, closePanel, closeSheet, toast, coach, refocus,
  setKb: (on) => setKb(on),
  onSinkBlur: () => { if (ui.kb) setKb(false); },
  sens: parseInt(store.get('sens', '3'), 10) || 3,
  scroll: store.get('scroll', 'natural'),
  armed: 0,      // 0 none, 1 middle, 2 right — spent by the next tap
  ZOOM_MIN, ZOOM_MAX,
  TEXT,
};

/* ── boot ───────────────────────────────────────────────────────────────── */

/*
 * The tighter, mouse-driven chrome, and the sheet footnote that goes with it.
 *
 * Turned on by two independent things: this page being the DESKTOP's window at
 * all, which the URL says at boot, and a real mouse event arriving from
 * anywhere. The first is what the pointer sniffer alone could not do — injected
 * events on the scrcpy display may arrive with pointerType 'touch', and the
 * desktop window then wore phone-sized chrome until something proved otherwise
 * (it is the open question doc/linux-viewer.md lists, and this closes it).
 */
function setDesktopChrome(on) {
  if (document.body.classList.contains('is-desktop') === on) return;
  document.body.classList.toggle('is-desktop', on);
  $('sheet-foot').textContent = on ? TEXT.footDesktop : TEXT.footTouch;
  layout();
}

/*
 * The window moved between displays without this page being rebuilt.
 *
 * LinuxActivity calls it. Usually a display change destroys and recreates that
 * activity — a new WebView, a new URL, a new boot() — and this is never
 * reached; it exists for the case where the two displays agree on density and
 * the platform hands the activity a plain configuration change instead. Called
 * with the context it already has, it does nothing.
 *
 * The method it lands on is whatever THIS context last had — the user's own
 * choice if they made one here, its default if they did not — and applying that
 * default must not record it as a choice, hence the false.
 */
window.dexContext = function (next) {
  const to = next === 'phone' ? 'phone' : 'desktop';
  if (to === ctx) return;
  ctx = to;
  setDesktopChrome(ctx === 'desktop');
  setMode(savedMode(), false);
  /* The other window's rule for how big, whatever the box. */
  followBox = null;
  followWindow(true);
};

function boot() {
  ctx = readCtx();
  if (ctx === 'pc') {
    document.body.classList.add('is-pc');
    host = window.parent !== window ? window.parent : null;
  }
  if (ctx !== 'phone') setDesktopChrome(true);
  /* No touch methods on a computer: Direct is the only one that does not
     remap anything, and a touchscreen laptop's finger is a pointer there. */
  ui.mode = ctx === 'pc' ? 'direct' : savedMode();
  document.body.dataset.mode = ui.mode;
  setScroll(api.scroll === 'reverse' ? 'reverse' : 'natural');
  $('sens').value = String(api.sens);
  paintMode();

  /* A panel restores as a bubble: putting an opaque strip back over a desktop
     the user last saw clean, to save one tap, is not a trade worth making. */
  const chrome = store.get('chrome', 'docked');
  setChrome(chrome === 'bubble' ? 'bubble' : 'docked');
  setKeys(store.get('keys', '0') === '1', true);

  wire();
  onViewport();
  connect();
}

boot();
