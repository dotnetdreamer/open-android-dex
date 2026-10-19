import { useEffect, useRef, useState } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { readText, writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError } from "@tauri-apps/plugin-log";
import { load, type Store } from "@tauri-apps/plugin-store";
import type { DesktopEvent, DesktopOpened, DesktopReceived, PutProgress, PutReport, RemotePhone } from "../../lib/types";
import { ClipboardShare } from "./clipboardShare";

/**
 * A paired phone's Linux desktop, in a window of its own (remote_desktop.rs).
 *
 * The picture is the phone's own viewer page — dex.html, the one its Linux
 * window loads — served through the tunnel onto this computer's loopback and
 * shown in a frame. This page is the window around it: it reaches the phone,
 * has the desktop started, says what is happening while that takes a minute,
 * and brings the picture back after the network drops. The frame is another
 * origin with no access to the app; the two talk in messages, and the frame's
 * half of that is in dex.js.
 *
 * What crosses between this computer and the guest is this page's to do,
 * never the frame's: the clipboard (clipboardShare.ts); files dropped on the
 * window, copied onto the guest's Desktop over SFTP in the same tunnel
 * (remote_desktop_put); and what Linux sends back — Send To, the dock's "Send
 * to computer" — which the app fetches into Downloads while this window is
 * open, and this page announces.
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

/** A byte count the way a file manager says one (as dexremote::sftp::size). */
function size(bytes: number) {
  if (bytes < 1000) return bytes === 1 ? "1 byte" : `${bytes} bytes`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1000;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

/** "report.pdf", "report.pdf and photos", "report.pdf and 2 more". */
function names(landed: string[]) {
  if (landed.length <= 2) return landed.join(" and ");
  return `${landed[0]} and ${landed.length - 1} more`;
}

/** What a copy did not bring along, in a line; empty when it brought everything. */
function leftOut(r: PutReport) {
  const parts: string[] = [];
  if (r.links) parts.push(r.links === 1 ? "1 link was left out" : `${r.links} links were left out`);
  if (r.unreadable) parts.push(r.unreadable === 1 ? "1 item could not be read" : `${r.unreadable} items could not be read`);
  return parts.join(" · ");
}

function merge(a: PutReport | null, b: PutReport): PutReport {
  if (!a) return b;
  return {
    ...b,
    landed: [...a.landed, ...b.landed],
    files: a.files + b.files,
    bytes: a.bytes + b.bytes,
    links: a.links + b.links,
    unreadable: a.unreadable + b.unreadable,
  };
}

/** What Linux sent, as the card in the corner shows it. */
type Inbox = { kind: "receiving"; name: string } | { kind: "received"; got: DesktopReceived };

type View =
  | { kind: "connecting" }
  | { kind: "starting" }
  | { kind: "up" }
  | { kind: "stopped" }
  | { kind: "failed"; message: string };

/** Files dropped on the window, as the card in its corner shows them. */
type Copy =
  | { kind: "copying"; progress: PutProgress | null; waiting: number }
  | { kind: "done"; report: PutReport }
  | { kind: "stopped"; report: PutReport }
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

/**
 * Over everything while files are dragged across the window. No blur: it lies
 * over a picture that repaints all the time.
 */
function DropCover({ name, open, busy }: { name: string; open: boolean; busy: boolean }) {
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-black/60 p-6">
      <div
        className={`flex max-w-full flex-col items-center gap-2 rounded-[var(--item-radius)] border-2 border-dashed bg-[#0d1117]/95 px-10 py-8 text-center ${
          open ? "border-[var(--accent)]" : "border-white/20"
        }`}
      >
        <svg
          viewBox="0 0 24 24"
          className={`h-9 w-9 ${open ? "text-[var(--accent)]" : "text-slate-500"}`}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M12 3.5v11m0 0-4.2-4.2M12 14.5l4.2-4.2" />
          <path d="M4 14.5v3.3A2.2 2.2 0 0 0 6.2 20h11.6a2.2 2.2 0 0 0 2.2-2.2v-3.3" />
        </svg>
        <p className="text-[15px] font-semibold text-slate-100">
          {open ? `Drop to copy to the Desktop on ${name}` : `The desktop on ${name} is not open yet`}
        </p>
        <p className="text-[12.5px] text-slate-400">
          {!open
            ? "Wait for it to open, then drop again."
            : busy
              ? "It is copied as soon as the copy in progress has finished."
              : "Folders come with everything in them. Nothing already there is replaced."}
        </p>
      </div>
    </div>
  );
}

