/*
 * The browser half of the Web viewer.
 *
 * One transport: WebRTC. The video is a track, the clicks and typing are a data
 * channel, the files are another. There is no fallback, and that is the point —
 * an earlier version carried H.264 down a WebSocket with its own muxer and a
 * ladder of decoders, and all of it existed to work around the phone being hard
 * to reach. WebRTC is the thing that solves that, so everything else went.
 *
 * There are two ways this page gets loaded, and they are genuinely different
 * sessions rather than two skins on one:
 *
 *   LOCAL       The phone served this page, so it is reachable and its own
 *               socket is the rendezvous. The access code buys a session token
 *               over HTTP, and that token is what the data channel replays —
 *               nobody types the code twice for one session.
 *
 *   RENDEZVOUS  A relay served this page because nothing can reach the phone —
 *               it is on mobile data behind carrier NAT. The relay introduces
 *               the two ends and carries nothing else. The access code is
 *               checked on the data channel, so the relay never sees it, and
 *               the room is in the URL fragment, so the relay never sees that
 *               either.
 */
'use strict';

const $ = (id) => document.getElementById(id);

const TOKEN_KEY = 'dexweb.token';
const FIT_KEY = 'dexweb.fit';
const HINT_KEY = 'dexweb.hint.desktop';

/** How long to wait for the phone's WebRTC side to finish loading, and how often. */
const RTC_RETRY_MS = 3500;
const RTC_MAX_ATTEMPTS = 4;

const app = {
  /** 'local' | 'rendezvous' */
  kind: location.hash.length > 1 ? 'rendezvous' : 'local',
  room: location.hash.slice(1),
  token: sessionStorage.getItem(TOKEN_KEY) || '',
  pin: '',
  ws: null,
  rtc: null,
  attempt: 0,
  control: true,
  files: true,
  width: 0,
  height: 0,
  path: '',
  iceServers: [],
  desktop: false,
};

/* ── the gate ───────────────────────────────────────────────────────── */

$('gate-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const pin = $('pin').value.trim();
  if (!pin) return;
  $('gate-go').disabled = true;
  $('gate-go').classList.add('loading');
  $('pin').value = '';
  paintPin();
  app.pin = pin;
  if (app.kind === 'rendezvous') {
    // Nothing to post to: the code is proven on the data channel once the peer
    // connection exists. Remember it and start connecting.
    enter();
  } else {
    authOverHttp(pin);
  }
});

/*
 * The access code as six painted slots.
 *
 * One real <input> sits transparent on top; its value is mirrored into the
 * cells behind it, which is the only reliable way to line digits up with boxes
 * across font metrics. The input stays the single source of truth, so the auth
 * flow above never has to know this exists.
 */
const pinCells = Array.from($('pin-cells').children);

function paintPin() {
  const input = $('pin');
  const value = input.value.replace(/\D/g, '').slice(0, 6);
  if (value !== input.value) input.value = value;
  const focused = document.activeElement === input;
  pinCells.forEach((cell, i) => {
    cell.textContent = value[i] || '';
    cell.classList.toggle('filled', i < value.length);
    cell.classList.toggle('active', focused && i === Math.min(value.length, 5));
  });
}

(() => {
  const input = $('pin');
  const field = input.closest('.pin-field');
  input.addEventListener('input', paintPin);
  input.addEventListener('focus', () => { field.classList.add('focused'); paintPin(); });
  input.addEventListener('blur', () => { field.classList.remove('focused'); paintPin(); });
  paintPin();
})();

async function authOverHttp(pin) {
  try {
    const res = await fetch('/api/auth', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'pin=' + encodeURIComponent(pin),
    });
    const body = await res.json().catch(() => ({}));
    if (res.ok && body.token) {
      app.token = body.token;
      sessionStorage.setItem(TOKEN_KEY, body.token);
      enter();
      return;
    }
    gateError(res.status === 429
      ? 'Too many attempts. Try again in ' + (body.seconds || 30) + ' seconds.'
      : 'That code did not match.');
  } catch (err) {
    gateError('The phone did not answer.');
  }
}

function gateError(text) {
  const el = $('gate-error');
  el.textContent = text;
  el.hidden = false;
  $('gate').hidden = false;
  $('app').hidden = true;
  $('gate-go').disabled = false;
  $('gate-go').classList.remove('loading');
  $('pin').focus();
}

