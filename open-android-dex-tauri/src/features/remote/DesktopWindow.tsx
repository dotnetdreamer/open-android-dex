import { useEffect, useRef, useState } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { error as logError } from "@tauri-apps/plugin-log";
import type { DesktopEvent, DesktopOpened } from "../../lib/types";
import type { DesktopHost } from "./DesktopCards";

/**
 * A paired phone's Linux desktop through the tunnel (remote_desktop.rs): one
 * of the two ways its window shows it (DesktopRouter.tsx).
 *
 * The picture is the phone's own viewer page — dex.html, the one its Linux
 * window loads — served through the tunnel onto this computer's loopback and
 * shown in a frame. This page is the part of the window around it that is the
 * tunnel's: it reaches the phone, has the desktop started, says what is
 * happening while that takes a minute, and brings the picture back after the
 * network drops. The frame is another origin with no access to the app; the
 * two talk in messages, and the frame's half of that is in dex.js.
 *
 * What crosses between this computer and the guest is the window's to do,
 * never the frame's (DesktopRouter.tsx): the frame only carries the guest's
 * end of the clipboard, and asks for its switch to flip.
 */

/**
 * What the path can carry, in noVNC's two knobs: JPEG quality and zlib effort,
 * 0–9 each. noVNC's own defaults (6 and 2) suit a direct path. A relay is
 * shared, rate-limited and often far away, and there a picture that keeps up
 * beats a crisp one that does not.
 */
function qualityFor(direct: boolean, rttMs: number) {
  return !direct || rttMs >= 80 ? { quality: 3, compression: 6 } : { quality: 6, compression: 2 };
}

/** Shown on the frame's status dot. */
function describePath(direct: boolean, rttMs: number) {
  return `${direct ? "Direct" : "Through a relay"} · ${rttMs} ms`;
}

/** The wait before the nth try to come back: quick at first, then every 15 s. */
function backoff(attempt: number) {
  return attempt <= 1 ? 300 : Math.min(15_000, 1000 * 2 ** (attempt - 2));
}

type View =
  | { kind: "connecting" }
  | { kind: "starting" }
  | { kind: "up" }
  | { kind: "stopped" }
  | { kind: "failed"; message: string };

