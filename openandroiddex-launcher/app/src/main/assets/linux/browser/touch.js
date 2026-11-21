// Touch, for a tablet's or a phone's browser (viewer.js): the same two ways
// LinuxOnDroid's own window offers on the phone (novnc/dex-input.js), so a
// person who knows one knows the other.
//
//   Touch      the finger is the pointer. Tap clicks where it lands; a drag
//              scrolls what is under it; a long press is a right-click, or,
//              if the finger then moves, a drag with the button held (to
//              select, to move a window). Two fingers pinch to zoom the
//              picture in and out and move it about.
//   Trackpad   the screen is a laptop's trackpad. A finger moves the
//              pointer, faster the faster it goes; a tap clicks where the
//              pointer is; tap and then press again and move for a drag; a
//              long press is a right-click. Two fingers scroll; a pinch zooms,
//              and a pointer pushed to the edge of a zoomed picture moves it.
//
// Only touch is taken here (pointerType 'touch'); a mouse, a trackpad and a
// pen go on as before. Taps are never held back to see if a second comes: two
// quick taps are two clicks, and Linux's own double-click timer decides what
// they mean, which is also why there is no double-tap to zoom.
//
// The numbers are dex-input.js's, which were tuned on phones.

const LONG_MS = 500;
const LONG_SLOP = 14;
const MOVE_SLOP = 12;
const TAP_EDGE = 10;
const TWO_DECIDE_MS = 60;
const PINCH_SLOP = 24;
const PAN_SLOP = 12;
const DOUBLE_MS = 300;
const DOUBLE_SLOP = 40;
const SCROLL_GAIN = 1.25;
const PTR_BASE = 1.6;
const PTR_ACCEL = 2.6;
const PTR_V0 = 0.35; // px/ms where acceleration starts
const PTR_V1 = 2.2; // px/ms where it saturates
const PTR_CURVE = 1.4;
const EDGE_BAND = 48;
const EDGE_RATE = 900; // px/s of the picture moved, with the pointer deep in the band
const IDLE_CURSOR_MS = 1200;

const LEFT = 1;
const RIGHT = 4;

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });

/**
 * {@code api}: desk() the desktop's {w, h}; toDesk(clientX, clientY) and
 * toClient(x, y) between the window and the desktop's pixels (unclamped);
 * overPicture(x, y); pointer(x, y, mask) to Linux; wheel(x, y, dx, dy) in a
 * wheel's pixels; zoom(), setZoom(z, clientX, clientY), panBy(dx, dy) for the
 * picture's own zoom; mode() 'touch' or 'trackpad'; live() whether the
 * picture is up; touched() on the first touch, and on every tap.
 */