function enter() {
  $('gate').hidden = true;
  $('app').hidden = false;
  applyFit(localStorage.getItem(FIT_KEY) || 'contain');
  $('fit-select').value = localStorage.getItem(FIT_KEY) || 'contain';
  if (app.kind === 'rendezvous') {
    startRendezvous();
  } else {
    connectSocket();
  }
}

function signOut() {
  const done = () => {
    sessionStorage.removeItem(TOKEN_KEY);
    location.reload();
  };
  if (app.kind !== 'local') {
    done();
    return;
  }
  fetch('/api/logout', { method: 'POST', headers: { 'X-Dex-Token': app.token } })
    .catch(() => {}).finally(done);
}

/* ── local session: the phone's own socket is the rendezvous ────────── */

async function connectSocket() {
  status('Connecting…', '');
  try {
    const res = await fetch('/api/state', { headers: { 'X-Dex-Token': app.token } });
    if (res.status === 401) {
      sessionStorage.removeItem(TOKEN_KEY);
      location.reload();
      return;
    }
    const state = await res.json();
    app.iceServers = state.ice || [];
  } catch (e) {
    /* no ICE servers is survivable on a local network */
  }

  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  let ws;
  try {
    ws = new WebSocket(scheme + '://' + location.host + '/ws?t='
      + encodeURIComponent(app.token));
  } catch (e) {
    status('Could not open a signalling socket', 'bad');
    return;
  }
  app.ws = ws;
  let opened = false;
  ws.onopen = () => {
    opened = true;
    startPeer();
  };
  ws.onclose = () => {
    // A socket that never opened means the token was refused — almost always
    // one issued by an earlier run of the phone's server, which keeps no
    // sessions across a restart. Check, and ask for the code again rather than
    // sitting on a dead page.
    if (!opened) {
      recoverSession();
      return;
    }
    // The peer connection outlives the socket that set it up, so a close after
    // it has formed is not fatal.
    if (!app.rtc || !app.rtc.connected) status('Disconnected', 'bad');
  };
  ws.onerror = () => ws.close();
  ws.onmessage = (ev) => {
    const msg = parse(ev.data);
    if (msg && msg.t === 'rtc' && app.rtc) app.rtc.onSignal(msg.v || {});
  };
}

/** The session the browser was holding is gone; start again from the code. */
function recoverSession() {
  status('Session expired', 'bad');
  fetch('/api/state', { headers: { 'X-Dex-Token': app.token } }).then((res) => {
    if (res.ok) {
      // The token is fine and something else refused the socket — say so
      // rather than bouncing the user back to a gate that will not help.
      status('The phone refused the signalling socket', 'bad');
      return;
    }
    sessionStorage.removeItem(TOKEN_KEY);
    app.token = '';
    app.pin = '';
    askForCode();
  }).catch(() => status('The phone did not answer', 'bad'));
}

function startPeer() {
  const ws = app.ws;
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  app.attempt++;
  const peerId = Math.random().toString(36).slice(2, 10);
  app.rtc = new RtcSession(
    (obj) => ws.send(JSON.stringify({ t: 'rtc', v: Object.assign({ peer: peerId }, obj) })),
    app.iceServers);
  app.rtc.join();
}

/** The phone's WebRTC side loads in the background; "not yet" is not "never". */
function retryLater() {
  if (app.kind !== 'local' || app.attempt >= RTC_MAX_ATTEMPTS) {
    status('The phone could not start WebRTC', 'bad');
    return;
  }
  status('Waiting for the phone…', '');
  setTimeout(startPeer, RTC_RETRY_MS);
}

/* ── rendezvous session: a relay introduces the two ends ────────────── */

async function startRendezvous() {
  status('Finding the phone…', '');
  try {
    const res = await fetch('/ice');
    app.iceServers = res.ok ? await res.json() : [];
  } catch (e) {
    app.iceServers = [];
  }
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(scheme + '://' + location.host + '/signal');
  app.ws = ws;
  ws.onopen = () => ws.send(JSON.stringify({ t: 'join', room: app.room }));
  ws.onmessage = (ev) => {
    const msg = parse(ev.data);
    if (!msg) return;
    if (msg.t === 'joined') {
      // The phone offers, so this side only ever answers — nothing to start.
      app.rtc = new RtcSession((obj) => ws.send(JSON.stringify(obj)), app.iceServers);
      return;
    }
    if (msg.t === 'no-host') {
      status('That phone is not at the rendezvous', 'bad');
      return;
    }
    if (msg.t === 'host-gone') {
      status('The phone left the rendezvous', 'bad');
      return;
    }
    if (app.rtc) app.rtc.onSignal(msg);
  };
  ws.onclose = () => {
    if (!app.rtc || !app.rtc.connected) status('The rendezvous closed the connection', 'bad');
  };
}

