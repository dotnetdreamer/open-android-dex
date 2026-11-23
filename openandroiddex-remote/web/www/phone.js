// The phone's own screen in a browser (Remote desktop): the page the phone's
// door serves at /phone.html, for a computer with nothing installed.
//
// The same stream the desktop app's window takes (PhoneStream.java on the
// phone; PhoneView.tsx in the app), through the door's /phone WebSocket with
// the token this browser was given for the code on the phone (the same code
// and token as Linux's page). Decoded with WebCodecs and drawn at the phone's
// shape in whatever size the window is. The left button is a finger, the
// right one Back and the middle one Home; a touch screen's finger is a finger;
// the wheel scrolls; keys go into the phone's focused field.
//
// Files (the bar's Files button): the phone's own storage, from the same door
// with the same token (/pfs/; phone-files.js, on Linux's Files' code). Loaded
// only where a door is behind this page: the page from anywhere has none.
import { avcC, codecString, sameBytes, splitAccessUnit } from './h264.js';
import { keyDown } from './keysym.js';
import { createSoundPlayer } from './sound.js';

const MSG_VIDEO = 0x01;
const MSG_AUDIO = 0x03;
const IN_POINTER = 0x10;
const IN_KEY = 0x11;
const IN_ACK = 0x12;
const IN_SCROLL = 0x13;
/** Ignored by the phone: a byte every 50 ms keeps a phone's Wi-Fi out of power save while this is in front. */
const KEEP_AWAKE = new Uint8Array([0x00]);
const MAC = /Mac|iPhone|iPad/.test(navigator.platform);
const PLAIN = location.protocol === 'http:';
// From anywhere (the page on the web, openandroiddex-remote/web): a socket over
// the phone's tunnel instead of the door's, and its own pairing before this.
const ANYWHERE = typeof window.PHONE_SOCKET === 'function';

const $ = (id) => document.getElementById(id);
const canvas = $('screen');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* private window */ } },
};
let token = store.get('lod.token');
let quality = ['low', 'medium', 'high'].includes(store.get('lod.phoneQuality')) ? store.get('lod.phoneQuality') : 'medium';
// Picture size: the part of the quality's picture the phone sends (half its
// width and height for Half size). A smaller one comes sooner when much of
// the screen changes (an app opening, a page turned): on a Galaxy S25 Ultra,
// half the size brought such a change in 116 ms instead of 150 over Wi-Fi,
// and 144 instead of 235 through the tunnel (doc/phone-remote.md). A small
// change, a button pressed, is as quick at every size.
const SIZES = [1, 0.75, 0.5, 0.333];
let scale = SIZES.includes(Number(store.get('lod.phoneSize'))) ? Number(store.get('lod.phoneSize')) : 1;
// Sound: where the phone's sound plays (PhoneSound.java). On the phone, as it
// always did, and off send nothing at all, so the whole link is the
// picture's; in this browser or on both, the phone sends what apps play.
// Off and in this browser keep the phone itself quiet.
const SOUNDS = ['phone', 'here', 'both', 'off'];
let sound = SOUNDS.includes(store.get('lod.phoneSound')) ? store.get('lod.phoneSound') : 'phone';
const soundHere = () => sound === 'here' || sound === 'both';
const soundOnPhone = () => sound === 'phone' || sound === 'both';
const player = createSoundPlayer({ onState: () => syncSound() });
/** What the phone says of the sound: on, or why not; and whether it keeps itself quiet. */
let soundState = { state: 'off', quiet: false };
let soundKbps = 0;
/** What the phone was last told ("on/phone"), so a change is sent once. */
let soundAsked = '';
// Screen off: the phone's own screen dark while the phone runs on for this page, as
// scrcpy's screen off does, so the phone's battery lasts longer (PhonePanel.java). The
// phone needs its helper for that, which the phone starts itself once set up on it
// (PhoneHelperStart: through its own Wireless debugging), or a computer with adb
// starts; without one the button says what the phone needs. This browser's choice is kept, so a link back after a drop, or the
// next visit, darkens the phone again; the phone's power button takes the choice back.
let panelOff = store.get('lod.phonePanel') === 'off';
/**
 * What the phone says of its screen: "none" (no helper), "on" or "off"; the
 * helper's start command for a computer with adb; and with no helper, what
 * stands between the phone and one it starts itself ("how": setup, waiting,
 * starting, devoff, wireless, wifi, ask, failed, unpaired, pc), why a start
 * failed, and the app's name on the phone.
 */
let panel = { state: 'none', start: '', how: '', why: '', app: '' };
/** The helper states in which the phone starts its helper itself when asked. */
const HELPER_STARTS = ['waiting', 'failed', 'ask', 'starting'];
let panelAskedAt = -1e9;

// ── the cover ──
function cover(title, words, what) {
  $('cover').hidden = false;
  $('cover-title').textContent = title;
  $('cover-words').textContent = words;
  $('pair').hidden = what !== 'pair';
  $('ask').hidden = what !== 'ask';
  if (what === 'pair') setTimeout(() => $('code').focus(), 50);
}
const uncover = () => { $('cover').hidden = true; };