export function createTouch(api, cursorEl, armedEl) {
  const pts = new Map(); // pointerId → {x, y}, the fingers down
  let phase = 'idle';
  let primary = -1;
  let p0 = null;
  let lastPt = null;
  let lastMs = 0;
  let vel = 0;
  let longTimer = 0;
  let lastTapAt = 0;
  let lastTapPt = null;
  let dbl = false;
  let d0 = 0;
  let c0 = null;
  let z0 = 1;
  let focal = null;
  let twoAt = 0;
  let twoKind = null;
  let held = 0;
  let twoMoved = false;

  // ── what Linux is sent ──

  const vc = { x: 0, y: 0 }; // the trackpad's pointer, in desktop pixels
  let fx = 0;
  let fy = 0;

  function send(x, y, mask) {
    fx = x;
    fy = y;
    api.pointer(Math.round(x), Math.round(y), mask);
  }
  function down(x, y, bit) {
    held |= bit;
    send(x, y, held);
  }
  function up(x, y, bit) {
    held &= ~bit;
    send(x, y, held);
  }
  function click(x, y, bit) {
    send(x, y, held);
    down(x, y, bit);
    up(x, y, bit);
  }
  function releaseAll() {
    if (held) {
      held = 0;
      send(fx, fy, 0);
    }
    hideArmed();
  }

  // ── the trackpad's pointer, drawn here: a touch screen shows none ──

  let idleTimer = 0;
  function paintCursor() {
    if (api.mode() !== 'trackpad' || !cursorEl) return;
    const { w } = api.desk();
    if (!w) return;
    const p = api.toClient(vc.x, vc.y);
    cursorEl.style.transform = `translate3d(${p.x}px, ${p.y}px, 0)`;
    cursorEl.hidden = false;
    cursorEl.classList.remove('idle');
    clearTimeout(idleTimer);
    idleTimer = setTimeout(() => cursorEl.classList.add('idle'), IDLE_CURSOR_MS);
  }
  function hideCursor() {
    if (cursorEl) cursorEl.hidden = true;
  }
  function recentre() {
    const { w, h } = api.desk();
    if (!w) return;
    const c = api.toDesk(innerWidth / 2, innerHeight / 2);
    vc.x = clamp(c.x, 0, w - 1);
    vc.y = clamp(c.y, 0, h - 1);
    paintCursor();
  }

  function gain(v) {
    const t = clamp((v - PTR_V0) / (PTR_V1 - PTR_V0), 0, 1);
    return PTR_BASE * (1 + (PTR_ACCEL - 1) * t ** PTR_CURVE);
  }

  /**
   * As a trackpad: 10 px of finger moves the pointer 10 px on screen at any
   * zoom, faster as the finger goes faster, held at the desktop's edge.
   */
  function moveBy(dx, dy, v) {
    const { w, h } = api.desk();
    if (!w) return;
    const s = api.toClient(1, 0).x - api.toClient(0, 0).x; // screen px per desktop px
    const g = gain(v) / Math.max(0.01, s);
    vc.x = clamp(vc.x + dx * g, 0, w - 1);
    vc.y = clamp(vc.y + dy * g, 0, h - 1);
    send(vc.x, vc.y, held);
    paintCursor();
  }

  // A pointer held at the edge of a zoomed picture moves the picture: the
  // trackpad has no pan of its own (two fingers are scroll).
  let edgeReq = 0;
  let edgeLast = 0;
  function edgeStart() {
    if (edgeReq) return;
    edgeLast = performance.now();
    edgeReq = requestAnimationFrame(edgeTick);
  }
  function edgeStop() {
    if (edgeReq) cancelAnimationFrame(edgeReq);
    edgeReq = 0;
  }
  function edgeTick(now) {
    edgeReq = 0;
    if (phase !== 'move' && phase !== 'select') return;
    const dt = Math.min(64, now - edgeLast) / 1000;
    edgeLast = now;
    const p = api.toClient(vc.x, vc.y);
    const over = (v) => clamp(v / EDGE_BAND, 0, 1);
    let dx = 0;
    let dy = 0;
    if (p.x > innerWidth - EDGE_BAND) dx = -EDGE_RATE * over(p.x - (innerWidth - EDGE_BAND)) * dt;
    else if (p.x < EDGE_BAND) dx = EDGE_RATE * over(EDGE_BAND - p.x) * dt;
    if (p.y > innerHeight - EDGE_BAND) dy = -EDGE_RATE * over(p.y - (innerHeight - EDGE_BAND)) * dt;
    else if (p.y < EDGE_BAND) dy = EDGE_RATE * over(EDGE_BAND - p.y) * dt;
    if ((dx || dy) && api.zoom() > 1.001) {
      api.panBy(dx, dy);
      paintCursor();
    }
    edgeReq = requestAnimationFrame(edgeTick);
  }

  // ── the ring a long press shows while it is deciding ──

  function showArmed(x, y) {
    if (!armedEl) return;
    armedEl.style.transform = `translate3d(${x}px, ${y}px, 0)`;
    armedEl.hidden = false;
  }
  function hideArmed() {
    if (armedEl) armedEl.hidden = true;
  }

  // ── the recogniser ──

  function reset() {
    clearTimeout(longTimer);
    longTimer = 0;
    phase = 'idle';
    p0 = null;
    lastPt = null;
    primary = -1;
    twoKind = null;
    hideArmed();
    edgeStop();
  }

  /** Whatever is in flight, ended cleanly: a held button goes up before the gesture changes. */
  function endGesture() {
    if (phase === 'select' && lastPt) {
      const at = api.mode() === 'trackpad' ? vc : api.toDesk(lastPt.x, lastPt.y);
      up(at.x, at.y, LEFT);
    }
    reset();
  }

  function onLong() {
    if (phase !== 'down1' || !lastPt || dist(lastPt, p0) > LONG_SLOP) return;
    phase = 'armed';
    showArmed(p0.x, p0.y);
  }

  function start(e) {
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const n = pts.size;
    if (n >= 3) {
      endGesture();
      phase = 'dead';
      return;
    }
    if (n === 1) {
      const now = performance.now();
      primary = e.pointerId;
      p0 = { x: e.clientX, y: e.clientY };
      lastPt = p0;
      // A touch on the black around the picture starts nothing, where the
      // finger is the pointer: it would be a click on the desktop's very edge.
      if (api.mode() !== 'trackpad' && !api.overPicture(p0.x, p0.y, TAP_EDGE)) {
        phase = 'dead';
        return;
      }
      dbl = !!lastTapPt && now - lastTapAt < DOUBLE_MS && dist(p0, lastTapPt) < DOUBLE_SLOP;
      lastMs = now;
      vel = 0;
      phase = 'down1';
      clearTimeout(longTimer);
      longTimer = setTimeout(onLong, LONG_MS);
      return;
    }
    // Two fingers. A second one during a drag is a palm: the drag holds a
    // button and goes on.
    if (phase === 'select') return;
    clearTimeout(longTimer);
    hideArmed();
    edgeStop();
    const [a, b] = [...pts.values()];
    d0 = dist(a, b);
    c0 = mid(a, b);
    z0 = api.zoom();
    focal = api.toDesk(c0.x, c0.y);
    twoAt = performance.now();
    twoKind = null;
    twoMoved = false;
    phase = 'two';
  }

  function twoMove() {
    const [a, b] = [...pts.values()];
    const d = dist(a, b);
    const c = mid(a, b);
    if (!twoKind) {
      if (performance.now() - twoAt < TWO_DECIDE_MS) return;
      if (Math.abs(d - d0) >= PINCH_SLOP) twoKind = 'pinch';
      else if (dist(c, c0) >= PAN_SLOP) twoKind = api.mode() === 'trackpad' ? 'scroll2' : 'pan';
      else return;
      phase = twoKind;
      twoMoved = true;
      c0 = c;
      return;
    }
    if (twoKind === 'pinch') {
      if (!focal || !d0) return;
      api.setZoom(z0 * (d / d0), c.x, c.y, focal);
      paintCursor();
      return;
    }
    if (twoKind === 'pan') {
      api.panBy(c.x - c0.x, c.y - c0.y);
      c0 = c;
      return;
    }
    // The trackpad's two-finger scroll, at the pointer.
    api.wheel(vc.x, vc.y, -(c.x - c0.x) * SCROLL_GAIN, -(c.y - c0.y) * SCROLL_GAIN);
    c0 = c;
  }

  function move(e) {
    if (!pts.has(e.pointerId)) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (phase === 'dead') return;
    if (phase === 'two' || phase === 'pinch' || phase === 'pan' || phase === 'scroll2') {
      if (pts.size >= 2) twoMove();
      return;
    }
    if (e.pointerId !== primary) return;
    const p = { x: e.clientX, y: e.clientY };
    const now = performance.now();
    const dt = Math.max(1, now - lastMs);
    const dx = p.x - lastPt.x;
    const dy = p.y - lastPt.y;
    vel = 0.6 * vel + 0.4 * (Math.hypot(dx, dy) / dt);
    lastMs = now;
    const pad = api.mode() === 'trackpad';
    switch (phase) {
      case 'down1':
        if (dist(p, p0) <= MOVE_SLOP) {
          lastPt = p;
          return;
        }
        clearTimeout(longTimer);
        if (!pad) {
          phase = 'scroll';
        } else if (dbl) {
          // The second press of a double tap, moving: drag with the button held.
          lastTapAt = 0;
          lastTapPt = null;
          dbl = false;
          down(vc.x, vc.y, LEFT);
          phase = 'select';
          edgeStart();
        } else {
          phase = 'move';
          edgeStart();
        }
        lastPt = p;
        return;
      case 'armed': {
        if (dist(p, p0) <= MOVE_SLOP) {
          lastPt = p;
          return;
        }
        // A long press, then a move: a drag with the button held.
        hideArmed();
        phase = 'select';
        const at = pad ? vc : api.toDesk(p0.x, p0.y);
        down(at.x, at.y, LEFT);
        if (pad) edgeStart();
        lastPt = p;
        return;
      }
      case 'scroll': {
        const at = api.toDesk(p.x, p.y);
        api.wheel(at.x, at.y, -dx * SCROLL_GAIN, -dy * SCROLL_GAIN);
        lastPt = p;
        return;
      }
      case 'move':
        moveBy(dx, dy, vel);
        lastPt = p;
        return;
      case 'select':
        if (pad) {
          moveBy(dx, dy, vel);
        } else {
          const at = api.toDesk(p.x, p.y);
          const { w, h } = api.desk();
          send(clamp(at.x, 0, w - 1), clamp(at.y, 0, h - 1), held);
        }
        lastPt = p;
        return;
      default:
        lastPt = p;
    }
  }

  function tap() {
    lastTapAt = performance.now();
    lastTapPt = p0;
    const pad = api.mode() === 'trackpad';
    const at = pad ? vc : api.toDesk(p0.x, p0.y);
    const { w, h } = api.desk();
    if (!pad) {
      // Where the finger landed is where the trackpad's pointer is, too.
      vc.x = clamp(at.x, 0, w - 1);
      vc.y = clamp(at.y, 0, h - 1);
    }
    click(clamp(at.x, 0, w - 1), clamp(at.y, 0, h - 1), LEFT);
    if (pad) paintCursor();
  }

  function end(e, cancelled) {
    if (!pts.has(e.pointerId)) return;
    pts.delete(e.pointerId);
    if (pts.size > 0) {
      // Fingers remain: anything of two fingers is over as soon as it is
      // not; the finger left starts nothing.
      if (phase === 'two' || phase === 'pinch' || phase === 'pan' || phase === 'scroll2') {
        // Two fingers down and up again without moving: a right-click.
        if (phase === 'two' && !twoMoved && !cancelled && performance.now() - twoAt < LONG_MS) {
          const pad = api.mode() === 'trackpad';
          const at = pad ? vc : focal || vc;
          const { w, h } = api.desk();
          click(clamp(at.x, 0, w - 1), clamp(at.y, 0, h - 1), RIGHT);
        }
        reset();
        phase = 'dead';
      }
      return;
    }
    const pad = api.mode() === 'trackpad';
    switch (phase) {
      case 'down1':
        if (!cancelled && lastPt && dist(lastPt, p0) <= MOVE_SLOP) tap();
        break;
      case 'armed':
        if (!cancelled) {
          const at = pad ? vc : api.toDesk(p0.x, p0.y);
          const { w, h } = api.desk();
          click(clamp(at.x, 0, w - 1), clamp(at.y, 0, h - 1), RIGHT);
        }
        break;
      case 'select': {
        const at = pad ? vc : api.toDesk(lastPt.x, lastPt.y);
        const { w, h } = api.desk();
        up(clamp(at.x, 0, w - 1), clamp(at.y, 0, h - 1), LEFT);
        break;
      }
      default:
        break;
    }
    if (cancelled) releaseAll();
    reset();
  }

  /** A pointer event from the picture: taken, and true, when it is a finger's. */
  function handle(e) {
    if (e.pointerType !== 'touch') return false;
    e.preventDefault();
    if (!api.live()) {
      pts.delete(e.pointerId);
      return true;
    }
    try {
      if (e.type === 'pointerdown') {
        api.touched();
        if (pts.size === 0 && phase !== 'idle') reset();
        start(e);
      } else if (e.type === 'pointermove') {
        move(e);
      } else if (e.type === 'pointerup') {
        end(e, false);
      } else if (e.type === 'pointercancel') {
        end(e, true);
      }
    } catch {
      releaseAll();
      pts.clear();
      reset();
    }
    return true;
  }

  return {
    handle,
    releaseAll() {
      releaseAll();
      pts.clear();
      reset();
    },
    recentre,
    paintCursor,
    hideCursor,
    /** The trackpad's pointer, where Linux has it (a mouse moved it meanwhile). */
    follow(x, y) {
      vc.x = x;
      vc.y = y;
    },
  };
}