function parse(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/* ── one peer connection ────────────────────────────────────────────── */

class RtcSession {
  constructor(sendSignal, iceServers) {
    this.sendSignal = sendSignal;
    this.control = null;
    this.file = null;
    this.transfers = new Map();
    this.nextTransfer = 1;
    this.connected = false;
    this.pc = new RTCPeerConnection({ iceServers: iceServers, bundlePolicy: 'max-bundle' });
    this.pc.onicecandidate = (e) => {
      if (!e.candidate) return;
      this.sendSignal({
        t: 'ice',
        mid: e.candidate.sdpMid,
        index: e.candidate.sdpMLineIndex,
        candidate: e.candidate.candidate,
      });
    };
    this.pc.ontrack = (e) => this.onTrack(e);
    this.pc.ondatachannel = (e) => this.onChannel(e.channel);
    this.pc.onconnectionstatechange = () => this.onStateChange();
  }

  join() {
    this.sendSignal({ t: 'join' });
  }

  onSignal(msg) {
    if (msg.t === 'offer') {
      this.onOffer(msg.sdp);
    } else if (msg.t === 'ice') {
      this.pc.addIceCandidate({
        candidate: msg.candidate,
        sdpMid: msg.mid,
        sdpMLineIndex: msg.index,
      }).catch(() => {});
    } else if (msg.t === 'full') {
      status('The phone already has as many viewers as it allows', 'bad');
    } else if (msg.t === 'unavailable') {
      this.close();
      retryLater();
    } else if (msg.t === 'error') {
      status('The phone could not start the capture', 'bad');
    }
  }

  async onOffer(sdp) {
    try {
      await this.pc.setRemoteDescription({ type: 'offer', sdp: sdp });
      const answer = await this.pc.createAnswer();
      await this.pc.setLocalDescription(answer);
      this.sendSignal({ t: 'answer', sdp: answer.sdp });
    } catch (e) {
      status('Could not answer the phone', 'bad');
    }
  }

  onTrack(e) {
    const stream = e.streams[0];
    if (!stream) return;
    const video = $('video');
    video.srcObject = stream;
    video.play().catch(() => {});
    hideNote();
  }

  onChannel(channel) {
    if (channel.label === 'ctl') {
      this.control = channel;
      channel.onmessage = (e) => this.onControlMessage(e.data);
      channel.onopen = () => this.authenticate();
      channel.onclose = () => {
        this.control = null;
      };
    } else if (channel.label === 'file') {
      this.file = channel;
      channel.binaryType = 'arraybuffer';
      channel.onmessage = (e) => this.onFileMessage(e.data);
      channel.onopen = () => {
        if (app.files) this.list('');
      };
    }
  }

  controlOpen() {
    return this.control && this.control.readyState === 'open';
  }

  /**
   * Prove who we are on the data channel.
   *
   * A local session already has a session token from the HTTP door, and
   * replaying that is better than making someone type the code a second time
   * for what is, to them, the same session. A rendezvous session has no HTTP
   * door and only ever has the code.
   */
  authenticate() {
    if (!this.controlOpen()) return;
    if (app.kind === 'local' && app.token) {
      this.control.send(JSON.stringify({ t: 'auth', token: app.token }));
    } else if (app.pin) {
      this.control.send(JSON.stringify({ t: 'auth', pin: app.pin }));
    } else {
      askForCode();
    }
  }

  send(obj) {
    if (this.controlOpen()) this.control.send(JSON.stringify(obj));
  }

  onControlMessage(data) {
    const msg = parse(data);
    if (!msg) return;
    if (msg.t === 'hello' && msg.auth) {
      this.authenticate();
      return;
    }
    if (msg.t === 'auth') {
      if (msg.ok) {
        status('Connected', 'live');
        return;
      }
      app.pin = '';
      gateError(msg.left > 0
        ? 'That code did not match. ' + msg.left + ' attempts left.'
        : 'That code did not match.');
      return;
    }
    if (msg.t === 'format') {
      app.width = msg.w;
      app.height = msg.h;
      app.control = !!msg.control;
      app.files = !!msg.files;
      app.desktop = !!msg.desktop;
      $('control-note').textContent = app.control
        ? 'Clicks are delivered as touches, and typing goes to the field that has focus on the phone.'
        : 'Control is turned off on the phone — this is a view-only session.';
      $('files-toggle').style.display = app.files ? '' : 'none';
      updateHint();
      if (this.connected) status(app.width + '×' + app.height + ' · WebRTC', 'live');
    }
  }

  onStateChange() {
    const state = this.pc.connectionState;
    if (state === 'connected') {
      this.connected = true;
      status(app.width ? app.width + '×' + app.height + ' · WebRTC' : 'Connected', 'live');
    } else if (state === 'failed') {
      status('The connection failed. A TURN server is usually what is missing.', 'bad');
    } else if (state === 'disconnected') {
      status('Reconnecting…', '');
    }
  }

  close() {
    try {
      this.pc.close();
    } catch (e) { /* already closed */ }
    if (app.rtc === this) app.rtc = null;
  }

  /* files — see WebRtcFiles on the phone */

  fileOpen() {
    return this.file && this.file.readyState === 'open';
  }

  sendFile(obj) {
    if (this.fileOpen()) this.file.send(JSON.stringify(obj));
  }

  list(path) {
    this.sendFile({ t: 'ls', path: path });
  }

  download(path) {
    this.sendFile({ t: 'get', id: this.nextTransfer++, path: path });
  }

  onFileMessage(data) {
    if (typeof data !== 'string') {
      const bytes = new Uint8Array(data);
      if (bytes.length < 4) return;
      const id = (bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]) >>> 0;
      const transfer = this.transfers.get(id);
      if (transfer && transfer.chunks) transfer.chunks.push(bytes.slice(4));
      return;
    }
    const msg = parse(data);
    if (!msg) return;
    const transfer = this.transfers.get(msg.id);
    switch (msg.t) {
      case 'ls':
        renderListing(msg.data);
        break;
      case 'get-begin':
        this.transfers.set(msg.id, { chunks: [], name: msg.name });
        break;
      case 'get-end':
        if (transfer) {
          saveBlob(new Blob(transfer.chunks), transfer.name);
          this.transfers.delete(msg.id);
        }
        break;
      case 'put-ready':
        if (transfer && transfer.start) transfer.start();
        break;
      case 'put-done':
        if (transfer && transfer.done) transfer.done();
        this.transfers.delete(msg.id);
        break;
      case 'error':
        if (transfer && transfer.fail) transfer.fail(msg.why);
        this.transfers.delete(msg.id);
        break;
      default:
        break;
    }
  }

  /**
   * Push a file up the data channel.
   *
   * Chunked and paused against bufferedAmount, because SCTP will accept far
   * more than the link can carry and then die of it. 16 KB is comfortably
   * inside the smallest message size any implementation guarantees.
   */
  upload(file, onProgress, onDone, onFail) {
    const id = this.nextTransfer++;
    const CHUNK = 16 * 1024;
    const LIMIT = 1024 * 1024;
    let offset = 0;
    this.transfers.set(id, {
      done: onDone,
      fail: onFail,
      start: async () => {
        try {
          while (offset < file.size) {
            while (this.file.bufferedAmount > LIMIT) {
              await new Promise((r) => setTimeout(r, 20));
            }
            if (!this.fileOpen()) throw new Error('closed');
            const bytes = new Uint8Array(
              await file.slice(offset, offset + CHUNK).arrayBuffer());
            const framed = new Uint8Array(4 + bytes.length);
            framed[0] = (id >>> 24) & 0xff;
            framed[1] = (id >>> 16) & 0xff;
            framed[2] = (id >>> 8) & 0xff;
            framed[3] = id & 0xff;
            framed.set(bytes, 4);
            this.file.send(framed);
            offset += bytes.length;
            onProgress(Math.round((offset / file.size) * 100));
          }
          this.sendFile({ t: 'put-end', id: id });
        } catch (e) {
          this.sendFile({ t: 'cancel', id: id });
          onFail('send');
        }
      },
    });
    this.sendFile({ t: 'put', id: id, name: file.name, size: file.size });
  }
}

