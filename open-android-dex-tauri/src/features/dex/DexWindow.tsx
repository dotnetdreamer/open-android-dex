import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { readText, writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { IS_MAC } from "../../lib/host";
import type { DisplayEvent, MirrorEvent } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Stage } from "../../ui/Stage";
import { ClipboardShare } from "../remote/clipboardShare";
import { StreamDecoder } from "../remote/usb/decoder";
import { FrameClock, median } from "../remote/usb/latency";
import { bitrateFor, type Tier } from "../remote/usb/UsbDesktop";
import {
  keyDown,
  KEYCODE_BACK,
  KEYCODE_FORWARD,
  KEYCODE_PASTE,
  META_SHIFT,
  metaOf,
  mouseButtons,
  wheelNotches,
} from "./androidKeys";

/**
 * The phone's Android desktop, in a window of this app's own — the desktop's
 * own video path (doc/dex-video.md), with scrcpy's window the fallback.
 *
 * The phone's side (openandroiddex-wmd, Desktop) makes the desktop's display
 * and sends it as H.264 at the pace the link takes it: when the link falls
 * behind it sends fewer frames, never older ones, and the bitrate follows what
 * gets through. This decodes it on the computer's GPU and draws it one phone
 * pixel to one screen pixel; the display is the window's size, in the screen's
 * own pixels, and follows it. The pointer is this computer's own — the phone is
 * told where it is — so it moves at the screen's rate whatever the video does.
 *
 * What only the computer knows comes from dex.rs: the WebSocket's address
 * (this computer's loopback, the window's own token), the phone's name, the
 * route, the bitrate picked in the phone's Settings, and whether the
 * clipboard is shared. The session itself is scrcpy.rs's, as scrcpy's window
 * is: closing this window ends the desktop; the cable coming and going is a
 * reconnect, which this window waits through.
 */

/** Messages from the phone (DesktopSession.java has the whole wire). */
const MSG_VIDEO = 0x01;
const ACK = new Uint8Array([0x12]);
const IN_POINTER = 0x20;
const IN_SCROLL = 0x21;
const IN_KEY = 0x22;
const IN_TEXT = 0x23;
/** The display sizes the phone takes (DesktopSession.java). */
const MIN_W = 320;
const MIN_H = 240;
const MAX_W = 4096;
const MAX_H = 4096;
/** How often the window says what its stream is doing in the app's log. */
const LOG_EVERY_MS = 30_000;

/** What dex.rs tells the window about its desktop. */
interface DexLink {
  url: string;
  name: string;
  /** How the computer reaches the phone, as the pill says it: USB, Wi-Fi. */
  route: string;
  wireless: boolean;
  /** A bit rate picked in the phone's Settings (or "Reduce quality"'s cap), bits a second; null for automatic. */
  bitrate: number | null;
  fps: number;
  /** Settings → Clipboard on the phone. */
  clipboard: boolean;
}

type Phase =
  | { kind: "connecting" }
  | { kind: "up" }
  | { kind: "reconnecting" }
  | { kind: "failed"; message: string };

interface Stats {
  size: string;
  fps: number;
  mbps: number;
  latency: number | null;
  rtt: number;
}

/** The display's size for a window: its own, in the screen's pixels, held even and within what the phone takes. */
function displaySize(): [number, number] {
  const dpr = window.devicePixelRatio || 1;
  const fit = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.round(v * dpr))) & ~1;
  return [fit(window.innerWidth, MIN_W, MAX_W), fit(window.innerHeight, MIN_H, MAX_H)];
}

