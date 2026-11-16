import { useEffect, useRef, useState } from "react";
import { Channel, invoke } from "@tauri-apps/api/core";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { readText, writeText } from "@tauri-apps/plugin-clipboard-manager";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { load, type Store } from "@tauri-apps/plugin-store";
import type { PutProgress, PutReport } from "../../lib/types";
import { ClipboardShare, type ToGuest } from "../remote/clipboardShare";
import { CopyCard, DesktopCover, DropCover, merge, Notice, type Copy, type DesktopHost } from "../remote/DesktopCards";
import { UsbDesktop } from "../remote/usb/UsbDesktop";

/**
 * A postmarketOS phone's desktop, in a window of its own (pmos.rs).
 *
 * The phone's helper speaks the messages the phone's Linux speaks over adb,
 * so the picture, the pointer and the keys are UsbDesktop's: H.264 decoded on
 * this computer's GPU, the pointer this computer's own. Only the way there is
 * different: a WebSocket on this computer's loopback that pmos.rs puts through
 * to the helper over SSH. The window lives as long as the session does;
 * pmos.rs closes it when the phone goes.
 *
 * What crosses between the two is this window's, as for the phone's Linux
 * (DesktopRouter.tsx): the clipboard, by clipboardShare.ts's rules, with its
 * switch on the pill, the phone being the guest; and files dropped on the
 * window, which go into the phone's Downloads and are then dropped on the app
 * they were let go over there (pmos_put).
 */

interface PmosLink {
  url: string;
  name: string;
  /** The phone's desktop lets the helper share its clipboard. */
  clipboard: boolean;
  /** …and drop files on its apps; otherwise a drop only lands in Downloads. */
  carry: boolean;
}

/**
 * The most of this computer's clipboard the phone is given at once. The
 * helper takes a message of up to 4 MB, JSON's escapes included, which can
 * double a text's size; this leaves room for that.
 */
const PHONE_CLIP_MAX = 1024 * 1024;

/** A drop waiting its turn: the files, and where on the desktop they were let go, if the phone can drop them there. */
interface Drop {
  paths: string[];
  at: [number, number] | null;
}