function askForCode() {
  $('app').hidden = true;
  $('gate').hidden = false;
  $('gate-go').disabled = false;
  $('gate-go').classList.remove('loading');
  paintPin();
  $('pin').focus();
}

function sendCtl(obj) {
  if (app.rtc) app.rtc.send(obj);
}

function status(text, cls) {
  $('status').textContent = text;
  $('status-dot').className = 'dot' + (cls ? ' ' + cls : '');
}

function hideNote() {
  const note = $('stage-note');
  if (note.style.display !== 'none') note.style.display = 'none';
}

/* ── pointer and keyboard ───────────────────────────────────────────── */

const SLOP = 6;
const LONG_MS = 500;

let pointer = null;

/**
 * Browser coordinates to a fraction of the picture.
 *
 * A <video> letterboxes inside its box, so the drawn picture is not the
 * element: mapping against the box would push every click towards the middle on
 * a screen whose aspect does not match the window's.
 */
function normalise(e) {
  const el = $('video');
  const rect = el.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  let left = rect.left;
  let top = rect.top;
  let width = rect.width;
  let height = rect.height;
  const nw = el.videoWidth;
  const nh = el.videoHeight;
  if (nw && nh) {
    const scale = Math.min(rect.width / nw, rect.height / nh);
    const drawnW = nw * scale;
    const drawnH = nh * scale;
    left += (rect.width - drawnW) / 2;
    top += (rect.height - drawnH) / 2;
    width = drawnW;
    height = drawnH;
  }
  return {
    x: Math.min(1, Math.max(0, (e.clientX - left) / width)),
    y: Math.min(1, Math.max(0, (e.clientY - top) / height)),
  };
}