/** The copy in the window's corner: how far, and the way to stop it. */
function CopyCard({ copy, name, onCancel, onClose }: { copy: Copy; name: string; onCancel: () => void; onClose: () => void }) {
  let title: string;
  let detail: string;
  let note = "";
  let done: number | null = null;
  if (copy.kind === "copying") {
    const p = copy.progress;
    title = `Copying to the Desktop on ${name}`;
    if (!p) detail = "Reaching the phone…";
    else if (p.filesTotal > 1) detail = `${p.current || "…"} · ${Math.min(p.filesDone + 1, p.filesTotal)} of ${p.filesTotal}`;
    else detail = p.current || "…";
    if (p) done = p.bytesTotal > 0 ? p.bytesDone / p.bytesTotal : p.filesTotal > 0 ? p.filesDone / p.filesTotal : 0;
    if (copy.waiting > 0) note = copy.waiting === 1 ? "Then 1 more drop" : `Then ${copy.waiting} more drops`;
  } else if (copy.kind === "done") {
    title = `Copied to the Desktop on ${name}`;
    detail = `${names(copy.report.landed)} · ${size(copy.report.bytes)}`;
    note = leftOut(copy.report);
  } else if (copy.kind === "stopped") {
    title = "Copy stopped";
    detail = copy.report.landed.length
      ? `${names(copy.report.landed)} stayed; what was in progress was taken back.`
      : "What was in progress was taken back; nothing was left on the phone.";
  } else {
    title = "The copy did not finish";
    detail = copy.message;
  }
  return (
    <div className="fade-in pointer-events-auto w-[360px] max-w-full rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">{title}</p>
          <p className={copy.kind === "failed" ? "mt-0.5 leading-relaxed text-amber-300/90" : "mt-0.5 truncate text-slate-400"}>
            {detail}
          </p>
          {note && <p className="mt-0.5 truncate text-slate-500">{note}</p>}
        </div>
        {copy.kind === "copying" ? (
          <button className="link-btn shrink-0" onClick={onCancel}>
            Cancel
          </button>
        ) : (
          <button className="link-btn muted shrink-0" onClick={onClose} aria-label="Close">
            ✕
          </button>
        )}
      </div>
      {done !== null && (
        <div className="mt-2.5 h-1 overflow-hidden rounded-full bg-white/10">
          <div className="h-full rounded-full bg-[var(--accent)] transition-[width] duration-200" style={{ width: `${Math.round(done * 100)}%` }} />
        </div>
      )}
    </div>
  );
}

/** What Linux sent: on its way, then saved, with the way to it. */
function InboxCard({ inbox, onShow, onClose }: { inbox: Inbox; onShow: (path: string) => void; onClose: () => void }) {
  if (inbox.kind === "receiving") {
    return (
      <div className="fade-in pointer-events-auto flex w-[360px] max-w-full items-center gap-3 rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
        <span
          className="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
          aria-hidden="true"
        />
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">Receiving from Linux…</p>
          <p className="mt-0.5 truncate text-slate-400">{inbox.name}</p>
        </div>
      </div>
    );
  }
  const got = inbox.got;
  const saved = got.landed.length > 0;
  const notes: string[] = [];
  if (got.links) notes.push(got.links === 1 ? "1 link was left out" : `${got.links} links were left out`);
  if (got.missing) notes.push(got.missing === 1 ? "1 item was gone from Linux" : `${got.missing} items were gone from Linux`);
  if (got.failed.length) notes.push(got.failed.length === 1 ? `Not saved: ${got.failed[0]}` : `${got.failed.length} could not be saved here`);
  return (
    <div className="fade-in pointer-events-auto w-[360px] max-w-full rounded-xl border border-white/10 bg-[#0d1117]/95 px-4 py-3 text-[12.5px] text-slate-200 shadow-lg">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold text-slate-100">{saved ? "Saved to Downloads" : "Nothing was saved"}</p>
          <p className="mt-0.5 truncate text-slate-400">
            {saved ? `${names(got.landed)} · ${size(got.bytes)}` : "What was sent had gone from Linux, or could not be read there."}
          </p>
          {notes.length > 0 && <p className="mt-0.5 truncate text-slate-500">{notes.join(" · ")}</p>}
        </div>
        {saved && got.first && (
          <button className="link-btn shrink-0" onClick={() => onShow(got.first!)}>
            Show
          </button>
        )}
        <button className="link-btn muted shrink-0" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
    </div>
  );
}

