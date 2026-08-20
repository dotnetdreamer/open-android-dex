/*
 * Touch, in two grammars, and the only bridge a pointer has to noVNC.
 *
 * noVNC has no public method for sending a pointer event — `_sendMouse` is
 * private and `RFB.messages.pointerEvent` needs the private socket and the
 * private display. What it does have is a handler on its own canvas that reads
 * exactly `type`, `clientX`, `clientY` and `button`, and never looks at
 * `isTrusted` or `buttons`. So a synthetic MouseEvent dispatched at that canvas
 * is indistinguishable from a real one by the time it reaches the wire, and
 * that is how every gesture below leaves the page.
 *
 * The gestures themselves are ours. noVNC ships a GestureHandler with a fixed
 * vocabulary — one-finger drag is a left-drag, two fingers is scroll, and
 * pinch asks the REMOTE app to zoom by holding Ctrl — which is one mode, has
 * no viewer pan, and cannot be reshaped into two. It is starved by intercepting
 * touch events in the capture phase on an ancestor, which beats AT_TARGET on
 * the canvas no matter who registered first. That matters: RFB attaches its
 * listeners synchronously inside its own constructor, so there is no race we
 * could win.
 *
 * Only touch is intercepted. Every mouse event from the scrcpy display reaches
 * the canvas exactly as it did before this page existed — which is the reason
 * for capture-on-an-ancestor rather than an opaque overlay, and the single
 * most important thing not to regress.
 */

/* Constants. 1 CSS px is 1 dp here: setUseWideViewPort is left false. */
const LONG_MS = 500;      // Android's long press, and the web viewer's LONG_MS
const LONG_SLOP = 14;
const MOVE_SLOP = 12;
const TAP_EDGE = 10;      // tolerance around the picture's edge, see overPicture
const TWO_DECIDE_MS = 60; // noVNC decides at 50; 60 kills the misfire
const PINCH_SLOP = 24;
const PAN_SLOP = 12;
const DOUBLE_MS = 300;
const DOUBLE_SLOP = 40;
const SCROLL_GAIN = 1.25; // finger px to wheel px; RFB owns the 50 px notch
const PTR_BASE = [0.8, 1.2, 1.6, 2.2, 3.0];
const PTR_ACCEL = 2.6;
const PTR_V0 = 0.35;      // px/ms where acceleration starts
const PTR_V1 = 2.2;       // px/ms where it saturates
const PTR_CURVE = 1.4;    // >1 keeps slow movement linear, for precision
const EDGE_BAND = 48;
const EDGE_RATE = 900;    // px/s of auto-pan at full penetration
const CLICK_GAP = 12;     // ms between a synthetic down and its up
const IDLE_CURSOR_MS = 1200;