const stage = $('stage');

stage.addEventListener('pointerdown', (e) => {
  if (!app.control) return;
  const p = normalise(e);
  if (!p) return;
  e.preventDefault();
  const el = $('video');
  if (el.setPointerCapture) el.setPointerCapture(e.pointerId);
  pointer = { id: e.pointerId, start: p, at: performance.now(), dragging: false };
  // A right click is a long press: it is what raises a context menu on Android,
  // and it is what a desktop user means by right-clicking.
  if (e.button === 2) {
    sendCtl({ t: 'long', x: p.x, y: p.y });
    pointer = null;
  }
});

stage.addEventListener('pointermove', (e) => {
  if (!pointer || e.pointerId !== pointer.id) return;
  const p = normalise(e);
  if (!p) return;
  const rect = $('video').getBoundingClientRect();
  const moved = Math.hypot((p.x - pointer.start.x) * rect.width,
    (p.y - pointer.start.y) * rect.height);
  if (!pointer.dragging) {
    if (moved < SLOP) return;
    pointer.dragging = true;
    sendCtl({ t: 'down', x: pointer.start.x, y: pointer.start.y });
  }
  sendCtl({ t: 'move', x: p.x, y: p.y });
});

function endPointer(e) {
  if (!pointer || e.pointerId !== pointer.id) return;
  const p = normalise(e) || pointer.start;
  if (pointer.dragging) {
    sendCtl({ t: 'up', x: p.x, y: p.y });
  } else if (performance.now() - pointer.at >= LONG_MS) {
    sendCtl({ t: 'long', x: p.x, y: p.y });
  } else {
    sendCtl({ t: 'tap', x: p.x, y: p.y });
  }
  pointer = null;
}

stage.addEventListener('pointerup', endPointer);
stage.addEventListener('pointercancel', endPointer);
stage.addEventListener('contextmenu', (e) => e.preventDefault());

stage.addEventListener('wheel', (e) => {
  if (!app.control) return;
  const p = normalise(e);
  if (!p) return;
  e.preventDefault();
  // deltaMode 1 counts lines, 2 counts pages; both become notches.
  const scale = e.deltaMode === 0 ? 1 / 100 : (e.deltaMode === 1 ? 1 / 3 : 1);
  sendCtl({
    t: 'scroll',
    x: p.x,
    y: p.y,
    dx: Math.max(-3, Math.min(3, e.deltaX * scale)),
    dy: Math.max(-3, Math.min(3, e.deltaY * scale)),
  });
}, { passive: false });

const sink = $('sink');

/*
 * Typing goes through a hidden textarea rather than raw keydown, because that
 * is the only thing a phone browser will raise its keyboard for and the only
 * way an IME's composed text (accents, CJK, autocorrect) is ever seen. The
 * field is emptied after every send, so its value is always exactly what has
 * not been forwarded yet.
 */
