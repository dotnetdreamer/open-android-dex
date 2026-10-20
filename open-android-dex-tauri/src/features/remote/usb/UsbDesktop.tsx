import { useEffect, useRef, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { IS_MAC } from "../../../lib/host";
import type { ToGuest } from "../clipboardShare";
import type { DesktopHost } from "../DesktopCards";
import { avcC, codecString, sameBytes, splitAccessUnit } from "./h264";
import { ALTS, isModifier, keyDown, XK_ISO_LEVEL3_SHIFT } from "./keysym";

/**
 * The phone's Linux desktop over adb, as video (remote_usb.rs, LinuxStream.java).
 *
 * The phone reads Xvnc's pixels raw and encodes them on its video encoder;
 * this decodes them on this computer's GPU (WebCodecs) and draws them 1:1 into
 * a canvas the size of the window. The pointer is this computer's own, wearing
 * the shape Xvnc sends — it moves at this screen's rate whatever the video is
 * doing, which is most of why it feels like a local desktop. Keys go as
 * keysyms (keysym.ts), and the window's size goes as the desktop's. The
 * clipboard, files dropped on the window and what Linux sends back are the
 * window's (DesktopRouter.tsx); this is only the guest's end of the clipboard.
 *
 * Nothing in the guest reaches this page's code: what comes in is video, a
 * cursor bitmap, clipboard text and a few numbers.
 */

/** Messages from the phone (see LinuxStream.java for the whole wire). */
const MSG_VIDEO = 0x01;
const MSG_CURSOR = 0x02;
const IN_POINTER = 0x10;
const IN_KEY = 0x11;
/** "A frame arrived": the phone keeps no more than a few unacknowledged in the pipe. */
const ACK = new Uint8Array([0x12]);

/** Pixels of wheel travel that make one click of a wheel in the guest. */
const WHEEL_STEP = 50;
/** The desktop sizes the phone takes (LinuxStream.java): the window's, within these. */
const MIN_W = 640;
const MIN_H = 400;
const MAX_W = 3840;
const MAX_H = 2400;

type Phase =
  | { kind: "connecting" }
  | { kind: "starting" }
  | { kind: "up" }
  | { kind: "stopped" }
  | { kind: "failed"; message: string };

interface Stats {
  size: string;
  fps: number;
  mbps: number;
  encodeMs: number;
  rttMs: number;
  encoder: string;
}

/** The desktop's size for a window: its own, in logical pixels, held even. */
function sizeFor(w: number, h: number): [number, number] {
  const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.round(v))) & ~1;
  return [clamp(w, MIN_W, MAX_W), clamp(h, MIN_H, MAX_H)];
}

/**
 * The stream's bitrate: by pixels, for the cable, or the Wi-Fi of wireless
 * debugging. A desktop is mostly still, so these are ceilings for motion
 * rather than what flows.
 */
function bitrateFor(w: number, h: number, wireless: boolean) {
  const bpp = wireless ? 0.07 : 0.18;
  const bits = w * h * 60 * bpp;
  return Math.round(wireless ? Math.min(30e6, Math.max(4e6, bits)) : Math.min(100e6, Math.max(10e6, bits)));
}