$('pair').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('pair-error').textContent = '';
  let r, j = {};
  try {
    r = await fetch('pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ code: $('code').value.trim() }),
    });
    j = await r.json().catch(() => ({}));
  } catch {
    $('pair-error').textContent = 'The phone did not answer. Is the phone on the same Wi-Fi?';
    return;
  }
  if (r.ok && j.token) {
    token = j.token;
    store.set('lod.token', token);
    $('code').value = '';
    open();
    void checkFiles();
    return;
  }
  $('pair-error').textContent = r.status === 429
    ? 'Too many tries. Wait a little, then try again.'
    : 'That code did not work. Check the code on the phone, in LinuxOnDroid → Settings.';
});

// ── the stream ──
let ws = null;
let live = false;
let up = false;
let told = false;
let retry = 0;
let dw = 0, dh = 0, cw = 0, ch = 0;
let decoder = null, description = null, sps = null, pps = null;
let needKey = true, askedKeyAt = 0;
let source = '', stats = { fps: 0, mbps: 0, rttMs: 0 }, phone = { control: true, locked: false };

const send = (m) => { if (ws && ws.readyState === WebSocket.OPEN) ws.send(m); };
/** The sound's two switches to the phone, when they change: heard here (only while this page can play it), and on the phone. */
function syncSound() {
  const on = soundHere() && player.playing();
  const said = `${on}/${soundOnPhone()}`;
  if (said === soundAsked || !ws || ws.readyState !== WebSocket.OPEN) return;
  soundAsked = said;
  send(JSON.stringify({ t: 'audio', on, phone: soundOnPhone(), codecs: player.codecs() }));
  showStatus();
}
/** In a click or a key: the moment a browser lets a page start sound. */
function startSound() {
  if (soundHere() && !player.playing()) player.start();
}
const askKey = () => {
  const now = performance.now();
  if (now - askedKeyAt < 500) return;
  askedKeyAt = now;
  send(JSON.stringify({ t: 'keyframe' }));
};
const ack = () => {
  const b = new Uint8Array(9);
  const v = new DataView(b.buffer);
  v.setUint8(0, IN_ACK);
  v.setFloat64(1, performance.timeOrigin + performance.now());
  send(b);
};

function draw(frame) {
  if (cw > 0) {
    if (canvas.width !== cw || canvas.height !== ch) {
      canvas.width = cw;
      canvas.height = ch;
    }
    ctx.drawImage(frame, 0, 0, cw, ch, 0, 0, cw, ch);
    if (!up) {
      up = true;
      retry = 0;
      uncover();
      canvas.focus({ preventScroll: true });
      // Allowed now if this page was clicked or typed in before (the code);
      // a page brought back by a reload waits for its first click.
      startSound();
    }
  }
  frame.close();
}

function newDecoder() {
  if (decoder && decoder.state !== 'closed') decoder.close();
  decoder = new VideoDecoder({
    output: draw,
    error: () => { description = null; needKey = true; askKey(); },
  });
}