export function initInput(api) {
  const stage = api.stage;
  const vcurEl = document.getElementById('vcur');
  const armedEl = document.getElementById('armed');

  /*
   * releaseCapture is not in noVNC's documented API and its module has moved
   * between versions. A static import of the wrong path kills the whole
   * module, so it is resolved dynamically with a fallback and a no-op floor.
   */
  let releaseCapture = () => {};
  (async () => {
    for (const p of ['./core/util/events.js', './core/util/element.js']) {
      try {
        const m = await import(p);
        if (m && typeof m.releaseCapture === 'function') { releaseCapture = m.releaseCapture; break; }
      } catch (e) { /* try the next one */ }
    }
  })();

  /* ── emitters ─────────────────────────────────────────────────────────── */

  const held = new Set();
  let lastFx = 0, lastFy = 0;
  let pending = null, flushReq = 0;

  function raw(type, fx, fy, button) {
    const c = api.canvas;
    if (!c) return;
    const p = api.toClient(fx, fy);
    lastFx = fx; lastFy = fy;
    /*
     * bubbles:false plus an explicit releaseCapture() is the only combination
     * that gets both properties. RFB's mousedown calls setCapture(canvas),
     * which on Android WebView appends a full-screen div at z-index 10000 and
     * installs mousemove/mouseup proxies on window. A bubbling synthetic
     * mouseup reaches those proxies, which clone it and re-dispatch it at the
     * canvas — every drag move and every button-up doubled, which collapses a
     * selection in any editor. Non-bubbling alone leaves the capture div over
     * the whole UI forever, and leaves the window proxies installed to double
     * genuine mouse events on the DeX display.
     */
    c.dispatchEvent(new MouseEvent(type, {
      clientX: p.x, clientY: p.y, button, buttons: 0,
      bubbles: false, cancelable: true,
    }));
  }

  function flush() {
    if (flushReq) { cancelAnimationFrame(flushReq); flushReq = 0; }
    if (!pending) return;
    const p = pending; pending = null;
    raw('mousemove', p.fx, p.fy, 0);
  }

  /* Moves coalesce to one per frame. Accuracy does not depend on the flush:
     every pointer message carries x and y, button messages included. */
  function emitMove(fx, fy) {
    if (api.ui.down) return;
    pending = { fx, fy };
    if (!flushReq) flushReq = requestAnimationFrame(() => { flushReq = 0; flush(); });
  }

  function emitDown(fx, fy, button) {
    if (api.ui.down) return;
    flush();
    held.add(button);
    raw('mousedown', fx, fy, button);
  }

  function emitUp(fx, fy, button) {
    flush();
    held.delete(button);
    raw('mouseup', fx, fy, button);
    releaseCapture();
  }

  function emitClick(fx, fy, button) {
    emitDown(fx, fy, button);
    setTimeout(() => emitUp(fx, fy, button), CLICK_GAP);
  }

  function emitScroll(fx, fy, dx, dy) {
    if (api.ui.down) return;
    const c = api.canvas;
    if (!c) return;
    const p = api.toClient(fx, fy);
    c.dispatchEvent(new WheelEvent('wheel', {
      clientX: p.x, clientY: p.y,
      deltaX: dx, deltaY: dy, deltaMode: 0,
      bubbles: false, cancelable: true,
    }));
  }

  /* Any button still down when a stream ends leaves XFCE in a rubber-band
     selection that never finishes. Android's gesture navigator cancels touch
     streams routinely, so this is not a corner case. */
  function releaseAll() {
    for (const b of [...held]) emitUp(lastFx, lastFy, b);
    held.clear();
    releaseCapture();
    hideArmed();
  }

  /* ── the virtual cursor (mouse mode only) ─────────────────────────────── */

  const vc = { x: 0, y: 0 };
  let cursorIdle = 0;

  function recentre() {
    const { w, h } = api.fb();
    if (!w) return;
    const r = stage.getBoundingClientRect();
    const c = api.toFb(r.left + r.width / 2, r.top + r.height / 2);
    vc.x = api.clamp(c.x, 0, w - 1);
    vc.y = api.clamp(c.y, 0, h - 1);
    paintCursor();
  }

  function paintCursor() {
    if (api.ui.mode !== 'mouse' || !api.canvas || !api.canvas.width) return;
    const p = api.toClient(vc.x, vc.y);
    const r = stage.getBoundingClientRect();
    vcurEl.style.transform = `translate3d(${p.x - r.left}px, ${p.y - r.top}px, 0)`;
    vcurEl.classList.remove('idle');
    clearTimeout(cursorIdle);
    cursorIdle = setTimeout(() => vcurEl.classList.add('idle'), IDLE_CURSOR_MS);
  }

  function gain(v) {
    const t = api.clamp((v - PTR_V0) / (PTR_V1 - PTR_V0), 0, 1);
    const base = PTR_BASE[api.clamp(api.sens, 1, 5) - 1];
    return base * (1 + (PTR_ACCEL - 1) * Math.pow(t, PTR_CURVE));
  }

  /*
   * Dividing the gain by the live scale is what makes this feel like a
   * trackpad: 10 px of finger moves the pointer 10 px ON SCREEN at every zoom
   * level. Clamping is at the framebuffer, never at the viewport — the cursor
   * stops dead at the edge of the desktop, with no wrap and no rubber band.
   */
  function moveBy(dx, dy, v) {
    const { w, h } = api.fb();
    if (!w) return;
    const g = gain(v) / Math.max(0.01, api.zNow());
    vc.x = api.clamp(vc.x + dx * g, 0, w - 1);
    vc.y = api.clamp(vc.y + dy * g, 0, h - 1);
    emitMove(vc.x, vc.y);
    paintCursor();
  }

  /*
   * Mouse mode has no pan gesture — two fingers is scroll — so without this
   * there is no way to reach the far side of a 1280-wide desktop through a
   * 360-wide window. It runs while the cursor sits inside a band at the
   * visible edge, which is exactly when the user is pushing against it.
   */
  let edgeReq = 0, edgeLast = 0;
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
    const r = stage.getBoundingClientRect();
    let dx = 0, dy = 0;
    const over = (v) => api.clamp(v / EDGE_BAND, 0, 1);
    if (p.x > r.right - EDGE_BAND) dx = -EDGE_RATE * over(p.x - (r.right - EDGE_BAND)) * dt;
    else if (p.x < r.left + EDGE_BAND) dx = EDGE_RATE * over((r.left + EDGE_BAND) - p.x) * dt;
    if (p.y > r.bottom - EDGE_BAND) dy = -EDGE_RATE * over(p.y - (r.bottom - EDGE_BAND)) * dt;
    else if (p.y < r.top + EDGE_BAND) dy = EDGE_RATE * over((r.top + EDGE_BAND) - p.y) * dt;
    if (dx || dy) { api.panBy(dx, dy); paintCursor(); }
    edgeReq = requestAnimationFrame(edgeTick);
  }

  /* ── the armed ring ───────────────────────────────────────────────────── */

  /* Shown when a long press has decided it is a right-click, so the user can
     see which of the two outcomes is waiting before they lift. */
  function showArmed(clientX, clientY) {
    const r = stage.getBoundingClientRect();
    armedEl.style.transform = `translate3d(${clientX - r.left}px, ${clientY - r.top}px, 0)`;
    armedEl.hidden = false;
  }
  function hideArmed() { armedEl.hidden = true; }

  /* ── the recogniser ───────────────────────────────────────────────────── */

  let phase = 'idle';
  let p0 = null;          // where the primary finger went down, in client coords
  let primary = null;     // Touch.identifier of the finger we are following
  let lastPt = null;
  let lastMs = 0;
  let vel = 0;            // px/ms, EMA-smoothed
  let longTimer = 0;
  let lastTapAt = 0, lastTapPt = null;
  /* Set on a touchstart that lands soon after a tap, in the same place. It is
     what turns the SECOND press of a double tap into a held drag — the button
     has to go down when that press starts, not when it ends. */
  let dbl = false;
  let d0 = 0, c0 = null, z0 = 1, focal = null, twoAt = 0, twoKind = null;

  const pt = (t) => ({ x: t.clientX, y: t.clientY });
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
  const find = (list, id) => {
    for (let i = 0; i < list.length; i++) if (list[i].identifier === id) return list[i];
    return null;
  };

  /*
   * Is a client point over the picture rather than the letterbox around it?
   *
   * Only gesture STARTS are tested. toFb does not clamp and noVNC's own
   * clientToElement clips an out-of-range point to the extreme edge of the
   * framebuffer, so in touch mode a tap on the black band becomes a real click
   * on the very top row of the desktop — a finger nowhere near anything opening
   * the XFCE Applications menu. On a portrait phone the black is most of the
   * screen, so this is the common case, not the corner.
   *
   * A drag that starts on the picture and wanders off it keeps sending, and
   * keeps its button-up: clipping to the edge is what a selection drag to the
   * edge of the screen should do everywhere else.
   */
  function overPicture(p) {
    const c = api.canvas;
    if (!c || !c.width) return true;      // nothing painting yet; raw() no-ops anyway
    const r = c.getBoundingClientRect();
    /* A few px of tolerance: at fit zoom on a phone the top row of the desktop
       is a fraction of a CSS pixel tall, and aiming at the picture's edge is a
       deliberate act, not a stray tap. */
    return p.x >= r.left - TAP_EDGE && p.x < r.right + TAP_EDGE
        && p.y >= r.top - TAP_EDGE && p.y < r.bottom + TAP_EDGE;
  }

  function reset() {
    clearTimeout(longTimer);
    longTimer = 0;
    phase = 'idle';
    p0 = lastPt = primary = null;
    twoKind = null;
    hideArmed();
    edgeStop();
  }

  /* End whatever is in flight cleanly — a held button MUST be released before
     the gesture changes underneath it. */
  function endGesture() {
    if (phase === 'select' && lastPt) {
      const p = api.ui.mode === 'mouse' ? vc : api.toFb(lastPt.x, lastPt.y);
      emitUp(p.x, p.y, 0);
    }
    reset();
  }

  function onTouch(ev) {
    /*
     * Direct hands the touch straight back: no stopPropagation, no
     * preventDefault, nothing recognised here. noVNC's own GestureHandler sees
     * it at the canvas and does what it did before this page existed. That is
     * the whole of the mode — the default, and the right answer whenever there
     * is a real pointer to follow.
     */
    if (api.ui.mode === 'direct') {
      if (phase !== 'idle') { endGesture(); releaseAll(); }
      return;
    }
    /* Both of these run before anything that could throw: a handler that
       throws does not stop propagation, and noVNC would then process the same
       touch as well. preventDefault is also what kills the WebView's
       compatibility mousedown/mouseup, which would double every tap. */
    ev.stopPropagation();
    ev.preventDefault();
    try {
      if (ev.type === 'touchstart') onStart(ev);
      else if (ev.type === 'touchmove') onMove(ev);
      else onEnd(ev);
    } catch (e) {
      releaseAll();
      reset();
    }
  }

  function onStart(ev) {
    /* A tap on the picture is how you dismiss the panel, and that tap is spent
       on the dismissal rather than being forwarded to the guest. */
    if (api.ui.chrome === 'panel') { api.closePanel(); endGesture(); return; }
    if (api.ui.down) return;

    const n = ev.touches.length;
    if (n >= 3) { endGesture(); phase = 'dead'; return; }

    if (n === 1) {
      const t = ev.touches[0];
      const now = performance.now();
      primary = t.identifier;
      p0 = lastPt = pt(t);
      /* Mouse mode uses the whole stage as a trackpad and every point it sends
         comes from the already-clamped virtual cursor, so it is immune and must
         keep working over the black. Touch mode maps the finger straight onto
         the framebuffer, so a press off the picture starts nothing. Returning
         before the long timer is armed kills the tap, the right-click, the
         drag-select and the scroll in one place. */
      if (api.ui.mode !== 'mouse' && !overPicture(p0)) { phase = 'dead'; return; }
      dbl = !!lastTapPt && now - lastTapAt < DOUBLE_MS && dist(p0, lastTapPt) < DOUBLE_SLOP;
      lastMs = now;
      vel = 0;
      phase = 'down1';
      clearTimeout(longTimer);
      longTimer = setTimeout(onLong, LONG_MS);
      return;
    }

    if (n === 2) {
      /* A second finger during a select is a palm, not a gesture: the drag
         holds a button down and must not be aborted by it. */
      if (phase === 'select') return;
      /* Nothing else holds a button, so there is nothing to release — the
         one-finger gesture in flight simply stops being the gesture. */
      clearTimeout(longTimer);
      hideArmed();
      edgeStop();
      const a = pt(ev.touches[0]), b = pt(ev.touches[1]);
      d0 = dist(a, b);
      c0 = mid(a, b);
      z0 = api.ui.zoom === 'fit' ? api.zNow() : api.ui.zoom;
      focal = (api.canvas && api.canvas.width) ? api.toFb(c0.x, c0.y) : null;
      twoAt = performance.now();
      twoKind = null;
      phase = 'two';
    }
  }

  function onLong() {
    if (phase !== 'down1') return;
    if (dist(lastPt, p0) > LONG_SLOP) return;
    phase = 'armed';
    showArmed(p0.x, p0.y);
  }

  function onMove(ev) {
    if (api.ui.down || phase === 'dead') return;

    if (phase === 'two' || phase === 'pinch' || phase === 'pan' || phase === 'scroll2') {
      if (ev.touches.length < 2) return;
      twoMove(pt(ev.touches[0]), pt(ev.touches[1]));
      return;
    }

    const t = find(ev.touches, primary);
    if (!t) return;
    const p = pt(t);
    const now = performance.now();
    const dt = Math.max(1, now - lastMs);
    const dx = p.x - lastPt.x, dy = p.y - lastPt.y;
    vel = 0.6 * vel + 0.4 * (Math.hypot(dx, dy) / dt);
    lastMs = now;

    switch (phase) {
      case 'down1': {
        if (dist(p, p0) <= MOVE_SLOP) { lastPt = p; return; }
        clearTimeout(longTimer);
        if (api.ui.mode !== 'mouse') {
          phase = 'scroll';
        } else if (dbl) {
          /* The second press of a double tap, now moving: hold the button and
             drag — "double-tap and drag to select". */
          lastTapAt = 0; lastTapPt = null; dbl = false;
          emitDown(vc.x, vc.y, 0);
          phase = 'select';
          edgeStart();
        } else {
          phase = 'move';
          edgeStart();
        }
        lastPt = p;
        return;
      }
      case 'armed': {
        if (dist(p, p0) <= MOVE_SLOP) { lastPt = p; return; }
        /* Long press, then movement: the press becomes a left-button drag —
           "long press and drag to select". */
        hideArmed();
        phase = 'select';
        const start = api.ui.mode === 'mouse' ? vc : api.toFb(p0.x, p0.y);
        emitDown(start.x, start.y, 0);
        if (api.ui.mode === 'mouse') edgeStart();
        lastPt = p;
        return;
      }
      case 'scroll': {
        const sign = api.scroll === 'reverse' ? -1 : 1;
        const at = api.toFb(p.x, p.y);
        emitScroll(at.x, at.y, -dx * SCROLL_GAIN * sign, -dy * SCROLL_GAIN * sign);
        lastPt = p;
        return;
      }
      case 'move': {
        moveBy(dx, dy, vel);
        lastPt = p;
        return;
      }
      case 'select': {
        if (api.ui.mode === 'mouse') { moveBy(dx, dy, vel); }
        else { const at = api.toFb(p.x, p.y); emitMove(at.x, at.y); }
        lastPt = p;
        return;
      }
      default:
        lastPt = p;
    }
  }

  function twoMove(a, b) {
    const d = dist(a, b), c = mid(a, b);
    if (!twoKind) {
      if (performance.now() - twoAt < TWO_DECIDE_MS) return;
      if (Math.abs(d - d0) >= PINCH_SLOP) twoKind = 'pinch';
      else if (dist(c, c0) >= PAN_SLOP) twoKind = api.ui.mode === 'mouse' ? 'scroll2' : 'pan';
      else return;
      phase = twoKind;
      c0 = c;   // measure the pan from where the decision was made
      return;
    }

    if (twoKind === 'pinch') {
      if (!focal || !d0) return;
      const z = api.clamp(z0 * (d / d0), api.ZOOM_MIN, api.ZOOM_MAX);
      api.ui.zoom = z;
      api.layout();
      const want = api.toClientRaw(focal.x, focal.y);
      api.panBy(c.x - want.x, c.y - want.y);
      paintCursor();
      return;
    }

    if (twoKind === 'pan') {
      api.panBy(c.x - c0.x, c.y - c0.y);
      c0 = c;
      paintCursor();
      return;
    }

    /* scroll2 — mouse mode's two-finger scroll, delivered at the cursor. */
    const sign = api.scroll === 'reverse' ? -1 : 1;
    const dx = c.x - c0.x, dy = c.y - c0.y;
    emitScroll(vc.x, vc.y, -dx * SCROLL_GAIN * sign, -dy * SCROLL_GAIN * sign);
    c0 = c;
  }

  function onEnd(ev) {
    if (ev.touches.length > 0) {
      /* Fingers remain. Anything two-fingered is finished the moment it stops
         being two-fingered; a select keeps going on its own finger. */
      if (phase === 'pinch' || phase === 'pan' || phase === 'scroll2' || phase === 'two') {
        if (phase === 'pinch') api.setZoom(api.ui.zoom);
        reset();
        const t = ev.touches[0];
        primary = t.identifier;
        p0 = lastPt = pt(t);
        lastMs = performance.now();
        phase = 'dead';   // the leftover finger starts nothing; lifting it resets
      }
      return;
    }

    const cancelled = ev.type === 'touchcancel';

    switch (phase) {
      case 'down1':
        /* Still being in 'down1' at touchend already proves the press ended
           before the long timer promoted it, and MOVE_SLOP is the same
           threshold that decided this was neither a drag nor a scroll. The
           entry condition and the exit condition have to be the same number:
           a second test here only carves out presses that do nothing at all.
           It also survives a stalled main thread — a long timer delayed past
           its deadline still leaves a tap, not silence. */
        if (!cancelled && dist(lastPt, p0) <= MOVE_SLOP) tap();
        break;
      case 'armed':
        if (!cancelled) {
          const p = api.ui.mode === 'mouse' ? vc : api.toFb(p0.x, p0.y);
          emitClick(p.x, p.y, 2);
        }
        break;
      case 'select': {
        const p = api.ui.mode === 'mouse' ? vc : api.toFb(lastPt.x, lastPt.y);
        emitUp(p.x, p.y, 0);
        break;
      }
      case 'pinch':
        if (!cancelled) api.setZoom(api.ui.zoom);
        break;
      default:
        break;
    }
    if (cancelled) releaseAll();
    reset();
  }

  /*
   * Taps are never debounced: two quick taps are two quick clicks, and XFCE's
   * own double-click timer decides what they mean. That is also why there is no
   * double-tap-to-zoom in either mode — it would eat every double-click.
   */
  function tap() {
    lastTapAt = performance.now();
    lastTapPt = p0;

    /* An armed chip is spent here and only here. */
    let button = 0;
    if (api.armed === 1) button = 1;
    else if (api.armed === 2) button = 2;
    api.armed = 0;

    const p = api.ui.mode === 'mouse' ? vc : api.toFb(p0.x, p0.y);
    emitClick(p.x, p.y, button);
  }

  /* ── attachment ───────────────────────────────────────────────────────── */

  for (const t of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
    stage.addEventListener(t, onTouch, { capture: true, passive: false });
  }

  /* Mouse-only, and gated on isTrusted so our own synthetics never come back
     round. Ctrl+wheel is the viewer's zoom; a plain wheel is the guest's and
     is not touched. */
  stage.addEventListener('wheel', (ev) => {
    if (!ev.isTrusted || !ev.ctrlKey) return;
    ev.preventDefault();
    ev.stopPropagation();
    const cur = api.ui.zoom === 'fit' ? api.zNow() : api.ui.zoom;
    api.setZoom(cur * (ev.deltaY < 0 ? 1.1 : 1 / 1.1), ev.clientX, ev.clientY);
  }, { capture: true, passive: false });

  /* focusOnClick is off so a tap cannot steal focus from the sink mid-sentence;
     a real click still has to hand the canvas its keyboard back. */
  stage.addEventListener('mousedown', (ev) => {
    if (!ev.isTrusted) return;
    if (!api.ui.kb && api.rfb) api.rfb.focus();
  }, { capture: true, passive: true });

  window.addEventListener('pointercancel', releaseAll, { passive: true });

  return { releaseAll, recentre, paintCursor };
}