export function DexWindow({ sessionKey }: { sessionKey: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [phase, setPhase] = useState<Phase>({ kind: "connecting" });
  const [name, setName] = useState("your phone");
  const [route, setRoute] = useState("");
  const [wireless, setWireless] = useState(false);
  const [stats, setStats] = useState<Stats | null>(null);
  const [pill, setPill] = useState(true);
  const [dropping, setDropping] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const actions = useRef({ again: () => {}, classic: () => {}, fullscreen: () => {} });

  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d", { alpha: false, desynchronized: true })!;
    const win = getCurrentWindow();
    let live = true;
    let ws: WebSocket | null = null;
    let link: DexLink | null = null;
    /** A picture is on screen: input goes to the phone from here on. */
    let up = false;
    let configured = false;
    let focused = true;

    // ── the picture ──
    let dw = 0;
    let dh = 0;
    let cw = 0;
    let ch = 0;
    let encoder = "";
    let drawn = 0;
    let shownAt = performance.now();
    let phone = { fps: 0, kbps: 0 };
    let rtt = 0;
    const clock = new FrameClock();
    let ages: number[] = [];
    let logged = { at: performance.now(), frames: 0, ages: [] as number[], rtts: [] as number[], kbps: [] as number[], hidden: false };

    const send = (m: string | Uint8Array) => {
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(m);
    };
    const decoder = new StreamDecoder({
      draw: (frame) => {
        if (live && dw > 0) {
          // Resized with the first picture at the new size in hand: setting a
          // canvas's size clears it, and the old picture should stay up until
          // there is a new one to replace it.
          if (canvas.width !== dw || canvas.height !== dh) {
            canvas.width = dw;
            canvas.height = dh;
          }
          ctx.drawImage(frame, 0, 0, cw || frame.displayWidth, ch || frame.displayHeight, 0, 0, dw, dh);
          drawn++;
          const age = clock.age(frame.timestamp, performance.now());
          if (age !== null) ages.push(age);
          if (!up) {
            up = true;
            setPhase({ kind: "up" });
            showPill(2500);
            canvas.focus();
          }
        }
        frame.close();
      },
      askKey: () => send(JSON.stringify({ t: "keyframe" })),
      error: (message) => logError(`dex: decoder: ${message}`),
    });

    // ── the status pill: down when the pointer rests at the top edge ──
    let pillShown = true;
    let pillTimer = 0;
    const showPill = (forMs: number) => {
      pillShown = true;
      setPill(true);
      window.clearTimeout(pillTimer);
      pillTimer = window.setTimeout(() => {
        if (live && pillShown) {
          pillShown = false;
          setPill(false);
        }
      }, forMs);
    };

    // ── the pointer ──
    let held = 0;
    let lastX = 0;
    let lastY = 0;
    let sideDown = { back: false, forward: false };
    const toDisplay = (clientX: number, clientY: number): [number, number] => {
      const r = canvas.getBoundingClientRect();
      if (!dw || !r.width) return [0, 0];
      const s = Math.min(r.width / dw, r.height / dh);
      const ox = (r.width - dw * s) / 2;
      const oy = (r.height - dh * s) / 2;
      const x = Math.floor((clientX - r.left - ox) / s);
      const y = Math.floor((clientY - r.top - oy) / s);
      return [Math.max(0, Math.min(dw - 1, x)), Math.max(0, Math.min(dh - 1, y))];
    };
    const pointer = (x: number, y: number, buttons: number, meta: number) => {
      lastX = x;
      lastY = y;
      const b = new Uint8Array(10);
      const v = new DataView(b.buffer);
      b[0] = IN_POINTER;
      b[1] = buttons;
      v.setUint16(2, x);
      v.setUint16(4, y);
      v.setUint32(6, meta >>> 0);
      send(b);
    };
    const press = (code: number) => {
      key(true, code, 0, 0);
      key(false, code, 0, 0);
    };
    const onPointer = (e: PointerEvent) => {
      if (!up) return;
      const [x, y] = toDisplay(e.clientX, e.clientY);
      const m = mouseButtons(e.buttons, e.shiftKey);
      if (e.type === "pointerdown") {
        canvas.setPointerCapture(e.pointerId);
        canvas.focus();
      }
      if ((e.type === "pointerup" || e.type === "pointercancel") && canvas.hasPointerCapture(e.pointerId)) {
        canvas.releasePointerCapture(e.pointerId);
      }
      // A mouse's side buttons are Back and Forward, as a real one's are on Android.
      if (m.back && !sideDown.back) press(KEYCODE_BACK);
      if (m.forward && !sideDown.forward) press(KEYCODE_FORWARD);
      sideDown = { back: m.back, forward: m.forward };
      held = m.held;
      pointer(x, y, held, metaOf(e, IS_MAC));
      if (e.type === "pointermove" && !held) {
        const want = e.clientY <= 4 || (pillShown && e.clientY < 64);
        if (want !== pillShown) {
          pillShown = want;
          setPill(want);
        }
      }
      e.preventDefault();
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (!up) return;
      const [x, y] = toDisplay(e.clientX, e.clientY);
      const [h, v] = wheelNotches(e.deltaX, e.deltaY, e.deltaMode);
      if (!h && !v) return;
      const b = new Uint8Array(17);
      const d = new DataView(b.buffer);
      b[0] = IN_SCROLL;
      d.setUint16(1, x);
      d.setUint16(3, y);
      d.setFloat32(5, h);
      d.setFloat32(9, v);
      // Without Shift: the browser has already turned Shift+wheel sideways,
      // and an Android list that saw Shift as well would turn it back.
      d.setUint32(13, (metaOf(e, IS_MAC) & ~META_SHIFT) >>> 0);
      send(b);
    };
    const onContextMenu = (e: Event) => e.preventDefault();

    // ── keys ──
    /** Each key held, by where it is on the keyboard: the Android code it went down as. */
    const down = new Map<string, number>();
    const key = (isDown: boolean, code: number, repeat: number, meta: number) => {
      const b = new Uint8Array(9);
      const v = new DataView(b.buffer);
      b[0] = IN_KEY;
      b[1] = isDown ? 1 : 0;
      b[2] = Math.min(255, repeat);
      v.setUint16(3, code);
      v.setUint32(5, meta >>> 0);
      send(b);
    };
    const text = (t: string) => {
      const u = new TextEncoder().encode(t);
      const b = new Uint8Array(1 + u.length);
      b[0] = IN_TEXT;
      b.set(u, 1);
      send(b);
    };
    /**
     * A paste waits for the computer's clipboard to reach the phone first, and
     * whatever is typed meanwhile waits behind it, in order.
     */
    let pasting: Promise<void> | null = null;
    const inOrder = (f: () => void) => {
      if (pasting) pasting = pasting.then(f);
      else f();
    };
    const toggleFullscreen = async () => {
      try {
        await win.setFullscreen(!(await win.isFullscreen()));
      } catch (e) {
        logError(`dex: fullscreen: ${String(e)}`);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      const bare = !e.ctrlKey && !e.altKey && !e.metaKey && !e.shiftKey;
      if (e.key === "F11" && bare) {
        e.preventDefault();
        if (!e.repeat) void toggleFullscreen();
        return;
      }
      // Escape leaves fullscreen and goes no further, as it does for scrcpy's
      // window (doc/escape-leaves-fullscreen.md); otherwise it is Android's.
      if (e.key === "Escape" && bare && !e.repeat) {
        void win.isFullscreen().then((fs) => {
          if (fs) void win.setFullscreen(false);
          else if (up) inOrder(() => press(111));
        });
        e.preventDefault();
        return;
      }
      if (!up) return;
      const a = keyDown(e, IS_MAC);
      if (a.kind === "system") return;
      e.preventDefault();
      if (a.kind === "none") return;
      if (a.kind === "text") {
        inOrder(() => text(a.text));
        return;
      }
      const isPaste =
        a.code === KEYCODE_PASTE || (e.ctrlKey && e.code === "KeyV") || (e.shiftKey && e.code === "Insert");
      if (isPaste && clipboardShare) {
        const c = clipboardShare;
        const before = pasting ?? Promise.resolve();
        const mine = before.then(() => c.focus()).catch(() => {});
        pasting = mine;
        void mine.then(() => {
          if (pasting === mine) pasting = null;
        });
      }
      // With a Mac's Cmd held the keyup never comes for the other keys: those go
      // in as one press. So do Copy, Cut and Paste, which are one press anyway.
      const oneShot = (IS_MAC && e.metaKey) || a.code === KEYCODE_PASTE || a.code === 277 || a.code === 278;
      if (oneShot) {
        inOrder(() => {
          key(true, a.code, e.repeat ? 1 : 0, a.meta);
          key(false, a.code, 0, a.meta);
        });
        return;
      }
      const was = down.get(e.code);
      if (was !== undefined && was !== a.code) inOrder(() => key(false, was, 0, a.meta));
      down.set(e.code, a.code);
      inOrder(() => key(true, a.code, e.repeat ? 1 : 0, a.meta));
    };
    const onKeyUp = (e: KeyboardEvent) => {
      if (!up) return;
      e.preventDefault();
      const code = down.get(e.code);
      if (code !== undefined) {
        down.delete(e.code);
        inOrder(() => key(false, code, 0, metaOf(e, IS_MAC)));
      }
    };
    const releaseAll = () => {
      for (const code of down.values()) key(false, code, 0, 0);
      down.clear();
      if (held) {
        held = 0;
        pointer(lastX, lastY, 0, 0);
      }
    };

    // ── the clipboard: this computer's when the window comes to the front, the phone's while it is there ──
    const crlf = navigator.userAgent.includes("Windows");
    let clipboardShare: ClipboardShare | null = null;
    const setUpClipboard = (on: boolean) => {
      clipboardShare?.dispose();
      clipboardShare = new ClipboardShare(
        {
          read: () => readText(),
          write: (t) => writeText(crlf ? t.replace(/\n/g, "\r\n") : t),
          post: (msg) => {
            if ("paste" in msg) send(JSON.stringify({ t: "clip", text: msg.paste }));
          },
          focused: () => focused,
          say: (t) => {
            setNotice(t);
            window.setTimeout(() => live && setNotice(null), 5000);
          },
          save: () => {},
        },
        on,
        // A binder call carries about a megabyte; the text goes as UTF-8.
        { name: "the phone", max: 512 * 1024, size: (t) => new TextEncoder().encode(t).length },
      );
    };

    // ── the window's size is the display's ──
    let asked = "";
    let settle = 0;
    const askSize = () => {
      const [w, h] = displaySize();
      const want = `${w}x${h}`;
      if (!configured || want === asked || (w === dw && h === dh)) return;
      asked = want;
      send(JSON.stringify({ t: "resize", w, h }));
      if (link && link.bitrate === null) send(JSON.stringify({ t: "bitrate", bitrate: autoBitrate(w, h) }));
    };
    const onResize = () => {
      window.clearTimeout(settle);
      settle = window.setTimeout(askSize, 400);
    };
    const tier = (): Tier => (link?.wireless ? "wifi" : "cable");
    const autoBitrate = (w: number, h: number) => bitrateFor(w, h, tier());
    // A window dragged onto a screen of another scale has the same size in
    // points and a new one in pixels: resize fires on some hosts, not all.
    let scaleQuery: MediaQueryList | null = null;
    const watchScale = () => {
      scaleQuery?.removeEventListener("change", onScale);
      scaleQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
      scaleQuery.addEventListener("change", onScale);
    };
    const onScale = () => {
      watchScale();
      onResize();
    };

    // ── the connection ──
    let told = false;
    let retry = 0;
    const connect = async () => {
      window.clearTimeout(retry);
      if (!live) return;
      try {
        link = await invoke<DexLink>("dex_link", { key: sessionKey });
      } catch (e) {
        // The phone is between sessions (a reconnect after the cable came and
        // went): ask again shortly; the session's own events say when.
        retry = window.setTimeout(() => void connect(), 1500);
        return;
      }
      if (!live) return;
      setName(link.name);
      setRoute(link.route);
      setWireless(link.wireless);
      if (!clipboardShare) setUpClipboard(link.clipboard);
      ws?.close();
      up = false;
      configured = false;
      told = false;
      decoder.restart();
      const socket = new WebSocket(link.url);
      socket.binaryType = "arraybuffer";
      ws = socket;
      socket.onopen = () => {
        const [w, h] = displaySize();
        const bitrate = link!.bitrate ?? autoBitrate(w, h);
        socket.send(JSON.stringify({ t: "hello", v: 1, w, h, bitrate, fps: link!.fps, acks: true }));
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
          decoder.push(b[1], Number(v.getBigUint64(2)), b.subarray(10));
        }
      };
      socket.onclose = () => {
        if (socket !== ws || !live) return;
        ws = null;
        down.clear();
        held = 0;
        if (!told) {
          up = false;
          setPhase((p) => (p.kind === "failed" ? p : { kind: "reconnecting" }));
          retry = window.setTimeout(() => void connect(), 1000);
        }
      };
    };

    const onText = (m: Record<string, unknown>) => {
      switch (m.t) {
        case "video": {
          dw = Number(m.dw);
          dh = Number(m.dh);
          cw = Number(m.cw);
          ch = Number(m.ch);
          encoder = String(m.encoder ?? "");
          decoder.restart();
          logInfo(
            `dex: video ${dw}x${dh} from ${encoder}${m.hw ? "" : " (software)"}${m.lowLatency ? ", low latency" : ""}, ` +
              `${Math.round(Number(m.bitrate) / 1e6)} Mb/s, display ${m.display} at ${m.dpi} dpi`,
          );
          configured = true;
          asked = "";
          askSize();
          if (clipboardShare) clipboardShare.connected();
          break;
        }
        case "clip":
          if (typeof m.text === "string") void clipboardShare?.fromGuest(m.text);
          break;
        case "stats":
          phone = { fps: Number(m.fps) || 0, kbps: Number(m.kbps) || 0 };
          logged.kbps.push(phone.kbps);
          break;
        case "pong":
          if (typeof m.n === "number") {
            const now = performance.now();
            rtt = Math.round(now - m.n);
            logged.rtts.push(rtt);
            clock.pong(m.n, now, m.at);
          }
          break;
        case "state":
          if (m.state === "failed") {
            told = true;
            up = false;
            setPhase({ kind: "failed", message: typeof m.message === "string" ? m.message : "The phone stopped sending the desktop." });
          }
          break;
      }
    };

    // Once a second, for the pill; every half minute, for the log.
    const tick = window.setInterval(() => {
      const now = performance.now();
      const fps = Math.round((drawn * 1000) / Math.max(1, now - shownAt));
      logged.frames += drawn;
      logged.ages.push(...ages);
      drawn = 0;
      shownAt = now;
      const latency = median(ages);
      ages = [];
      if (up) {
        setStats({ size: `${dw}×${dh}`, fps, mbps: Math.round(phone.kbps / 100) / 10, latency: latency === null ? null : Math.round(latency), rtt });
      }
      // A hidden window's page is run late by the webview, and so are its
      // frames: half a minute of that is not a measurement of the link.
      if (document.visibilityState !== "visible") logged.hidden = true;
      if (now - logged.at >= LOG_EVERY_MS) {
        const secs = (now - logged.at) / 1000;
        if (up && logged.frames > 0 && !logged.hidden) {
          const lat = median(logged.ages);
          logInfo(
            `dex: ${dw}x${dh}, ${(logged.frames / secs).toFixed(1)} fps shown, ${((median(logged.kbps) ?? 0) / 1000).toFixed(1)} Mb/s, ` +
              `latency ${lat === null ? "?" : Math.round(lat)} ms, link ${median(logged.rtts) ?? "?"} ms (${link?.route ?? "?"})`,
          );
        }
        logged = { at: now, frames: 0, ages: [], rtts: [], kbps: [], hidden: false };
      }
    }, 1000);
    const pingTimer = window.setInterval(() => send(JSON.stringify({ t: "ping", n: performance.now() })), 1000);

    // The session's own word: the phone gone and coming back, or a new display ready.
    const unStatus = listen<MirrorEvent>("mirror:status", ({ payload }) => {
      if (payload.sessionKey !== sessionKey) return;
      if (payload.status === "reconnecting") {
        up = false;
        setPhase({ kind: "reconnecting" });
      }
    });
    const unDisplay = listen<DisplayEvent>("mirror:display", ({ payload }) => {
      if (payload.sessionKey === sessionKey && !up) void connect();
    });

    // Focus: the clipboard goes by it, and nothing stays held when the window loses it.
    let unFocus: (() => void) | null = null;
    win.isFocused().then((f) => live && (focused = f)).catch(() => {});
    win
      .onFocusChanged(({ payload }) => {
        focused = payload;
        if (payload) void clipboardShare?.focus();
        else releaseAll();
      })
      .then((u) => (live ? (unFocus = u) : u()));

    // Files dropped on the window go to the phone's Downloads; an app (.apk) is installed.
    let unDrop: (() => void) | null = null;
    getCurrentWebview()
      .onDragDropEvent(({ payload }) => {
        if (payload.type === "enter" || payload.type === "over") setDropping(true);
        else if (payload.type === "leave") setDropping(false);
        else if (payload.type === "drop") {
          setDropping(false);
          if (payload.paths.length) {
            invoke("dex_drop", { key: sessionKey, paths: payload.paths }).catch((e) => logError(`dex: drop: ${String(e)}`));
          }
        }
      })
      .then((u) => (live ? (unDrop = u) : u()));

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
    watchScale();

    actions.current = {
      again: () => {
        setPhase({ kind: "connecting" });
        void connect();
      },
      classic: () => {
        invoke("dex_fallback", { key: sessionKey, reason: "asked from the window" }).catch((e) => logError(`dex: fallback: ${String(e)}`));
      },
      fullscreen: () => void toggleFullscreen(),
    };

    if (!StreamDecoder.available()) {
      // No WebCodecs here: scrcpy's window instead, which decodes for itself.
      logError("dex: this webview has no WebCodecs — the desktop goes to scrcpy's window");
      invoke("dex_fallback", { key: sessionKey, reason: "no WebCodecs in this webview" }).catch(() => {});
    } else {
      void connect();
    }

    return () => {
      live = false;
      window.clearInterval(tick);
      window.clearInterval(pingTimer);
      window.clearTimeout(settle);
      window.clearTimeout(retry);
      window.clearTimeout(pillTimer);
      unStatus.then((f) => f());
      unDisplay.then((f) => f());
      unFocus?.();
      unDrop?.();
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
      scaleQuery?.removeEventListener("change", onScale);
      clipboardShare?.dispose();
      const s = ws;
      ws = null;
      s?.close();
      decoder.close();
    };
  }, [sessionKey]);

  const covered = phase.kind !== "up";
  const via = wireless ? "wifi" : "usb";
  return (
    <div className="relative h-screen w-screen overflow-hidden bg-black">
      <canvas
        ref={canvasRef}
        tabIndex={0}
        className="absolute inset-0 h-full w-full outline-none"
        style={{ objectFit: "contain", touchAction: "none" }}
        aria-label={`The desktop on ${name}`}
      />
      {covered && (
        <div className="app-backdrop fade-in absolute inset-0 flex overflow-y-auto p-6">
          <div className="m-auto flex w-[460px] max-w-full flex-col items-center text-center">
            <div className="w-[min(300px,62vw)]">
              <Stage
                scene={phase.kind === "failed" ? "error" : "launching"}
                via={via}
                progress={1}
                stalled={phase.kind === "reconnecting"}
              />
            </div>
            <h1 className="display mt-5 !text-[21px] !leading-[27px]">
              {phase.kind === "connecting"
                ? `Opening the desktop on ${name}…`
                : phase.kind === "reconnecting"
                  ? `Waiting for ${name}…`
                  : `The desktop on ${name} stopped`}
            </h1>
            <p className="lede mt-2 select-text">
              {phase.kind === "reconnecting"
                ? wireless
                  ? "The Wi-Fi connection dropped. The desktop comes back by itself when the phone is reachable again."
                  : "The cable was unplugged, or the phone is reconnecting. Plug it back in and the desktop comes back."
                : phase.kind === "failed"
                  ? phase.message
                  : ""}
            </p>
            {phase.kind === "failed" ? (
              <div className="mt-6 flex gap-2.5">
                <button className="btn btn-primary btn-lg" onClick={() => actions.current.again()}>
                  <Icon name="refresh" size={16} />
                  Try again
                </button>
                <button className="btn btn-secondary btn-lg" onClick={() => actions.current.classic()}>
                  Use the classic window
                </button>
              </div>
            ) : (
              <span className="spinner spinner-lg mt-6" aria-hidden="true" />
            )}
          </div>
        </div>
      )}
      {!covered && (
        <div
          className={`pointer-events-none absolute inset-x-0 top-2 flex justify-center transition-opacity duration-200 ${pill ? "opacity-100" : "opacity-0"}`}
        >
          <div className="toast card-raised pointer-events-auto !w-auto max-w-full !items-center !gap-1 !rounded-full !py-1 !pl-3.5 !pr-1 text-[12px]">
            <span className="dot dot-ok mr-1" aria-hidden="true" />
            <span className="font-semibold">{route}</span>
            {stats && (
              <span className="ml-2 hidden tabular-nums text-[var(--ink-3)] sm:inline">
                {stats.size} · {stats.fps} fps · {stats.mbps} Mb/s
                {stats.latency !== null ? ` · ${stats.latency} ms` : ` · ${stats.rtt} ms`}
              </span>
            )}
            <span className="mx-1.5 h-4 w-px bg-[var(--line-2)]" aria-hidden="true" />
            <button
              className="btn btn-quiet btn-sm btn-icon !rounded-full"
              onClick={() => actions.current.fullscreen()}
              title="Full screen (F11)"
              aria-label="Full screen"
            >
              <Icon name="fullscreen" size={14} />
            </button>
          </div>
        </div>
      )}
      {dropping && !covered && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/65 p-6">
          <div className="flex w-[440px] max-w-full flex-col items-center gap-2 rounded-[var(--r-xl)] border-[1.5px] border-dashed border-[var(--accent)] bg-[var(--bg-overlay)]/95 px-10 py-9 text-center shadow-[0_0_0_6px_var(--accent-soft)]">
            <span className="tile tile-lg tile-accent mb-1">
              <Icon name="upload" size={22} />
            </span>
            <p className="heading text-[15px]">Drop to copy to {name}</p>
            <p className="body">Files go to Downloads on the phone. An app (.apk) is installed.</p>
          </div>
        </div>
      )}
      {notice && !covered && (
        <div className="pointer-events-none absolute inset-x-0 bottom-4 flex justify-center">
          <div className="toast card-raised !w-auto max-w-full text-[12.5px]">{notice}</div>
        </div>
      )}
    </div>
  );
}