function onVideo(flags, pts, payload) {
  const unit = splitAccessUnit(payload);
  if (unit.sps) sps = unit.sps;
  if (unit.pps) pps = unit.pps;
  if (!unit.data.length) return;
  const isKey = unit.key || (flags & 1) !== 0;
  if (isKey && sps && pps) {
    const d = avcC(sps, pps);
    if (!decoder || decoder.state === 'closed' || !sameBytes(d, description)) {
      if (!decoder || decoder.state === 'closed') newDecoder();
      decoder.configure({ codec: codecString(sps), description: d, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' });
      description = d;
    }
    needKey = false;
  }
  if (needKey || !decoder || decoder.state !== 'configured') { askKey(); return; }
  if (decoder.decodeQueueSize > 3 && !isKey) { needKey = true; askKey(); return; }
  decoder.decode(new EncodedVideoChunk({ type: isKey ? 'key' : 'delta', timestamp: pts, data: unit.data }));
}

function showStatus() {
  const how = source === 'shots' ? 'Slow view' : source === 'screen' ? 'Full speed' : '';
  // From anywhere, the way the picture comes: "direct" or "through a relay" (anywhere.js).
  const route = ANYWHERE && typeof window.PHONE_ROUTE === 'string' ? ` · ${window.PHONE_ROUTE}` : '';
  const picture = cw && ch ? ` · ${cw} × ${ch}` : '';
  $('status').textContent = !how ? '' : `${how}${picture} · ${stats.fps} fps · ${stats.mbps} Mb/s · ${stats.rttMs} ms${route}`
    + (phone.locked ? ' · locked' : '') + (!phone.control ? ' · watching only: control is off on the phone' : '')
    + (panel.state === 'off' ? ' · the phone’s screen is off' : panelOff && panel.state === 'on' ? ' · turning the phone’s screen off' : '')
    + soundWords();
  showPanel();
  $('share').hidden = source !== 'shots';
  for (const q of ['low', 'medium', 'high']) $(`q-${q}`).classList.toggle('on', q === quality);
  if ($('size').value !== String(scale)) $('size').value = String(scale);
  if ($('sound').value !== sound) $('sound').value = sound;
}

/** The sound's part of the status line: what is playing here, or why nothing is. */
function soundWords() {
  if (!soundHere()) return soundState.quiet ? ' · the phone is quiet' : '';
  switch (soundState.state) {
    case 'on': return player.playing() ? ` · sound ${soundKbps} kb/s` : ' · click the screen to hear the phone';
    case 'permission': return ' · sound: allow it on the phone first';
    case 'sharing': return ' · sound comes with full speed';
    case 'android': return ' · sound needs Android 10 or newer on the phone';
    case 'failed': return ' · the phone’s sound stopped: choose the sound again to retry';
    default: return player.playing() ? '' : ' · click the screen to hear the phone';
  }
}

function open() {
  if (!('VideoDecoder' in window)) {
    cover('Phone screen', PLAIN
      ? 'This browser shows the picture only on the secure address. Open the address that starts with https.'
      : 'This browser cannot decode the picture. Use a recent Chrome, Edge, Safari or Firefox.', '');
    return;
  }
  if (!ANYWHERE && !token) {
    cover('Phone screen', 'Enter the code shown on the phone, in LinuxOnDroid → Settings → Remote desktop.', 'pair');
    return;
  }
  cover('Phone screen', 'Opening the phone’s screen…', '');
  up = false;
  told = false;
  needKey = true;
  description = null;
  ws = ANYWHERE ? window.PHONE_SOCKET() : new WebSocket(`${PLAIN ? 'ws' : 'wss'}://${location.host}/phone?t=${encodeURIComponent(token)}`);
  ws.binaryType = 'arraybuffer';
  live = true;
  ws.onopen = () => {
    const on = soundHere() && player.playing();
    soundAsked = `${on}/${soundOnPhone()}`;
    soundState = { state: 'off', quiet: false };
    send(JSON.stringify({
      t: 'hello', v: 1, w: innerWidth, h: innerHeight, bitrate: 30e6, fps: 60,
      acks: true, ackAt: true, quality, scale, wake: true, via: 'browser',
      audio: on, phone: soundOnPhone(), codecs: player.codecs(), panel: !panelOff,
    }));
    // Back while Files are up (the link dropped and came back): still no picture.
    if (filesOpen()) send(JSON.stringify({ t: 'picture', on: false }));
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data !== 'string') {
      const b = new Uint8Array(ev.data);
      if (b[0] === MSG_AUDIO && b.length > 10) {
        player.run(b[1], Number(new DataView(ev.data, 2, 8).getBigUint64(0)), b.subarray(10));
        return;
      }
      if (b[0] !== MSG_VIDEO || b.length < 10) return;
      const flags = b[1];
      const pts = Number(new DataView(ev.data, 2, 8).getBigUint64(0));
      onVideo(flags, pts, b.subarray(10));
      if (!(flags & 2)) ack();
      return;
    }
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.t === 'video') {
      dw = m.dw | 0; dh = m.dh | 0; cw = m.cw | 0; ch = m.ch | 0;
      source = m.source === 'shots' ? 'shots' : 'screen';
      if (['low', 'medium', 'high'].includes(m.quality)) quality = m.quality;
      if (SIZES.includes(m.scale)) scale = m.scale;
      needKey = true;
      showStatus();
    } else if (m.t === 'phone') {
      phone = { control: !!m.control, locked: !!m.locked };
      if (typeof m.panel === 'string') {
        const was = panel.state;
        panel = {
          state: m.panel,
          start: typeof m.panelStart === 'string' ? m.panelStart : '',
          how: typeof m.panelHow === 'string' ? m.panelHow : '',
          why: typeof m.panelWhy === 'string' ? m.panelWhy : '',
          app: typeof m.app === 'string' ? m.app : '',
        };
        // The phone's power button took its screen back: this browser's choice goes with it.
        if (panelOff && m.panelWanted === false && m.panel !== 'none' && performance.now() - panelAskedAt > 3000) setPanel(false, false);
        // The helper came while the note says how to start it: the note has done its work.
        if (was === 'none' && m.panel !== 'none') $('panel-note').hidden = true;
        // Still none: the note follows the phone (starting, then failed, …).
        else if (m.panel === 'none' && !$('panel-note').hidden) showHelperNote();
      }
      showStatus();
    } else if (m.t === 'stats') {
      stats = { fps: m.fps | 0, mbps: Math.round((m.kbps || 0) / 100) / 10, rttMs: Math.round(m.rttMs || 0) };
      soundKbps = m.soundKbps | 0;
      showStatus();
    } else if (m.t === 'sound') {
      player.format(m);
    } else if (m.t === 'audio') {
      soundState = { state: String(m.state || 'off'), quiet: !!m.quiet };
      showStatus();
    } else if (m.t === 'protected') {
      showProtected(String(m.what || ''));
    } else if (m.t === 'lock') {
      lock = m;
      drawLock();
    } else if (m.t === 'state') {
      if (m.state === 'running') return;
      if (m.state === 'waiting') { cover('Phone screen', m.message || 'Waiting for the phone.', 'ask'); return; }
      told = true;
      if (m.state === 'unauthorized') {
        token = null;
        store.set('lod.token', null);
        filesGone();
        cover('Phone screen', 'This browser needs the code again: it was signed out on the phone.', 'pair');
        return;
      }
      cover('Phone screen', m.message || (m.state === 'replaced'
        ? 'Another computer opened this phone.' : 'The phone closed the screen.'), m.state === 'replaced' ? 'ask' : '');
    }
  };
  ws.onclose = () => {
    if (decoder && decoder.state !== 'closed') decoder.close();
    decoder = null;
    lock = { pad: '' };
    drawLock();
    if (!live || told) return;
    // The link went, not the phone: back by itself. Every 2 s for the first
    // minute, since an app Android has stopped (a Xiaomi phone's Clear all)
    // is back by itself 2 to 20 s later, and a wait that kept doubling came
    // back up to 8 s after it; then every 15 s.
    up = false;
    cover('Phone screen', 'The connection to the phone dropped. Trying again…', '');
    setTimeout(open, retry < 30 ? Math.min(2000, 500 * 2 ** retry) : 15000);
    retry++;
  };
}

