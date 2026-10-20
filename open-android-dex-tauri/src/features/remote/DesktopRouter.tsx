import { useEffect, useRef, useState } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { readText, writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { load, type Store } from "@tauri-apps/plugin-store";
import type { InboxEvent, PutProgress, PutReport, RemotePhone } from "../../lib/types";
import { ClipboardShare, type ToGuest } from "./clipboardShare";
import { CopyCard, DropCover, InboxCard, merge, Notice, type Copy, type DesktopHost, type Inbox } from "./DesktopCards";
import { DesktopWindow } from "./DesktopWindow";
import { UsbDesktop } from "./usb/UsbDesktop";

/**
 * A phone's desktop window, which reaches its phone one of two ways.
 *
 * - **Over adb** — the cable, or wireless debugging — as video from the
 *   phone's encoder (UsbDesktop, remote_usb.rs): a local desktop's frame
 *   rate and a pointer that never lags.
 * - **Through the tunnel**, anywhere else, with the phone's own viewer page
 *   (DesktopWindow, remote_desktop.rs).
 *
 * Adb wins whenever the phone is on it. A cable pulled mid-session drops the
 * window onto the tunnel — the desktop itself lives on the phone, so nothing
 * in it is lost — and while it is there the window watches for the phone to
 * come back onto adb, and moves to it.
 *
 * What crosses between this computer and the guest is the window's, whichever
 * way the picture comes, so it lives here and outlasts a move between the two:
 * the clipboard and its switch (clipboardShare.ts); files dropped on the
 * window, onto the guest's Desktop (remote_desktop_put); and what Linux sends
 * this computer, fetched into Downloads while the window is open
 * (remote_desktop_inbox). Files go through the tunnel either way, so a copy
 * under way carries on when the cable is pulled.
 */

/** remote_usb.rs's answer. */
interface UsbLink {
  url: string;
  wireless: boolean;
}

type Route =
  | { kind: "probing" }
  | { kind: "usb"; url: string; wireless: boolean; start: boolean; key: number }
  | { kind: "tunnel"; key: number };

/** How often the window on the tunnel looks for its phone on adb. Cheap: no process, a few ms per phone. */
const WATCH_MS = 3000;
/**
 * A stream over adb that keeps ending moments after it began is not a cable
 * pulled; it is something the window cannot fix. After this many in a row it
 * stays on the tunnel, and does not look at adb again for a while.
 */
const QUICK_LOSS_MS = 10_000;
const QUICK_LOSSES = 3;
const ADB_REST_MS = 30_000;

/** This webview decodes H.264 with WebCodecs. */
async function canDecode(): Promise<boolean> {
  if (typeof VideoDecoder === "undefined" || typeof EncodedVideoChunk === "undefined") return false;
  try {
    const r = await VideoDecoder.isConfigSupported({ codec: "avc1.640033", optimizeForLatency: true });
    return r.supported === true;
  } catch {
    return false;
  }
}

export function DesktopRouter({ phoneId }: { phoneId: string }) {
  const [name, setName] = useState("the phone");
  const [route, setRoute] = useState<Route>({ kind: "probing" });
  const routeRef = useRef(route);
  routeRef.current = route;
  const lost = useRef<() => void>(() => {});
  /** What the window does for the view that shows the desktop: there once the window's own effect has run. */
  const [host, setHost] = useState<DesktopHost | null>(null);
  /** The desktop is showing: a drop lands on it. */
  const [up, setUp] = useState(false);
  /** Files are being dragged over the window. */
  const [dragging, setDragging] = useState(false);
  const [copy, setCopy] = useState<Copy | null>(null);
  const [inbox, setInbox] = useState<Inbox | null>(null);
  /** A sentence over the picture, in the window's own words. */
  const [notice, setNotice] = useState<string | null>(null);
  const actions = useRef({
    cancelCopy: () => {},
    closeCopy: () => {},
    show: (_path: string) => {},
    closeInbox: () => {},
  });
  /** Where the inbox's channel delivers: the effect that is live now, whichever channel the app kept. */
  const onInbox = useRef<(e: InboxEvent) => void>(() => {});

  // Which way: adb or the tunnel, and moving between them.
  useEffect(() => {
    let live = true;
    let n = 0;
    let video = false;
    let busy = false;
    let usbSince = 0;
    let quickLosses = 0;
    let adbRestUntil = 0;
    const toUsb = (link: UsbLink, start: boolean) => {
      usbSince = Date.now();
      setRoute({ kind: "usb", ...link, start, key: ++n });
    };

    invoke<RemotePhone[]>("remote_phones")
      .then((list) => {
        const phone = list.find((p) => p.id === phoneId);
        if (phone && live) setName(phone.name);
      })
      .catch(() => {});

    const overAdb = async (wake: boolean): Promise<UsbLink | null> => {
      if (!video) return null;
      try {
        return await invoke<UsbLink | null>("remote_desktop_usb", { id: phoneId, wake });
      } catch (e) {
        logError(`remote: desktop over adb: ${String(e)}`);
        return null;
      }
    };
    const viaTunnel = () => setRoute((r) => (r.kind === "tunnel" ? r : { kind: "tunnel", key: ++n }));

    const first = async () => {
      video = await canDecode();
      if (!video) logInfo("remote: this webview has no H.264 decoder for the desktop's video; using the tunnel");
      const link = await overAdb(true);
      if (!live) return;
      if (link) toUsb(link, true);
      else viaTunnel();
    };

    // The stream went without the phone saying why. Still on adb — its
    // service restarted, say — it comes straight back the same way; gone from
    // it, the cable pulled, it goes through the tunnel.
    lost.current = async () => {
      quickLosses = Date.now() - usbSince < QUICK_LOSS_MS ? quickLosses + 1 : 0;
      if (quickLosses >= QUICK_LOSSES) {
        logError("remote: the desktop over adb keeps dropping; staying on the tunnel for now");
        quickLosses = 0;
        adbRestUntil = Date.now() + ADB_REST_MS;
        viaTunnel();
        return;
      }
      // Quick losses wait a little longer each time before looking again.
      if (quickLosses) await new Promise((r) => window.setTimeout(r, 500 * 2 ** (quickLosses - 1)));
      const link = await overAdb(false);
      if (!live) return;
      if (link) toUsb(link, false);
      else viaTunnel();
    };

    const watch = window.setInterval(async () => {
      if (!video || busy || routeRef.current.kind !== "tunnel" || Date.now() < adbRestUntil) return;
      busy = true;
      try {
        const link = await overAdb(false);
        if (live && link && routeRef.current.kind === "tunnel") {
          logInfo("remote: the phone is on adb; moving the desktop to it");
          toUsb(link, false);
        }
      } finally {
        busy = false;
      }
    }, WATCH_MS);

    void first();
    return () => {
      live = false;
      window.clearInterval(watch);
    };
  }, [phoneId]);

  // What crosses between this computer and the guest, whichever way: one
  // closure per window, as long as it is open.
  useEffect(() => {
    let live = true;
    const win = getCurrentWindow();
    /** This window is the one in front, which the clipboard goes by. */
    let focused = true;
    /** The desktop is showing: a drop goes onto it. */
    let showing = false;
    /** The guest's end of the clipboard: the view that has the desktop now. */
    let sink: ((msg: ToGuest) => void) | null = null;

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
          post: (msg) => sink?.(msg),
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
          if (showing && d.paths.length > 0) dropped(d.paths);
        }
      })
      .then((u) => (live ? (unlistenDrag = u) : u()));

    // What Linux sends: the app fetches it while this window is open, and
    // says so here.
    let inboxTimer = 0;
    onInbox.current = (e) => {
      if (!live) return;
      if (e.kind === "receiving") {
        window.clearTimeout(inboxTimer);
        setInbox({ kind: "receiving", name: e.name });
      } else {
        const { kind: _, ...got } = e;
        const card: Inbox = { kind: "received", got };
        setInbox(card);
        window.clearTimeout(inboxTimer);
        inboxTimer = window.setTimeout(() => live && setInbox((c) => (c === card ? null : c)), 12_000);
      }
    };
    const inboxEvents = new Channel<InboxEvent>((e) => onInbox.current(e));
    invoke("remote_desktop_inbox", { id: phoneId, events: inboxEvents }).catch((e) =>
      logError(`remote: desktop: what Linux sends: ${String(e)}`),
    );

    actions.current = {
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

    setHost({
      clipboard,
      attach: (mine) => {
        sink = mine;
        return () => {
          if (sink === mine) sink = null;
        };
      },
      showing: (now) => {
        showing = now;
        if (live) setUp(now);
      },
    });

    return () => {
      live = false;
      window.clearTimeout(noticeTimer);
      window.clearTimeout(copyTimer);
      window.clearTimeout(inboxTimer);
      unlistenFocus?.();
      unlistenDrag?.();
      void clipboard.then((c) => c.dispose());
      if (copying) void invoke("remote_desktop_put_cancel").catch(() => {});
    };
  }, [phoneId]);

  let view;
  if (host && route.kind === "usb") {
    view = (
      <UsbDesktop
        key={route.key}
        url={route.url}
        wireless={route.wireless}
        name={name}
        start={route.start}
        host={host}
        onLost={() => lost.current()}
      />
    );
  } else if (host && route.kind === "tunnel") {
    view = <DesktopWindow key={route.key} phoneId={phoneId} name={name} host={host} />;
  } else {
    view = (
      <div className="relative flex h-full w-full items-center justify-center overflow-hidden bg-[var(--bg)] p-6">
        <div className="glass flex w-[440px] max-w-full flex-col items-center gap-2 px-7 py-6 text-center">
          <p className="text-[15px] font-semibold text-slate-100">Reaching {name}…</p>
          <span
            className="mt-2 h-5 w-5 animate-spin rounded-full border-2 border-white/15 border-t-[var(--accent)]"
            aria-hidden="true"
          />
        </div>
      </div>
    );
  }

  return (
    <div className="relative h-full w-full overflow-hidden bg-[var(--bg)]">
      {view}
      {notice && <Notice text={notice} />}
      {(inbox || copy) && (
        <div className="pointer-events-none absolute bottom-4 right-4 flex max-w-[calc(100%-2rem)] flex-col items-end gap-2">
          {inbox && <InboxCard inbox={inbox} onShow={(p) => actions.current.show(p)} onClose={() => actions.current.closeInbox()} />}
          {copy && (
            <CopyCard copy={copy} name={name} onCancel={() => actions.current.cancelCopy()} onClose={() => actions.current.closeCopy()} />
          )}
        </div>
      )}
      {dragging && <DropCover name={name} open={up} busy={copy?.kind === "copying"} />}
    </div>
  );
}