export function DesktopWindow({ phoneId }: { phoneId: string }) {
  const [name, setName] = useState("the phone");
  const [view, setView] = useState<View>({ kind: "connecting" });
  /** The viewer page, and a key that gives each comeback a fresh frame. */
  const [frame, setFrame] = useState<{ url: string; key: number } | null>(null);
  /** Set while the picture has gone and the window is bringing it back: why, if known. */
  const [lost, setLost] = useState<string | null>(null);
  /** Files are being dragged over the window. */
  const [dragging, setDragging] = useState(false);
  const [copy, setCopy] = useState<Copy | null>(null);
  const [inbox, setInbox] = useState<Inbox | null>(null);
  /** A sentence over the picture, in the window's own words. */
  const [notice, setNotice] = useState<string | null>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const actions = useRef({
    begin: () => {},
    now: () => {},
    close: () => {},
    cancelCopy: () => {},
    closeCopy: () => {},
    show: (_path: string) => {},
    closeInbox: () => {},
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
    /** The desktop is showing: a drop goes onto it. */
    let up = false;
    /** This window is the one in front, which the clipboard goes by. */
    let focused = true;
    const win = getCurrentWindow();

    /** To the frame — addressed to its origin, so nothing else can be handed it. */
    const post = (msg: object) => {
      const target = frameRef.current?.contentWindow;
      if (target && origin) target.postMessage({ oadx: 1, ...msg }, origin);
    };
    const tellPath = () => {
      if (path) post({ ...qualityFor(path.direct, path.rttMs), link: describePath(path.direct, path.rttMs) });
    };

    // What Linux sends: the app fetches it while this window is open, and
    // says so through the same channel as the path.
    let inboxTimer = 0;
    const received = (e: DesktopEvent) => {
      if (e.kind === "receiving") {
        window.clearTimeout(inboxTimer);
        setInbox({ kind: "receiving", name: e.name });
      } else if (e.kind === "received") {
        const { kind: _, ...got } = e;
        const card: Inbox = { kind: "received", got };
        setInbox(card);
        window.clearTimeout(inboxTimer);
        inboxTimer = window.setTimeout(() => live && setInbox((c) => (c === card ? null : c)), 12_000);
      }
    };

    let noticeTimer = 0;
    const say = (text: string) => {
      window.clearTimeout(noticeTimer);
      setNotice(text);
      noticeTimer = window.setTimeout(() => live && setNotice(null), 5000);
    };

    // The clipboard, once the switch for this phone is known: nothing is
    // handed to the guest before then, in case it is off.
    const settingKey = `clipboard.${phoneId}`;
    const crlf = navigator.userAgent.includes("Windows");
    const clipboard: Promise<ClipboardShare> = (async () => {
      let on = true;
      let store: Store | null = null;
      try {
        store = await load("phone-desktop.json", { autoSave: false });
        const saved = await store.get<boolean>(settingKey);
        if (typeof saved === "boolean") on = saved;
      } catch (e) {
        logError(`remote: desktop: cannot read the clipboard setting: ${String(e)}`);
      }
      return new ClipboardShare(
        {
          read: () => readText(),
          // Windows' own convention; a Mac and Linux keep the guest's.
          write: (text) => writeText(crlf ? text.replace(/\n/g, "\r\n") : text),
          post,
          focused: () => focused,
          say,
          save: (value) => {
            if (!store) return;
            const saving = store;
            saving
              .set(settingKey, value)
              .then(() => saving.save())
              .catch((e) => logError(`remote: desktop: cannot save the clipboard setting: ${String(e)}`));
          },
        },
        on,
      );
    })();

    const open = async (start: boolean): Promise<DesktopOpened | null> => {
      const mine = ++generation;
      const events = new Channel<DesktopEvent>((e) => {
        if (!live) return;
        if (e.kind === "path") {
          path = { direct: e.direct, rttMs: e.rttMs };
          tellPath();
        } else if (e.kind === "receiving" || e.kind === "received") {
          received(e);
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
      up = true;
      setFrame({ url, key: frames });
      setView({ kind: "up" });
    };

    const drop = (next: View) => {
      setLost(null);
      setFrame(null);
      origin = "";
      up = false;
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
      if (m.share === "toggle") void clipboard.then((c) => c.toggle());
      if (typeof m.clip === "string") {
        const text: string = m.clip;
        void clipboard.then((c) => c.fromGuest(text));
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
          void clipboard.then((c) => c.connected());
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

    // Files dropped on the window, onto the guest's Desktop. One copy at a
    // time; a drop during one waits its turn, and a cancel takes the waiting
    // ones with it.
    const waiting: string[][] = [];
    let copying = false;
    let copyTimer = 0;
    const copyAll = async () => {
      copying = true;
      window.clearTimeout(copyTimer);
      let total: PutReport | null = null;
      let outcome: Copy | null = null;
      while (live && waiting.length) {
        const paths = waiting.shift()!;
        setCopy({ kind: "copying", progress: null, waiting: waiting.length });
        const events = new Channel<PutProgress>((p) => {
          if (live) setCopy((c) => (c?.kind === "copying" ? { ...c, progress: p } : c));
        });
        try {
          const report = await invoke<PutReport>("remote_desktop_put", { id: phoneId, paths, events });
          total = merge(total, report);
          if (report.cancelled) waiting.length = 0;
          outcome = report.cancelled ? { kind: "stopped", report: total } : { kind: "done", report: total };
        } catch (e) {
          waiting.length = 0;
          logError(`remote: desktop: copy: ${String(e)}`);
          outcome = { kind: "failed", message: String(e) };
        }
      }
      copying = false;
      if (!live || !outcome) return;
      const last = outcome;
      setCopy(last);
      // A copy that went says so for a while; one that did not stays until closed.
      if (last.kind !== "failed") copyTimer = window.setTimeout(() => live && setCopy((c) => (c === last ? null : c)), 7000);
    };
    const dropped = (paths: string[]) => {
      waiting.push(paths);
      if (copying) setCopy((c) => (c?.kind === "copying" ? { ...c, waiting: waiting.length } : c));
      else void copyAll();
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

    // The window coming to the front is when the computer's clipboard goes
    // over: someone who copied something elsewhere is back to paste it.
    win
      .isFocused()
      .then((f) => {
        if (live) focused = f;
      })
      .catch(() => {});
    let unlistenFocus: (() => void) | null = null;
    win
      .onFocusChanged(({ payload }) => {
        focused = payload;
        if (payload) void clipboard.then((c) => c.focus());
      })
      .then((u) => (live ? (unlistenFocus = u) : u()));

    // The webview's own drag and drop, which sees the frame's rectangle too
    // and gives real paths. Only a drag that carries files covers the window.
    let unlistenDrag: (() => void) | null = null;
    getCurrentWebview()
      .onDragDropEvent(({ payload: d }) => {
        if (!live) return;
        if (d.type === "enter") setDragging(d.paths.length > 0);
        else if (d.type === "leave") setDragging(false);
        else if (d.type === "drop") {
          setDragging(false);
          if (up && d.paths.length > 0) dropped(d.paths);
        }
      })
      .then((u) => (live ? (unlistenDrag = u) : u()));

    invoke<RemotePhone[]>("remote_phones")
      .then((list) => {
        const phone = list.find((p) => p.id === phoneId);
        if (phone && live) setName(phone.name);
      })
      .catch(() => {});

    actions.current = {
      begin: () => void begin(),
      now: () => {
        window.clearTimeout(timer);
        timer = 0;
        comeBack(null, 0);
      },
      close: () => void win.close(),
      cancelCopy: () => {
        waiting.length = 0;
        void invoke("remote_desktop_put_cancel").catch(() => {});
      },
      closeCopy: () => {
        window.clearTimeout(copyTimer);
        setCopy(null);
      },
      show: (path) => {
        invoke("remote_desktop_reveal", { path }).catch((e) => logError(`remote: desktop: show: ${String(e)}`));
      },
      closeInbox: () => {
        window.clearTimeout(inboxTimer);
        setInbox(null);
      },
    };
    void begin();

    return () => {
      live = false;
      window.clearTimeout(timer);
      window.clearTimeout(settle);
      window.clearTimeout(noticeTimer);
      window.clearTimeout(copyTimer);
      window.clearTimeout(inboxTimer);
      window.removeEventListener("message", onMessage);
      window.removeEventListener("keydown", onKey);
      unlisten?.();
      unlistenFocus?.();
      unlistenDrag?.();
      void clipboard.then((c) => c.dispose());
      if (copying) void invoke("remote_desktop_put_cancel").catch(() => {});
    };
  }, [phoneId]);

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
      {notice && (
        <div className="fade-in pointer-events-none absolute inset-x-0 top-14 flex justify-center px-4">
          <div className="max-w-[560px] rounded-xl border border-white/10 bg-black/85 px-4 py-2 text-center text-[12.5px] text-slate-200 shadow-lg">
            {notice}
          </div>
        </div>
      )}
      {(inbox || copy) && (
        <div className="pointer-events-none absolute bottom-4 right-4 flex max-w-[calc(100%-2rem)] flex-col items-end gap-2">
          {inbox && <InboxCard inbox={inbox} onShow={(p) => actions.current.show(p)} onClose={() => actions.current.closeInbox()} />}
          {copy && (
            <CopyCard copy={copy} name={name} onCancel={() => actions.current.cancelCopy()} onClose={() => actions.current.closeCopy()} />
          )}
        </div>
      )}
      {dragging && <DropCover name={name} open={view.kind === "up"} busy={copy?.kind === "copying"} />}
    </div>
  );
}