// ── the phone's keys and the picture's quality ──
const nav = (k) => send(JSON.stringify({ t: 'key', key: k }));
$('k-back').onclick = () => nav('back');
$('k-home').onclick = () => nav('home');
$('k-recents').onclick = () => nav('recents');
$('k-notifications').onclick = () => nav('notifications');
for (const q of ['low', 'medium', 'high']) {
  $(`q-${q}`).onclick = () => {
    quality = q;
    store.set('lod.phoneQuality', q);
    send(JSON.stringify({ t: 'quality', mode: q }));
    showStatus();
  };
}
$('size').value = String(scale);
$('size').onchange = () => {
  scale = Number($('size').value);
  store.set('lod.phoneSize', String(scale));
  send(JSON.stringify({ t: 'size', scale }));
  $('screen').focus();
};
$('sound').value = sound;
$('sound').onchange = () => {
  sound = SOUNDS.includes($('sound').value) ? $('sound').value : 'phone';
  store.set('lod.phoneSound', sound);
  // A choice is a click: the moment the browser lets the sound start.
  if (soundHere()) player.start();
  else player.stop();
  syncSound();
  showStatus();
  $('screen').focus();
};
$('share').onclick = () => send(JSON.stringify({ t: 'share' }));
$('ask').onclick = () => { if (ws && ws.readyState === WebSocket.OPEN) send(JSON.stringify({ t: 'share' })); else open(); };

// ── screen off: the phone's own screen dark, the phone running on ──
/** This browser's choice, kept for the next time; told to the phone unless the phone said it first. */
function setPanel(off, tell = true) {
  panelOff = off;
  store.set('lod.phonePanel', off ? 'off' : null);
  if (tell) {
    panelAskedAt = performance.now();
    send(JSON.stringify({ t: 'panel', on: !off }));
  }
  showStatus();
}
/** The button as the phone has its screen: off, on its way off, on, or no helper yet. */
function showPanel() {
  const b = $('panel-btn');
  const dark = panel.state === 'off';
  // Asked for and on its way: the phone's screen still on, or its helper still starting.
  const waiting = panelOff && (panel.state === 'on' || (panel.state === 'none' && HELPER_STARTS.includes(panel.how)));
  b.classList.toggle('on', dark);
  b.classList.toggle('pending', waiting);
  b.classList.toggle('unavailable', panel.state === 'none' && !HELPER_STARTS.includes(panel.how));
  b.setAttribute('aria-pressed', String(dark || waiting));
  b.title = panel.state === 'none'
    ? (HELPER_STARTS.includes(panel.how) ? 'Turn the phone’s own screen off: the phone starts its helper first'
      : 'Turning off the phone’s screen needs a helper on the phone: click to see how')
    : dark ? 'The phone’s own screen is off, and the phone keeps working here. Click to turn the phone’s screen back on'
      : 'Turn the phone’s own screen off: the phone keeps working here, and the phone’s battery lasts longer. The phone’s power button turns the screen back on';
}
/**
 * Where the phone stands on its helper, in words: what the phone needs to
 * start it itself (PhoneHelperStart), and whether a computer's command helps.
 */