export function PmosDesktop({ id }: { id: string }) {
  const [link, setLink] = useState<PmosLink | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  /** Bumped to reach the phone again: a fresh socket, the same screen on the phone. */
  const [attempt, setAttempt] = useState(0);
  /** What the window does for the view: there once its effect has run, for what the phone can do. */
  const [host, setHost] = useState<DesktopHost | null>(null);
  /** The desktop is showing: a drop lands on it. */
  const [up, setUp] = useState(false);
  /** Files are being dragged over the window. */
  const [dragging, setDragging] = useState(false);
  const [copy, setCopy] = useState<Copy | null>(null);
  /** A sentence over the picture, in the window's own words. */
  const [notice, setNotice] = useState<string | null>(null);
  const actions = useRef({ cancelCopy: () => {}, closeCopy: () => {} });
  const name = link?.name ?? "the phone";
  /** What the phone can do, as a key: the effect below is the window's for as long as it stays the same. */
  const can = link ? `${link.clipboard ? "clipboard" : ""}+${link.carry ? "carry" : ""}` : null;

  useEffect(() => {
    let live = true;
    invoke<PmosLink>("pmos_window_link", { id })
      .then((l) => {
        if (!live) return;
        setLink(l);
        setFailed(null);
      })
      .catch((e) => live && setFailed(String(e)));
    return () => {
      live = false;
    };
  }, [id, attempt]);

  // The clipboard and drops: one closure per window, as long as it is open.
  useEffect(() => {
    if (can === null) return;
    const canClip = can.startsWith("clipboard");
    const canCarry = can.endsWith("carry");
    let live = true;
    const win = getCurrentWindow();
    /** This window is the one in front, which the clipboard goes by. */
    let focused = true;
    /** The desktop is showing: a drop goes onto it. */
    let showing = false;
    /** The guest's end of the clipboard: the view that has the desktop now. */
    let sink: ((msg: ToGuest) => void) | null = null;
    /** Where a point of the window is on the phone's desktop, while it shows. */
    let place: ((x: number, y: number) => [number, number]) | null = null;

    let noticeTimer = 0;
    const say = (text: string) => {
      window.clearTimeout(noticeTimer);
      setNotice(text);
      noticeTimer = window.setTimeout(() => live && setNotice(null), 5000);
    };

    // The clipboard, once the switch for this phone is known: nothing is
    // handed over before then, in case it is off. A phone whose desktop can't
    // share its clipboard gets no switch: a promise that never settles.
    const settingKey = `clipboard.pmos.${id}`;
    const crlf = navigator.userAgent.includes("Windows");
    const clipboard: Promise<ClipboardShare> = canClip
      ? (async () => {
          let on = true;
          let store: Store | null = null;
          try {
            store = await load("phone-desktop.json", { autoSave: false });
            const saved = await store.get<boolean>(settingKey);
            if (typeof saved === "boolean") on = saved;
          } catch (e) {
            logError(`pmos: cannot read the clipboard setting: ${String(e)}`);
          }
          return new ClipboardShare(
            {
              read: () => readText(),
              // Windows' own convention; a Mac and Linux keep the phone's.
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
                  .catch((e) => logError(`pmos: cannot save the clipboard setting: ${String(e)}`));
              },
            },
            on,
            { name: "the phone", max: PHONE_CLIP_MAX },
          );
        })()
      : new Promise<never>(() => {});
    if (!canClip) logInfo("pmos: this phone's desktop can't share its clipboard (no data-control)");

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
        if (payload && canClip) void clipboard.then((c) => c.focus());
      })
      .then((u) => (live ? (unlistenFocus = u) : u()));

    // Files dropped on the window: into the phone's Downloads, then onto the
    // app where they were let go. One copy at a time; a drop during one waits
    // its turn, and a cancel takes the waiting ones with it.
    const waiting: Drop[] = [];
    let copying = false;
    let copyTimer = 0;
    const copyAll = async () => {
      copying = true;
      window.clearTimeout(copyTimer);
      let total: PutReport | null = null;
      let outcome: Copy | null = null;
      while (live && waiting.length) {
        const drop = waiting.shift()!;
        setCopy({ kind: "copying", progress: null, waiting: waiting.length });
        const events = new Channel<PutProgress>((p) => {
          if (live) setCopy((c) => (c?.kind === "copying" ? { ...c, progress: p } : c));
        });
        try {
          const report = await invoke<PutReport>("pmos_put", { id, paths: drop.paths, at: drop.at, events });
          total = merge(total, report);
          if (report.cancelled) waiting.length = 0;
          outcome = report.cancelled ? { kind: "stopped", report: total } : { kind: "done", report: total };
        } catch (e) {
          waiting.length = 0;
          logError(`pmos: copy: ${String(e)}`);
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

    // The webview's own drag and drop, which gives real paths and where the
    // drop was, in physical pixels of the page. Only a drag that carries files
    // covers the window.
    let unlistenDrag: (() => void) | null = null;
    getCurrentWebview()
      .onDragDropEvent(({ payload: d }) => {
        if (!live) return;
        if (d.type === "enter") setDragging(d.paths.length > 0);
        else if (d.type === "leave") setDragging(false);
        else if (d.type === "drop") {
          setDragging(false);
          if (!showing || d.paths.length === 0) return;
          const ratio = window.devicePixelRatio || 1;
          const at = canCarry && place ? place(d.position.x / ratio, d.position.y / ratio) : null;
          waiting.push({ paths: d.paths, at });
          if (copying) setCopy((c) => (c?.kind === "copying" ? { ...c, waiting: waiting.length } : c));
          else void copyAll();
        }
      })
      .then((u) => (live ? (unlistenDrag = u) : u()));

    actions.current = {
      cancelCopy: () => {
        waiting.length = 0;
        void invoke("pmos_put_cancel").catch(() => {});
      },
      closeCopy: () => {
        window.clearTimeout(copyTimer);
        setCopy(null);
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
      point: (map) => {
        place = map;
      },
    });

    return () => {
      live = false;
      window.clearTimeout(noticeTimer);
      window.clearTimeout(copyTimer);
      unlistenFocus?.();
      unlistenDrag?.();
      void clipboard.then((c) => c.dispose());
      if (copying) void invoke("pmos_put_cancel").catch(() => {});
    };
  }, [id, can]);

  if (failed) {
    return (
      <div className="relative h-screen">
        <DesktopCover
          name={name}
          state="failed"
          message={failed}
          via="usb"
          onAgain={() => setAttempt((n) => n + 1)}
          onClose={() => void getCurrentWindow().close()}
        />
      </div>
    );
  }
  if (!link || !host) return <div className="h-screen bg-black" />;
  return (
    <div className="relative h-screen overflow-hidden">
      <UsbDesktop
        key={attempt}
        url={link.url}
        route="USB"
        tier="cable"
        name={link.name}
        start
        smooth
        host={host}
        onLost={() => {
          // The socket went without the phone saying why. While the session
          // lasts the phone's screen is still there: reach it again. When the
          // session has ended, pmos.rs is closing this window anyway.
          logInfo("pmos: the desktop's socket closed; reaching the phone again");
          window.setTimeout(() => setAttempt((n) => n + 1), 800);
        }}
      />
      {notice && <Notice text={notice} />}
      {copy && (
        <div className="pointer-events-none absolute bottom-4 right-4 flex max-w-[calc(100%-2rem)] flex-col items-end gap-2">
          <CopyCard
            copy={copy}
            name={name}
            where={`Downloads on ${name}`}
            onCancel={() => actions.current.cancelCopy()}
            onClose={() => actions.current.closeCopy()}
          />
        </div>
      )}
      {dragging && (
        <DropCover
          name={name}
          open={up}
          busy={copy?.kind === "copying"}
          title={`Drop to send to ${name}`}
          how={
            link.carry
              ? "It goes to the app you drop it on, and into Downloads there. Nothing already there is replaced."
              : "It goes into Downloads there. Folders come with everything in them; nothing already there is replaced."
          }
        />
      )}
    </div>
  );
}