function Cover({
  view,
  name,
  onStart,
  onClose,
}: {
  view: Exclude<View, { kind: "up" }>;
  name: string;
  onStart: () => void;
  onClose: () => void;
}) {
  const busy = view.kind === "connecting" || view.kind === "starting";
  const copy: Record<typeof view.kind, [string, string]> = {
    connecting: [`Reaching ${name}…`, ""],
    starting: [`Starting the desktop on ${name}…`, "The first start can take a minute."],
    stopped: [`The desktop is not running on ${name}`, "It was shut down, or logged out of, on the phone."],
    failed: [`No desktop from ${name}`, view.kind === "failed" ? view.message : ""],
  };
  const [title, detail] = copy[view.kind];
  return (
    <div className="fade-in absolute inset-0 flex items-center justify-center p-6">
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
            <button className="btn-accent" onClick={onStart}>
              {view.kind === "stopped" ? "Start it" : "Try again"}
            </button>
            <button className="btn-ghost" onClick={onClose}>
              Close
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

export function DesktopWindow({ phoneId, name, host }: { phoneId: string; name: string; host: DesktopHost }) {
  const [view, setView] = useState<View>({ kind: "connecting" });
  /** The viewer page, and a key that gives each comeback a fresh frame. */
  const [frame, setFrame] = useState<{ url: string; key: number } | null>(null);
  /** Set while the picture has gone and the window is bringing it back: why, if known. */
  const [lost, setLost] = useState<string | null>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const actions = useRef({
    begin: () => {},
    now: () => {},
    close: () => {},
  });

  // Everything that changes without a repaint lives in this closure, made once
  // per window: the messages, the keys and the retries all read the same state.
  useEffect(() => {
    let live = true;
    let generation = 0;
    let attempt = 0;
    let authFailures = 0;
    let timer = 0;
    let origin = "";
    let frames = 0;
    let fullscreen = false;
    let path: { direct: boolean; rttMs: number } | null = null;
    /** Lets go of the clipboard's word for the guest, while a frame has it. */
    let detach: (() => void) | null = null;
    const win = getCurrentWindow();

    /** To the frame — addressed to its origin, so nothing else can be handed it. */
    const post = (msg: object) => {
      const target = frameRef.current?.contentWindow;
      if (target && origin) target.postMessage({ oadx: 1, ...msg }, origin);
    };
    const tellPath = () => {
      if (path) post({ ...qualityFor(path.direct, path.rttMs), link: describePath(path.direct, path.rttMs) });
    };

    const open = async (start: boolean): Promise<DesktopOpened | null> => {
      const mine = ++generation;
      const events = new Channel<DesktopEvent>((e) => {
        if (!live) return;
        if (e.kind === "path") {
          path = { direct: e.direct, rttMs: e.rttMs };
          tellPath();
        } else if (mine === generation) {
          setView((v) => (v.kind === "up" ? v : { kind: "starting" }));
        }
      });
      let opened: DesktopOpened;
      try {
        opened = await invoke<DesktopOpened>("remote_desktop_connect", {
          id: phoneId,
          // The window's size in logical pixels, if the desktop has to be
          // started. After that it follows the window: the viewer asks the
          // guest to match every resize (resizeSession, dex.js).
          width: Math.round(window.innerWidth),
          height: Math.round(window.innerHeight),
          start,
          events,
        });
      } catch (e) {
        // Refused before the phone was asked — forgotten on this computer,
        // say. Waiting will not change that.
        opened = { kind: "failed", message: String(e), again: false };
      }
      return live && mine === generation ? opened : null;
    };

    const show = (url: string) => {
      origin = new URL(url).origin;
      frames += 1;
      host.showing(true);
      setFrame({ url, key: frames });
      setView({ kind: "up" });
    };

    const drop = (next: View) => {
      detach?.();
      detach = null;
      setLost(null);
      setFrame(null);
      origin = "";
      host.showing(false);
      setView(next);
    };

    /** Open it, starting it if it is not running: the window's first act, and its buttons'. */
    const begin = async () => {
      window.clearTimeout(timer);
      timer = 0;
      attempt = 0;
      authFailures = 0;
      drop({ kind: "connecting" });
      const opened = await open(true);
      if (!opened) return;
      if (opened.kind === "up") show(opened.url);
      else if (opened.kind === "stopped") drop({ kind: "stopped" });
      else {
        logError(`remote: desktop: ${opened.message}`);
        drop({ kind: "failed", message: opened.message });
      }
    };

    /**
     * The picture has gone. Come back to the desktop if it is still there —
     * and only then: a desktop someone logged out of on the phone is not
     * started again behind them.
     */
    const comeBack = (why: string | null, wait?: number) => {
      if (timer) return;
      setLost(why ?? "");
      attempt += 1;
      timer = window.setTimeout(async () => {
        timer = 0;
        const opened = await open(false);
        if (!opened) return;
        if (opened.kind === "up") show(opened.url); // `lost` clears when the new frame connects
        else if (opened.kind === "stopped") drop({ kind: "stopped" });
        else if (opened.again) comeBack(opened.message);
        else drop({ kind: "failed", message: opened.message });
      }, wait ?? backoff(attempt));
    };

    const toggleFullscreen = async () => {
      try {
        await win.setFullscreen(!(await win.isFullscreen()));
      } catch (e) {
        logError(`remote: desktop fullscreen: ${String(e)}`);
      }
    };

    const onMessage = (e: MessageEvent) => {
      const source = frameRef.current?.contentWindow;
      if (!source || e.source !== source || e.origin !== origin) return;
      const m = e.data;
      if (!m || m.oadx !== 1) return;
      if (m.fullscreen === "toggle") void toggleFullscreen();
      // The frame only asks: the window keeps the switch, and says it moved.
      if (m.share === "toggle") void host.clipboard.then((c) => c.toggle());
      if (typeof m.clip === "string") {
        const text: string = m.clip;
        void host.clipboard.then((c) => c.fromGuest(text));
      }
      switch (m.status) {
        case "connected":
          attempt = 0;
          authFailures = 0;
          setLost(null);
          tellPath();
          post({ fullscreen });
          frameRef.current?.focus();
          post({ focus: true });
          detach?.();
          detach = host.attach(post);
          void host.clipboard.then((c) => c.connected());
          break;
        case "closed":
        case "broke":
          comeBack(null);
          break;
        case "failed":
          // Xvnc refused the password: Linux was reinstalled since this page
          // was handed one. Once more, with whatever the phone says now.
          authFailures += 1;
          if (authFailures > 1) {
            drop({ kind: "failed", message: "The desktop did not accept its password. Open Linux on the phone once, then try again." });
          } else {
            comeBack(null);
          }
          break;
      }
    };

    // F11 while this page has the keys — over the cover, or before the frame
    // has been clicked. Inside the frame, dex.js passes it up instead.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "F11" || e.ctrlKey || e.altKey || e.metaKey || e.shiftKey) return;
      e.preventDefault();
      if (!e.repeat) void toggleFullscreen();
    };

    window.addEventListener("message", onMessage);
    window.addEventListener("keydown", onKey);
    // However fullscreen changed — F11, the frame's button, the green button
    // on a Mac — the frame folds its bar away for it. Asked once a resize has
    // settled: a window being dragged larger is a stream of them.
    let unlisten: (() => void) | null = null;
    let settle = 0;
    win
      .onResized(() => {
        window.clearTimeout(settle);
        settle = window.setTimeout(async () => {
          const now = await win.isFullscreen().catch(() => fullscreen);
          if (live && now !== fullscreen) {
            fullscreen = now;
            post({ fullscreen });
          }
        }, 150);
      })
      .then((u) => (live ? (unlisten = u) : u()));

    actions.current = {
      begin: () => void begin(),
      now: () => {
        window.clearTimeout(timer);
        timer = 0;
        comeBack(null, 0);
      },
      close: () => void win.close(),
    };
    void begin();

    return () => {
      live = false;
      window.clearTimeout(timer);
      window.clearTimeout(settle);
      window.removeEventListener("message", onMessage);
      window.removeEventListener("keydown", onKey);
      unlisten?.();
      detach?.();
      host.showing(false);
    };
  }, [phoneId, host]);

  return (
    <div className="relative h-full w-full overflow-hidden bg-[var(--bg)]">
      {frame && (
        <iframe
          key={frame.key}
          ref={frameRef}
          src={frame.url}
          title={`The Linux desktop on ${name}`}
          className="absolute inset-0 h-full w-full border-0"
          // Scripts, and its own origin for its modules and its WebSocket.
          // Nothing else: no popups, and never this window's navigation.
          sandbox="allow-scripts allow-same-origin"
          onLoad={() => frameRef.current?.focus()}
        />
      )}
      {view.kind !== "up" && (
        <Cover view={view} name={name} onStart={() => actions.current.begin()} onClose={() => actions.current.close()} />
      )}
      {view.kind === "up" && lost !== null && (
        <div className="fade-in pointer-events-none absolute inset-x-0 top-3 flex justify-center px-4">
          <div className="pointer-events-auto flex max-w-full items-center gap-3 rounded-xl border border-white/10 bg-black/85 px-4 py-2 text-[12.5px] text-slate-200 shadow-lg">
            <span
              className="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
              aria-hidden="true"
            />
            <span className="shrink-0">Reconnecting to {name}…</span>
            {lost && <span className="truncate text-amber-300/90">{lost}</span>}
            <button className="link-btn shrink-0" onClick={() => actions.current.now()}>
              Try now
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