function helperWords() {
  const app = panel.app || 'the app';
  const computer = 'A computer with adb (Android’s USB debugging) can start the helper instead: run this on that computer, with the phone connected, once after every restart of the phone.';
  switch (panel.how) {
    case 'starting':
    case 'waiting':
      return { words: 'The phone is starting its helper…', cmd: false };
    case 'setup':
      return { words: `Two things need a small helper on the phone: turning off the phone’s screen, and pressing the buttons Android keeps for someone at the phone, such as permission questions. Set the helper up once on the phone: open ${app} there, then Settings, Remote desktop, Set up Screen off. After that the phone starts the helper by itself. ${computer}`, cmd: true };
    case 'unpaired':
      return { words: `The phone’s Wireless debugging no longer knows ${app}. Set up Screen off again on the phone: ${app}, Settings, Remote desktop. ${computer}`, cmd: true };
    case 'devoff':
      return { words: `Developer options are off on the phone, so the phone can’t start its helper. Turn Developer options back on in the phone’s Settings. ${computer}`, cmd: true };
    case 'wireless':
      return { words: `Wireless debugging is off on the phone, so the phone can’t start its helper. Turn on Wireless debugging in the phone’s Developer options. ${computer}`, cmd: true };
    case 'wifi':
      return { words: `The phone needs Wi-Fi to start its helper by itself, because Wireless debugging works only on Wi-Fi. ${computer}`, cmd: true };
    case 'ask':
      return { words: 'The phone asks whether to allow Wireless debugging on its Wi-Fi. Someone at the phone needs to tap Allow there, then press Screen off here again.', cmd: false };
    case 'failed':
      return { words: `The phone’s helper did not start${panel.why ? ` (${panel.why})` : ''}. Press Screen off to try again. ${computer}`, cmd: true };
    default:
      // Android 10 (no Wireless debugging), or a phone app that does not say.
      return { words: 'Two things need a small helper on the phone: turning off the phone’s screen, and pressing the buttons Android keeps for someone at the phone, such as permission questions. A computer with adb (Android’s USB debugging) starts the helper: run this on that computer, with the phone connected, once after every restart of the phone.', cmd: true };
  }
}
/** How to start the phone's helper, which both Screen off and Android's protected buttons need. */
function showHelperNote() {
  const h = helperWords();
  $('panel-words').textContent = h.words;
  $('panel-cmd').parentElement.style.display = h.cmd ? '' : 'none';
  $('panel-cmd').textContent = panel.start || 'The phone has not said the command yet. Wait a moment, then try again.';
  $('panel-then').textContent = 'Then Screen off turns the phone’s screen off while the phone keeps working here, and the phone’s battery lasts longer. The phone’s power button, closing this page, or Screen off again turns the screen back on.';
  $('protect-note').hidden = true;
  $('panel-note').hidden = false;
}
$('panel-btn').onclick = () => {
  if (panel.state === 'none') {
    // A phone set up for it starts its helper now (PhoneHelperStart), and its
    // screen goes off once the helper is up; the note says how that goes.
    if (HELPER_STARTS.includes(panel.how)) setPanel(true);
    showHelperNote();
    return;
  }
  setPanel(!(panelOff || panel.state === 'off'));
  $('screen').focus();
};
$('protect-how').onclick = () => showHelperNote();
let protectTimer = 0;
/** A press on a screen Android keeps from the phone's accessibility service, with no helper on the phone. */
function showProtected(what) {
  const screen = what === 'permission' ? 'a permission question' : what === 'install' ? 'an install' : 'this question from Android';
  $('protect-words').textContent = `Android lets only someone at the phone press the buttons on ${screen}. The phone’s helper lets you press them from here.`;
  $('protect-note').hidden = false;
  clearTimeout(protectTimer);
  protectTimer = setTimeout(() => { $('protect-note').hidden = true; }, 12000);
}
$('panel-copy').onclick = async () => {
  const t = panel.start;
  if (!t) return;
  try {
    await navigator.clipboard.writeText(t);
    $('panel-copy').textContent = 'Copied';
  } catch {
    // No clipboard here (the address without encryption): the command selected, for Ctrl+C.
    const r = document.createRange();
    r.selectNodeContents($('panel-cmd'));
    const sel = getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
    $('panel-copy').textContent = 'Selected';
  }
  setTimeout(() => { $('panel-copy').textContent = 'Copy'; }, 2000);
};
$('panel-close').onclick = () => {
  $('panel-note').hidden = true;
  $('screen').focus();
};