sink.addEventListener('input', () => {
  const text = sink.value;
  if (!text) return;
  sink.value = '';
  if (app.control) sendCtl({ t: 'text', v: text });
});

sink.addEventListener('keydown', (e) => {
  if (!app.control) return;
  const map = { Backspace: 'backspace', Enter: 'enter', Escape: 'back' };
  const vk = map[e.key];
  if (vk) {
    e.preventDefault();
    sendCtl({ t: 'vk', v: vk });
  }
});

sink.addEventListener('paste', (e) => {
  const text = (e.clipboardData || window.clipboardData).getData('text');
  if (!text) return;
  e.preventDefault();
  if (app.control) {
    sendCtl({ t: 'text', v: text });
    sendCtl({ t: 'clip', v: text });
  }
});

/* ── chrome ─────────────────────────────────────────────────────────── */

document.querySelectorAll('[data-vk]').forEach((btn) => {
  btn.addEventListener('click', () => sendCtl({ t: 'vk', v: btn.dataset.vk }));
});

$('kb-toggle').addEventListener('click', () => {
  sink.focus();
  $('kb-toggle').classList.add('on');
});

sink.addEventListener('blur', () => $('kb-toggle').classList.remove('on'));

$('fs-toggle').addEventListener('click', () => {
  if (document.fullscreenElement) {
    document.exitFullscreen();
  } else {
    document.documentElement.requestFullscreen().catch(() => {});
  }
});

document.addEventListener('fullscreenchange', () => {
  document.body.classList.toggle('is-fullscreen', !!document.fullscreenElement);
});

/**
 * The "this is the phone, not the desktop" note.
 *
 * The phone can only ever capture its own display, so when the DeX desktop is
 * on a screen connected to a computer this viewer necessarily shows the phone.
 * Say so once — a dismissal is remembered so it never nags a returning viewer.
 */
function updateHint() {
  const show = app.desktop && localStorage.getItem(HINT_KEY) !== 'off';
  $('viewer-hint').hidden = !show;
}

$('hint-dismiss').addEventListener('click', () => {
  localStorage.setItem(HINT_KEY, 'off');
  $('viewer-hint').hidden = true;
});

/**
 * The Files / Settings drawer.
 *
 * A slide-in over the stream rather than a column that reflows it: the picture
 * is the point, and taking width away from it every time someone glances at a
 * file list is the wrong trade. Tapping the same button again, the scrim, the
 * close control or Escape all put it away.
 */
function openPanel(which) {
  const panel = $('panel');
  if (panel.classList.contains('open') && panel.dataset.view === which) {
    closePanel();
    return;
  }
  const showFiles = which === 'files';
  panel.dataset.view = which;
  $('files-view').hidden = !showFiles;
  $('settings-view').hidden = showFiles;
  $('panel-title').textContent = showFiles ? 'Files' : 'Settings';
  $('files-toggle').classList.toggle('on', showFiles);
  $('menu-toggle').classList.toggle('on', !showFiles);
  $('scrim').hidden = false;
  panel.classList.add('open');
  panel.setAttribute('aria-hidden', 'false');
  if (showFiles && app.rtc) app.rtc.list(app.path);
}

function closePanel() {
  const panel = $('panel');
  panel.classList.remove('open');
  panel.setAttribute('aria-hidden', 'true');
  panel.dataset.view = '';
  $('scrim').hidden = true;
  $('files-toggle').classList.remove('on');
  $('menu-toggle').classList.remove('on');
}

$('files-toggle').addEventListener('click', () => openPanel('files'));
$('menu-toggle').addEventListener('click', () => openPanel('settings'));
$('panel-close').addEventListener('click', closePanel);
$('scrim').addEventListener('click', closePanel);
$('sign-out').addEventListener('click', signOut);

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && $('panel').classList.contains('open')) {
    e.stopPropagation();
    closePanel();
  }
});

$('fit-select').addEventListener('change', (e) => {
  localStorage.setItem(FIT_KEY, e.target.value);
  applyFit(e.target.value);
});

function applyFit(fit) {
  document.body.classList.toggle('actual-size', fit === 'actual');
}

/* ── files ──────────────────────────────────────────────────────────── */

function human(bytes) {
  if (bytes < 1024) return bytes + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  return value.toFixed(value < 10 ? 1 : 0) + ' ' + units[i];
}