export function UsbDesktop({
  url,
  wireless,
  name,
  start,
  host,
  onLost,
}: {
  url: string;
  wireless: boolean;
  name: string;
  /** Start the desktop if it is not running: a window's first open, never its comebacks. */
  start: boolean;
  /** The window around this view: the clipboard, and where drops go. */
  host: DesktopHost;
  /** The stream went without the phone saying why: the cable, most likely. */
  onLost: () => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<Phase>({ kind: "connecting" });
  const [stats, setStats] = useState<Stats | null>(null);
  const [pill, setPill] = useState(true);
  /** The clipboard's switch, as the window last said it; null until it has. */
  const [share, setShare] = useState<boolean | null>(null);
  const actions = useRef({ again: () => {}, fullscreen: () => {}, clipboard: () => {} });
  const lostRef = useRef(onLost);
  lostRef.current = onLost;

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true })!;
    const win = getCurrentWindow();
    let live = true;
    let ws: WebSocket | null = null;
    /** A picture is on screen: input goes to the guest from here on. */
    let up = false;
    /** The phone has said what it is encoding: the window can ask for its size. */
    let configured = false;
    /** The phone said why it is ending; the close after it is not a loss. */
    let told = false;

    // ── the picture ──
    let dw = 0;
    let dh = 0;
    let cw = 0;
    let ch = 0;
    let decoder: VideoDecoder | null = null;
    let description: Uint8Array | null = null;
    let sps: Uint8Array | null = null;
    let pps: Uint8Array | null = null;
    let needKey = true;
    let askedKeyAt = 0;
    let drawn = 0;
    let shownAt = performance.now();
    let phoneStats = { fps: 0, kbps: 0, encodeMs: 0 };
    let rtt = 0;
    let encoder = "";

    const send = (m: string | Uint8Array) => {
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(m);
    };
    const askKey = () => {
      const now = performance.now();
      if (now - askedKeyAt < 500) return;
      askedKeyAt = now;
      send(JSON.stringify({ t: "keyframe" }));
    };

    const draw = (frame: VideoFrame) => {
      if (live && dw > 0) {
        // Resized here, with the first picture at the new size in hand:
        // setting a canvas's size clears it, and the old picture should stay
        // up until there is a new one to replace it.
        if (canvas.width !== dw || canvas.height !== dh) {
          canvas.width = dw;
          canvas.height = dh;
        }
        ctx.drawImage(frame, 0, 0, cw || frame.displayWidth, ch || frame.displayHeight, 0, 0, dw, dh);
        drawn++;
        if (!up) {
          up = true;
          setPhase({ kind: "up" });
          host.showing(true);
          detach?.();
          detach = host.attach(toGuest);
          void host.clipboard.then((c) => c.connected());
          pillShown = true;
          setPill(true);
          window.setTimeout(() => {
            if (live && pillShown) {
              pillShown = false;
              setPill(false);
            }
          }, 2500);
          canvas.focus();
        }
      }
      frame.close();
    };

    const newDecoder = () => {
      if (decoder && decoder.state !== "closed") decoder.close();
      decoder = new VideoDecoder({
        output: draw,
        error: (e) => {
          logError(`remote: desktop decoder: ${e.message}`);
          description = null;
          needKey = true;
          askKey();
        },
      });
    };

    const onVideo = (flags: number, pts: number, payload: Uint8Array) => {
      const unit = splitAccessUnit(payload);
      if (unit.sps) sps = unit.sps;
      if (unit.pps) pps = unit.pps;
      if (!unit.data.length) return; // parameter sets only
      const isKey = unit.key || (flags & 1) !== 0;
      if (isKey && sps && pps) {
        const d = avcC(sps, pps);
        if (!decoder || decoder.state === "closed" || !sameBytes(d, description)) {
          if (!decoder || decoder.state === "closed") newDecoder();
          decoder!.configure({
            codec: codecString(sps),
            description: d,
            optimizeForLatency: true,
            hardwareAcceleration: "prefer-hardware",
          });
          description = d;
        }
        needKey = false;
      }
      if (needKey || !decoder || decoder.state !== "configured") {
        askKey();
        return;
      }
      // Behind by more than a couple of frames is a decoder that cannot keep
      // up; start over from a key frame rather than show a growing delay.
      if (decoder.decodeQueueSize > 3 && !isKey) {
        needKey = true;
        askKey();
        return;
      }
      decoder.decode(new EncodedVideoChunk({ type: isKey ? "key" : "delta", timestamp: pts, data: unit.data }));
    };

    // ── the pointer ──
    let pillShown = true;
    let buttons = 0;
    let lastX = 0;
    let lastY = 0;
    const toDesktop = (clientX: number, clientY: number): [number, number] => {
      const r = canvas.getBoundingClientRect();
      if (!dw || !r.width) return [0, 0];
      const s = Math.min(r.width / dw, r.height / dh);
      const ox = (r.width - dw * s) / 2;
      const oy = (r.height - dh * s) / 2;
      const x = Math.floor((clientX - r.left - ox) / s);
      const y = Math.floor((clientY - r.top - oy) / s);
      return [Math.max(0, Math.min(dw - 1, x)), Math.max(0, Math.min(dh - 1, y))];
    };
    const pointer = (x: number, y: number, mask: number) => {
      lastX = x;
      lastY = y;
      send(new Uint8Array([IN_POINTER, mask & 0xff, x >> 8, x & 0xff, y >> 8, y & 0xff]));
    };
    /** The browser's buttons as RFB's: left 1, middle 2, right 4, back 128 (X's button 8). */
    const rfbButtons = (b: number) => (b & 1 ? 1 : 0) | (b & 4 ? 2 : 0) | (b & 2 ? 4 : 0) | (b & 8 ? 128 : 0);
    const onPointer = (e: PointerEvent) => {
      if (!up) return;
      const [x, y] = toDesktop(e.clientX, e.clientY);
      const next = rfbButtons(e.buttons);
      if (e.type === "pointerdown") {
        canvas.setPointerCapture(e.pointerId);
        canvas.focus();
      }
      if (e.type === "pointerup" || e.type === "pointercancel") {
        if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
      }
      buttons = next;
      pointer(x, y, buttons);
      // The status pill comes down when the pointer rests at the top edge,
      // and goes once it leaves the strip it sits in.
      if (e.type === "pointermove" && !buttons) {
        const want = e.clientY <= 4 || (pillShown && e.clientY < 64);
        if (want !== pillShown) {
          pillShown = want;
          setPill(want);
        }
      }
      e.preventDefault();
    };
    let wheelX = 0;
    let wheelY = 0;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (!up) return;
      const k = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 800 : 1;
      wheelX += e.deltaX * k;
      wheelY += e.deltaY * k;
      const [x, y] = toDesktop(e.clientX, e.clientY);
      const click = (bit: number) => {
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
    };
    const onContextMenu = (e: Event) => e.preventDefault();

    const setCursor = (hotX: number, hotY: number, w: number, h: number, rgba: Uint8Array) => {
      if (!w || !h) {
        canvas.style.cursor = "none";
        return;
      }
      const c = document.createElement("canvas");
      c.width = w;
      c.height = h;
      const g = c.getContext("2d")!;
      g.putImageData(new ImageData(new Uint8ClampedArray(rgba.buffer, rgba.byteOffset, w * h * 4), w, h), 0, 0);
      canvas.style.cursor = `url(${c.toDataURL("image/png")}) ${Math.min(hotX, w - 1)} ${Math.min(hotY, h - 1)}, default`;
    };

    // ── keys ──
    const down = new Map<string, number>();
    const key = (keysym: number, isDown: boolean) =>
      send(
        new Uint8Array([IN_KEY, isDown ? 1 : 0, (keysym >>> 24) & 0xff, (keysym >>> 16) & 0xff, (keysym >>> 8) & 0xff, keysym & 0xff]),
      );
    const toggleFullscreen = async () => {
      try {
        await win.setFullscreen(!(await win.isFullscreen()));
      } catch (e) {
        logError(`remote: desktop fullscreen: ${String(e)}`);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "F11" && !e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey) {
        e.preventDefault();
        if (!e.repeat) void toggleFullscreen();
        return;
      }
      if (!up || e.isComposing || e.keyCode === 229) return;
      e.preventDefault();
      const a = keyDown(e, IS_MAC);
      if (a.kind === "none") return;
      if (a.kind === "type") {
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
    };
    const onKeyUp = (e: KeyboardEvent) => {
      if (!up) return;
      e.preventDefault();
      const k = down.get(e.code);
      if (k !== undefined) {
        down.delete(e.code);
        key(k, false);
      }
      // A Mac reports no keyup for a key pressed while Cmd was held: they all
      // go up with Cmd, or the guest would think them held for ever.
      if (IS_MAC && (e.code === "MetaLeft" || e.code === "MetaRight")) {
        for (const [code, ks] of [...down]) {
          if (!isModifier(ks) && ks !== XK_ISO_LEVEL3_SHIFT) {
            down.delete(code);
            key(ks, false);
          }
        }
      }
    };
    const releaseAll = () => {
      for (const ks of down.values()) key(ks, false);
      down.clear();
      if (buttons) {
        buttons = 0;
        pointer(lastX, lastY, 0);
      }
    };

    // ── the clipboard's guest end (the rest is clipboardShare.ts, the window's) ──
    /** Lets go of the clipboard's word for the guest, while this view has it. */
    let detach: (() => void) | null = null;
    let shared: boolean | null = null;
    /** The guest is holding, right now, what the window gave it. */
    let fromHost = false;
    const toGuest = (msg: ToGuest) => {
      if ("paste" in msg) {
        send(JSON.stringify({ t: "clip", text: msg.paste }));
        fromHost = true;
        return;
      }
      const was = shared;
      shared = msg.share;
      if (live) setShare(msg.share);
      if (msg.share) return;
      // Off means off: the computer's text, if that is what the guest has,
      // does not stay there to be pasted. The guest's own is left alone.
      if (was && fromHost) send(JSON.stringify({ t: "clip", text: "" }));
      fromHost = false;
    };
    const fromGuest = (text: string) => {
      fromHost = false;
      void host.clipboard.then((c) => c.fromGuest(text));
    };
    /** The picture has gone: no drop lands on it, and the clipboard waits for the next view. */
    const gone = () => {
      detach?.();
      detach = null;
      host.showing(false);
    };

    // ── the window's size is the desktop's ──
    let asked = "";
    let settle = 0;
    const askSize = () => {
      const [w, h] = sizeFor(window.innerWidth, window.innerHeight);
      const want = `${w}x${h}`;
      if (!configured || want === asked || (w === dw && h === dh)) return;
      asked = want;
      send(JSON.stringify({ t: "resize", w, h }));
    };
    const onResize = () => {
      window.clearTimeout(settle);
      settle = window.setTimeout(askSize, 400);
    };

    // ── the connection ──
    let pingTimer = 0;
    const connect = (asStart: boolean) => {
      ws?.close();
      up = false;
      configured = false;
      told = false;
      needKey = true;
      description = null;
      setPhase({ kind: "connecting" });
      const socket = new WebSocket(url);
      socket.binaryType = "arraybuffer";
      ws = socket;
      socket.onopen = () => {
        const [w, h] = sizeFor(window.innerWidth, window.innerHeight);
        socket.send(
          JSON.stringify({ t: "hello", v: 1, start: asStart, w, h, bitrate: bitrateFor(w, h, wireless), fps: 60, acks: true }),
        );
      };
      socket.onmessage = (ev) => {
        if (socket !== ws) return;
        if (typeof ev.data === "string") {
          let m: Record<string, unknown>;
          try {
            m = JSON.parse(ev.data);
          } catch {
            return;
          }
          onText(m);
          return;
        }
        const b = new Uint8Array(ev.data as ArrayBuffer);
        if (b[0] === MSG_VIDEO && b.length > 10) {
          // Acknowledged on arrival, before decoding: it is the link the
          // phone is pacing itself to.
          if (!(b[1] & 2)) socket.send(ACK);
          const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
          const pts = Number(v.getBigUint64(2));
          onVideo(b[1], pts, b.subarray(10));
        } else if (b[0] === MSG_CURSOR && b.length >= 9) {
          const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
          const w = v.getUint16(5);
          const h = v.getUint16(7);
          if (b.length >= 9 + w * h * 4) setCursor(v.getUint16(1), v.getUint16(3), w, h, b.subarray(9, 9 + w * h * 4));
        }
      };
      socket.onclose = () => {
        if (socket !== ws || !live) return;
        ws = null;
        releaseAllLocal();
        gone();
        if (!told) lostRef.current();
      };
    };
    /** Forget held keys without telling a phone that is already gone. */
    const releaseAllLocal = () => {
      down.clear();
      buttons = 0;
    };

    const onText = (m: Record<string, unknown>) => {
      switch (m.t) {
        case "state": {
          const state = String(m.state);
          const message = typeof m.message === "string" ? m.message : "";
          if (state === "starting") setPhase((p) => (p.kind === "up" ? p : { kind: "starting" }));
          else if (state === "running") setPhase((p) => (p.kind === "up" ? p : { kind: "connecting" }));
          else if (state === "stopped") {
            told = true;
            up = false;
            gone();
            setPhase({ kind: "stopped" });
          } else {
            told = true;
            up = false;
            gone();
            const why: Record<string, string> = {
              missing: "Linux is not installed on the phone yet.",
              installing: "Linux on the phone is still being set up.",
              unfinished: "Linux on the phone did not finish setting up. Open Linux there to try again.",
            };
            setPhase({ kind: "failed", message: message || why[state] || "The phone could not show the desktop." });
          }
          break;
        }
        case "video": {
          dw = Number(m.dw);
          dh = Number(m.dh);
          cw = Number(m.cw);
          ch = Number(m.ch);
          encoder = String(m.encoder ?? "");
          needKey = true;
          logInfo(`remote: desktop video ${dw}x${dh} from ${encoder}${m.hw ? "" : " (software)"}, ${Math.round(Number(m.bitrate) / 1e6)} Mb/s`);
          // The window decides the size: tell the phone if it is not this.
          configured = true;
          asked = "";
          askSize();
          break;
        }
        case "clip":
          if (typeof m.text === "string") fromGuest(m.text);
          break;
        case "stats":
          phoneStats = { fps: Number(m.fps) || 0, kbps: Number(m.kbps) || 0, encodeMs: Number(m.encodeMs) || 0 };
          break;
        case "pong":
          if (typeof m.n === "number") rtt = Math.round(performance.now() - m.n);
          break;
      }
    };

    // Stats for the pill, once a second.
    const tick = window.setInterval(() => {
      const now = performance.now();
      const fps = Math.round((drawn * 1000) / Math.max(1, now - shownAt));
      drawn = 0;
      shownAt = now;
      if (up) {
        setStats({
          size: `${dw}×${dh}`,
          fps,
          mbps: Math.round(phoneStats.kbps / 100) / 10,
          encodeMs: phoneStats.encodeMs,
          rttMs: rtt,
          encoder,
        });
      }
    }, 1000);
    pingTimer = window.setInterval(() => send(JSON.stringify({ t: "ping", n: performance.now() })), 2000);

    canvas.addEventListener("pointermove", onPointer);
    canvas.addEventListener("pointerdown", onPointer);
    canvas.addEventListener("pointerup", onPointer);
    canvas.addEventListener("pointercancel", onPointer);
    canvas.addEventListener("wheel", onWheel, { passive: false });
    canvas.addEventListener("contextmenu", onContextMenu);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("keyup", onKeyUp);
    window.addEventListener("blur", releaseAll);
    window.addEventListener("resize", onResize);

    actions.current = {
      again: () => connect(true),
      fullscreen: () => void toggleFullscreen(),
      // The window keeps the switch, and says it moved.
      clipboard: () => void host.clipboard.then((c) => c.toggle()),
    };
    connect(start);

    return () => {
      live = false;
      window.clearInterval(tick);
      window.clearInterval(pingTimer);
      window.clearTimeout(settle);
      canvas.removeEventListener("pointermove", onPointer);
      canvas.removeEventListener("pointerdown", onPointer);
      canvas.removeEventListener("pointerup", onPointer);
      canvas.removeEventListener("pointercancel", onPointer);
      canvas.removeEventListener("wheel", onWheel);
      canvas.removeEventListener("contextmenu", onContextMenu);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("keyup", onKeyUp);
      window.removeEventListener("blur", releaseAll);
      window.removeEventListener("resize", onResize);
      gone();
      const s = ws;
      ws = null;
      s?.close();
      if (decoder && decoder.state !== "closed") decoder.close();
    };
  }, [url, wireless, start, host]);

  const covered = phase.kind !== "up";
  return (
    <div className="relative h-full w-full overflow-hidden bg-black">
      <canvas
        ref={canvasRef}
        tabIndex={0}
        className="absolute inset-0 h-full w-full outline-none"
        style={{ objectFit: "contain", imageRendering: "pixelated", touchAction: "none" }}
        aria-label={`The Linux desktop on ${name}`}
      />
      {covered && <UsbCover phase={phase} name={name} onAgain={() => actions.current.again()} />}
      {!covered && (
        <div
          className={`pointer-events-none absolute inset-x-0 top-2 flex justify-center transition-opacity duration-200 ${pill ? "opacity-100" : "opacity-0"}`}
        >
          <div className="glass pointer-events-auto flex items-center gap-3 px-3.5 py-1.5 text-[12px] text-slate-200 shadow-lg">
            <span className="h-2 w-2 rounded-full bg-emerald-400" aria-hidden="true" />
            <span className="font-semibold">{wireless ? "Wireless debugging" : "USB"}</span>
            {stats && (
              <span className="tabular-nums text-slate-400">
                {stats.size} · {stats.fps} fps · {stats.mbps} Mb/s · {stats.rttMs} ms
              </span>
            )}
            {share !== null && (
              <button
                className={`link-btn ${share ? "" : "muted"}`}
                onClick={() => actions.current.clipboard()}
                title={share ? "Clipboard shared with this computer" : "Clipboard not shared"}
                aria-pressed={share}
              >
                {share ? "Clipboard on" : "Clipboard off"}
              </button>
            )}
            <button className="link-btn" onClick={() => actions.current.fullscreen()} title="Fullscreen (F11)">
              Fullscreen
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function UsbCover({ phase, name, onAgain }: { phase: Exclude<Phase, { kind: "up" }>; name: string; onAgain: () => void }) {
  const busy = phase.kind === "connecting" || phase.kind === "starting";
  const copy: Record<typeof phase.kind, [string, string]> = {
    connecting: [`Reaching ${name} over USB…`, ""],
    starting: [`Starting the desktop on ${name}…`, "The first start can take a minute."],
    stopped: [`The desktop is not running on ${name}`, "It was shut down, or logged out of, on the phone."],
    failed: [`No desktop from ${name}`, phase.kind === "failed" ? phase.message : ""],
  };
  const [title, detail] = copy[phase.kind];
  return (
    <div className="fade-in absolute inset-0 flex items-center justify-center bg-[var(--bg)] p-6">
      <div className="glass flex w-[440px] max-w-full flex-col items-center gap-2 px-7 py-6 text-center">
        <p className="text-[15px] font-semibold text-slate-100">{title}</p>
        {detail && <p className="text-[12.5px] leading-relaxed text-slate-400">{detail}</p>}
        {busy ? (
          <span
            className="mt-2 h-5 w-5 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
            aria-hidden="true"
          />
        ) : (
          <div className="mt-3 flex gap-2">
            <button className="btn-accent" onClick={onAgain}>
              {phase.kind === "stopped" ? "Start it" : "Try again"}
            </button>
            <button className="btn-ghost" onClick={() => void getCurrentWindow().close()}>
              Close
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