// ── the lock screen's pad, which screen sharing shows black ──
// While the phone's PIN pad is up, the phone says where its keys are and
// what it says (PhoneLock.java), and they are drawn here over the picture,
// where Android leaves it black. Nothing here takes a click: a click is a tap
// on the real key below, and the number keys, Backspace and Enter press the
// keys too. Drawn only while the picture there is black, so a phone that
// does show its pad is not drawn over.
let lock = { pad: '' };
/** The picture where the pad's keys are is black: the pad is hidden from screen sharing. */
function padHidden() {
  const keys = lock.keys || [];
  if (!keys.length || !cw || !ch || !lock.w || !lock.h) return true;
  try {
    let dark = 0, n = 0;
    for (const k of keys) {
      const x = Math.round((k.x + k.w / 2) * cw / lock.w);
      const y = Math.round((k.y + k.h / 2) * ch / lock.h);
      if (x < 0 || y < 0 || x >= cw || y >= ch) continue;
      const d = ctx.getImageData(x, y, 1, 1).data;
      n++;
      if (d[0] + d[1] + d[2] < 24) dark++;
    }
    return n === 0 || dark / n > 0.8;
  } catch {
    return true;
  }
}
function drawLock() {
  const box = $('lockpad');
  const note = $('lock-note');
  const on = !!lock.pad && up && !!cw && !!lock.w && padHidden();
  box.hidden = !on;
  note.hidden = !on;
  if (!on) {
    box.replaceChildren();
    return;
  }
  // The pad's frame: where the picture is drawn in the stage.
  const r = canvas.getBoundingClientRect();
  const stage = $('stage').getBoundingClientRect();
  const s = Math.min(r.width / cw, r.height / ch);
  const w = cw * s, h = ch * s;
  box.style.left = `${r.left - stage.left + (r.width - w) / 2}px`;
  box.style.top = `${r.top - stage.top + (r.height - h) / 2}px`;
  box.style.width = `${w}px`;
  box.style.height = `${h}px`;
  const k = w / lock.w; // the phone's pixels to the page's
  const put = (cls, text, at, size) => {
    const d = document.createElement('div');
    d.className = cls;
    d.textContent = text;
    d.style.left = `${at.x * k}px`;
    d.style.top = `${at.y * k}px`;
    d.style.width = `${at.w * k}px`;
    d.style.height = `${at.h * k}px`;
    d.style.fontSize = `${Math.max(9, size * k)}px`;
    return d;
  };
  const parts = [];
  for (const wd of lock.words || []) parts.push(put('lp-words', wd.s, wd, Math.min(wd.h * 0.6, 34)));
  if (lock.field) parts.push(put('lp-field', '●'.repeat(Math.min(lock.field.n | 0, 24)), lock.field, lock.field.h * 0.4));
  for (const key of lock.keys || []) {
    const label = key.k === 'del' ? '⌫' : key.k === 'ok' ? 'OK' : key.k;
    parts.push(put(`lp-key${key.k === 'ok' && !key.on ? ' off' : ''}`, label, key, key.h * (key.k.length > 1 ? 0.3 : 0.4)));
  }
  if (lock.pattern) {
    // The pattern's nine dots, three by three across its square.
    const p = lock.pattern;
    for (let i = 0; i < 9; i++) {
      const cx = p.x + p.w * (1 + 2 * (i % 3)) / 6, cy = p.y + p.h * (1 + 2 * Math.floor(i / 3)) / 6;
      const r0 = Math.min(p.w, p.h) * 0.04;
      parts.push(put('lp-dot', '', { x: cx - r0, y: cy - r0, w: r0 * 2, h: r0 * 2 }, 0));
    }
  }
  box.replaceChildren(...parts);
  note.textContent = lock.pad === 'pin'
    ? 'The phone hides its PIN pad from screen sharing, so it is drawn here. Type the PIN on your keyboard, or click the keys.'
    : lock.pad === 'password'
      ? 'The phone hides its password box from screen sharing. Type the password on your keyboard, then press Enter.'
      : 'The phone hides its pattern from screen sharing. Draw the pattern over the dots.';
}
// The picture under the pad turns black a frame or two after the pad is up, and the window changes size.
setInterval(() => { if (lock.pad) drawLock(); }, 500);
addEventListener('resize', () => { if (lock.pad) drawLock(); });

// ── the mouse, the pen and the finger ──
let buttons = 0;
function toScreen(e) {
  if (!cw || !ch || !dw || !dh) return null;
  const r = canvas.getBoundingClientRect();
  const s = Math.min(r.width / cw, r.height / ch);
  const ox = r.left + (r.width - cw * s) / 2, oy = r.top + (r.height - ch * s) / 2;
  const px = Math.min(cw - 1, Math.max(0, (e.clientX - ox) / s));
  const py = Math.min(ch - 1, Math.max(0, (e.clientY - oy) / s));
  return [Math.round(px * dw / cw), Math.round(py * dh / ch)];
}
function pointer(mask, at) {
  send(new Uint8Array([IN_POINTER, mask & 0xff, at[0] >> 8, at[0] & 0xff, at[1] >> 8, at[1] & 0xff]));
}
const maskOf = (e) => e.pointerType === 'mouse'
  ? (e.buttons & 1) | (e.buttons & 4 ? 2 : 0) | (e.buttons & 2 ? 4 : 0)
  : (e.buttons ? 1 : 0);