/* Small inline icon set for the file list. Static markup, never user data. */
const FILE_ICONS = {
  dir: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>',
  file: '<path d="M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/><path d="M14 3v5h5" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>',
  up: '<path d="M12 19V6m0 0-6 6m6-6 6 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>',
};

function renderListing(data) {
  if (!data) return;
  app.path = data.path;
  const pathEl = $('path');
  pathEl.textContent = data.path;
  if (!data.granted) {
    const warn = document.createElement('span');
    warn.className = 'path-warn';
    warn.textContent = 'Limited — the phone has not been given all-files access.';
    pathEl.appendChild(warn);
  }
  const list = $('listing');
  list.textContent = '';
  if (data.parent) {
    list.appendChild(row('up', '..', '', false, () => app.rtc && app.rtc.list(data.parent)));
  }
  for (const entry of data.entries) {
    const size = entry.dir ? '' : human(entry.size);
    list.appendChild(row(entry.dir ? 'dir' : 'file', entry.name, size, entry.dir, () => {
      if (!app.rtc) return;
      if (entry.dir) app.rtc.list(entry.path);
      else app.rtc.download(entry.path);
    }));
  }
}

function row(kind, name, size, isDir, onClick) {
  const li = document.createElement('li');
  if (isDir) li.className = 'dir';
  const g = document.createElement('span');
  g.className = 'glyph';
  g.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">'
    + (FILE_ICONS[kind] || FILE_ICONS.file) + '</svg>';
  const n = document.createElement('span');
  n.className = 'name';
  n.textContent = name;
  const s = document.createElement('span');
  s.className = 'size';
  s.textContent = size;
  li.append(g, n, s);
  li.addEventListener('click', onClick);
  return li;
}

/** A file that arrived over the data channel, handed to the browser to save. */
function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

$('file-input').addEventListener('change', (e) => {
  upload(Array.from(e.target.files));
  e.target.value = '';
});

let dragDepth = 0;

['dragenter', 'dragover'].forEach((type) => {
  window.addEventListener(type, (e) => {
    if (!app.files) return;
    e.preventDefault();
    if (type === 'dragenter') dragDepth++;
    $('dropzone').hidden = false;
  });
});

['dragleave', 'drop'].forEach((type) => {
  window.addEventListener(type, (e) => {
    e.preventDefault();
    if (type === 'dragleave') dragDepth = Math.max(0, dragDepth - 1);
    else dragDepth = 0;
    if (dragDepth === 0) $('dropzone').hidden = true;
    if (type === 'drop' && app.files && e.dataTransfer) {
      upload(Array.from(e.dataTransfer.files));
    }
  });
});

function upload(files) {
  if (!files.length || !app.rtc) return;
  openPanel('files');
  files.forEach((file) => {
    const card = uploadCard(file);
    app.rtc.upload(file, card.progress, card.done, card.fail);
  });
}

function uploadCard(file) {
  const card = document.createElement('div');
  card.className = 'up';
  const head = document.createElement('div');
  head.className = 'up-name';
  const name = document.createElement('span');
  name.textContent = file.name;
  const pct = document.createElement('span');
  pct.textContent = '0%';
  head.append(name, pct);
  const bar = document.createElement('progress');
  bar.max = 100;
  bar.value = 0;
  card.append(head, bar);
  $('uploads').appendChild(card);
  return {
    progress(value) {
      bar.value = value;
      pct.textContent = value + '%';
    },
    done() {
      pct.textContent = 'Sent';
      bar.remove();
      setTimeout(() => card.remove(), 4000);
      if (app.rtc) app.rtc.list(app.path);
    },
    fail(why) {
      card.classList.add('err');
      pct.textContent = why === 'too-big' ? 'Too large'
        : (why === 'no-storage' ? 'No storage access' : 'Failed');
    },
  };
}

/* ── start ──────────────────────────────────────────────────────────── */

if (!('RTCPeerConnection' in window)) {
  gateError('This browser cannot do WebRTC, which is the only way in.');
  $('gate-go').disabled = true;
} else if (app.kind === 'rendezvous') {
  $('pin').focus();
} else if (app.token) {
  // A token from this tab's last life is worth trying before asking again.
  fetch('/api/state', { headers: { 'X-Dex-Token': app.token } }).then((res) => {
    if (res.ok) {
      enter();
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
      app.token = '';
      $('pin').focus();
    }
  }).catch(() => $('pin').focus());
} else {
  $('pin').focus();
}