function onPointer(e) {
  if (!up) return;
  const at = toScreen(e);
  if (!at) return;
  e.preventDefault();
  if (e.type === 'pointerdown') {
    canvas.setPointerCapture(e.pointerId);
    startSound();
  }
  const mask = e.type === 'pointerup' || e.type === 'pointercancel' ? 0 : maskOf(e);
  if (e.type === 'pointermove' && !mask && !buttons) return; // hover: a phone has none
  buttons = mask;
  pointer(mask, at);
}
for (const t of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel']) canvas.addEventListener(t, onPointer);
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
// The wheel and a touchpad: the phone's page scrolls as far as a page here
// would, on this screen. The distance goes in the phone's pixels, one message
// for each wheel event (PhoneInput.scroll), and what is left of a pixel waits
// for the next; a touchpad's momentum comes as wheel events too, so the phone
// adds none of its own.
let wheelX = 0, wheelY = 0;
canvas.addEventListener('wheel', (e) => {
  if (!up) return;
  // A pinch on a touchpad, or Ctrl and the wheel: this page's own zoom. The phone gets one finger, which cannot pinch.
  if (e.ctrlKey) return;
  e.preventDefault();
  const at = toScreen(e);
  if (!at) return;
  const r = canvas.getBoundingClientRect();
  const s = Math.min(r.width / cw, r.height / ch);
  // Lines and pages as a page here takes them; a page is the picture's height.
  const unit = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? ch * s : 1;
  let dx = e.deltaX * unit, dy = e.deltaY * unit;
  // Shift turns a mouse's wheel sideways, where the browser has not already.
  if (e.shiftKey && !dx) { dx = dy; dy = 0; }
  const k = dw / (cw * s); // the phone's pixels in one of this page's
  wheelX += dx * k;
  wheelY += dy * k;
  const sx = Math.max(-32767, Math.min(32767, Math.trunc(wheelX)));
  const sy = Math.max(-32767, Math.min(32767, Math.trunc(wheelY)));
  if (!sx && !sy) return;
  wheelX -= sx;
  wheelY -= sy;
  send(new Uint8Array([IN_SCROLL, at[0] >> 8, at[0] & 0xff, at[1] >> 8, at[1] & 0xff, (sx >> 8) & 0xff, sx & 0xff, (sy >> 8) & 0xff, sy & 0xff]));
}, { passive: false });

// ── the keyboard ──
const held = new Map();
const key = (keysym, down) => send(new Uint8Array([IN_KEY, down ? 1 : 0,
  (keysym >>> 24) & 0xff, (keysym >>> 16) & 0xff, (keysym >>> 8) & 0xff, keysym & 0xff]));
addEventListener('keydown', (e) => {
  // Files up: the keys are Files' (its search, a name being typed), never the phone's;
  // the screen-off note up: its command may be copied with the keyboard.
  if (!up || filesOpen() || !$('panel-note').hidden || e.isComposing || e.keyCode === 229 || document.activeElement === $('code')) return;
  // Paste: the paste event below brings this computer's clipboard.
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyV') return;
  e.preventDefault();
  startSound();
  const a = keyDown(e, MAC);
  if (a.kind === 'none') return;
  if (a.kind === 'type') { key(a.keysym, true); key(a.keysym, false); return; }
  held.set(e.code, a.keysym);
  key(a.keysym, true);
});
addEventListener('keyup', (e) => {
  const k = held.get(e.code);
  if (k === undefined) return;
  held.delete(e.code);
  e.preventDefault();
  key(k, false);
});
addEventListener('paste', (e) => {
  if (!up || filesOpen()) return;
  const text = e.clipboardData && e.clipboardData.getData('text/plain');
  if (text) { e.preventDefault(); send(JSON.stringify({ t: 'text', text })); }
});
addEventListener('blur', () => { for (const k of held.values()) key(k, false); held.clear(); });

// ── out of sight: no picture, and the phone's Wi-Fi may sleep ──
document.addEventListener('visibilitychange', () => {
  // Files up: still no picture, the tab seen or not.
  send(JSON.stringify({ t: 'picture', on: !document.hidden && !filesOpen() }));
  if (!document.hidden && !filesOpen()) needKey = true;
  // The sound goes on while the tab is hidden; back in sight, it comes back to the picture's time.
  if (!document.hidden) player.settle();
});

// ── leaving: asked first, then this browser's session ends on the phone ──
// Closing the tab, or going elsewhere in it, while the phone is on screen is
// asked first, as Linux's page asks: the browser words the question. Gone,
// the phone is told at once ({"t":"bye"}): its session for this browser ends
// there and then, with its sound, its quiet speaker and its screen kept on,
// and Remote desktop stays on for the next one.
addEventListener('beforeunload', (e) => {
  if (!up) return;
  e.preventDefault();
  e.returnValue = '';
});
addEventListener('pagehide', () => {
  live = false;
  if (ws && ws.readyState === WebSocket.OPEN) {
    send(JSON.stringify({ t: 'bye' }));
    ws.close(1000);
  }
  player.stop();
});
// Brought back from the browser's page cache (Back after going elsewhere): the link again.
addEventListener('pageshow', (e) => {
  if (e.persisted && !live) open();
});
// On while Files are up too: a phone's Wi-Fi in power save slows a transfer as it slows the picture.
setInterval(() => { if (!document.hidden) send(KEEP_AWAKE); }, 50);
// Hidden too, every two seconds: how the phone knows this page is still here. It lets go of a
// computer it has heard nothing from for 90 s (a tab that vanished without a word: a crash, a
// lid shut, the network gone), so its sound, its quiet speaker and its screen kept on go too.
setInterval(() => { if (document.hidden) send(KEEP_AWAKE); }, 2000);

// ── Files: the phone's own storage (phone-files.js) ──
const filesBtn = $('files-btn');
/** Files, once loaded (createFiles' answer); null before, and always on the page from anywhere. */
let files = null;
let filesLoading = null;
let phoneName = '';
const filesOpen = () => !!files && files.isOpen();
/** The address asks for Files (#files, or #files=/Download): a bookmark, or a reload in them. */
const wantsFiles = () => location.hash === '#files' || location.hash.startsWith('#files=');
function hashPath() {
  if (!location.hash.startsWith('#files=')) return null;
  try {
    const p = decodeURI(location.hash.slice('#files='.length));
    return p.startsWith('/') ? p : null;
  } catch {
    return null;
  }
}

/**
 * Whether the phone has its files for this browser, asked once a token is in
 * hand: the button shows unless this build of the app has none to give
 * ("unavailable": Google Play's, without All files access) or Remote desktop
 * is off. No All files access yet ("noaccess"): the button shows, and Files
 * says what to do on the phone. The page from anywhere has no door, so none.
 */
async function checkFiles() {
  if (ANYWHERE || !filesBtn || !token) return;
  let state = 'ready';
  try {
    const auth = { Authorization: `Bearer ${token}` };
    const [r, hello] = await Promise.all([
      fetch('pfs/info', { headers: auth, cache: 'no-store' }),
      fetch('hello', { headers: auth, cache: 'no-store' }).then((h) => h.json()).catch(() => ({})),
    ]);
    if (typeof hello.name === 'string') phoneName = hello.name;
    if (!r.ok) state = r.status === 401 ? 'unauthorized' : ((await r.json().catch(() => ({}))).error || String(r.status));
  } catch {
    state = 'offline';
  }
  if (!token || ['unavailable', 'off', 'unauthorized'].includes(state)) {
    filesBtn.hidden = true;
    return;
  }
  try {
    await loadFiles();
  } catch {
    return;
  }
  // Signed out while this was asked: the button stays hidden.
  if (!token) return;
  filesBtn.hidden = false;
  if (wantsFiles() && !filesOpen()) void openFiles(hashPath());
}

function loadFiles() {
  if (!filesLoading) {
    filesLoading = import('./phone-files.js').then((m) => {
      files = m.createPhoneFiles({
        token: () => token,
        phoneName: () => phoneName,
        // Signed out on the phone, seen by a request of the Files'.
        unauthorized: () => signedOutInFiles(),
        toDesktop: () => closeFiles(),
        toFiles: (path, select) => openFiles(path, select),
      });
      return files;
    }, (e) => {
      filesLoading = null;
      throw e;
    });
  }
  return filesLoading;
}

/** Files, with the picture let go of: the phone sends none, and every byte of the link is the files'. */
async function openFiles(path = null, select = null) {
  if (!token || ANYWHERE) return;
  await loadFiles();
  for (const k of held.values()) key(k, false);
  held.clear();
  document.body.classList.add('files-on');
  send(JSON.stringify({ t: 'picture', on: false }));
  if (!wantsFiles()) {
    try {
      history.replaceState(null, '', '#files');
    } catch {
      // the address stays: a reload opens the screen
    }
  }
  document.title = phoneName ? `Files · ${phoneName}` : 'Files · Phone';
  void files.show(path, select);
}

/** Back to the phone's screen: the picture asked for again, from a key frame. */
function closeFiles() {
  if (!files || !files.isOpen()) return;
  files.hide();
  document.body.classList.remove('files-on');
  if (wantsFiles()) {
    try {
      history.replaceState(null, '', location.pathname + location.search);
    } catch {
      // kept: harmless
    }
  }
  document.title = 'Phone screen';
  if (!document.hidden) {
    send(JSON.stringify({ t: 'picture', on: true }));
    needKey = true;
    askKey();
  }
  canvas.focus({ preventScroll: true });
}

/** This browser signed out (the screen's word, or a Files request's): Files close, and the button goes. */
function filesGone() {
  closeFiles();
  if (filesBtn) filesBtn.hidden = true;
}

function signedOutInFiles() {
  token = null;
  store.set('lod.token', null);
  filesGone();
  told = true;
  if (ws) ws.close();
  cover('Phone screen', 'This browser needs the code again: it was signed out on the phone.', 'pair');
}

if (filesBtn) {
  filesBtn.hidden = true;
  filesBtn.addEventListener('click', () => void openFiles());
}

showStatus();
// The hello says which sound this page can decode: known first.
player.init().then(open, open);
void checkFiles();
/** For the tests' eyes: the sound and the lock screen as this page has them. */
window.lodPhone = () => ({ sound, soundState, playing: player.playing(), player: player.stats(), lock: lock.pad, lockShown: !$('lockpad').hidden,
  panel: panel.state, panelOff, panelStart: panel.start, panelHow: panel.how, panelWhy: panel.why,
  panelNote: !$('panel-note').hidden, panelWords: $('panel-words').textContent,
  protectedNote: $('protect-note').hidden ? '' : $('protect-words').textContent });
