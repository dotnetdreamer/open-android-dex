import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { error as logError } from "@tauri-apps/plugin-log";
import { IS_LINUX, THIS_COMPUTER } from "../../lib/host";
import type { DeviceInfo } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { deviceName } from "../desktop/model";
import { KeepCopy } from "./KeepCopy";
import { ArrivalStrip, Sent } from "./Sent";
import { byDay, stripArrival, tzMinutes, type Arrival, type Photo, type PhotosView, type SendView } from "./model";

type Say = (text: string, tone?: "ok" | "warn" | "bad" | "info", detail?: string) => void;

/** How often the page says it is still looking: the computer's half forgets a page quiet for a minute. */
const STILL_LOOKING_MS = 30_000;

/**
 * The phone's newest photos and screenshots, here (src-tauri/src/send) — the
 * window's Photos tab. New ones show up by themselves; one click selects a
 * picture, and the bar at the foot opens it, saves it to Downloads, copies it
 * or shows it in its folder. Keep a copy, what the phone sent, and the
 * switches for "Send to computer" live here too.
 */
export function PhotosPanel({ device, say }: { device?: DeviceInfo; say: Say }) {
  const serial = device?.serial;
  const phone = device?.hardwareSerial ?? serial ?? "";
  const [view, setView] = useState<PhotosView | null>(null);
  const [send, setSend] = useState<SendView | null>(null);
  const [arrivals, setArrivals] = useState<Arrival[]>([]);
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [olderBusy, setOlderBusy] = useState(false);
  const [dismissed, setDismissed] = useState<Set<number>>(() => new Set());
  const [now, setNow] = useState(() => Date.now());

  // The page is looking: the phone looks once a second while it is. With the
  // window hidden or minimised it looks less often — only that: the list, older
  // pages and all, stands, and nothing is fetched afresh when it comes back.
  useEffect(() => {
    if (!serial) return;
    const look = () =>
      invoke("photos_watch", { serial, on: true, hidden: document.visibilityState === "hidden" }).catch(() => {});
    look();
    const t = window.setInterval(look, STILL_LOOKING_MS);
    document.addEventListener("visibilitychange", look);
    return () => {
      window.clearInterval(t);
      document.removeEventListener("visibilitychange", look);
      invoke("photos_watch", { serial, on: false }).catch(() => {});
    };
  }, [serial]);

  const list = useCallback(() => {
    if (!serial) return;
    invoke<PhotosView>("photos_list", { serial, tz: tzMinutes() })
      .then(setView)
      .catch((e) => logError(`photos: listing ${serial}: ${String(e)}`));
  }, [serial]);
  const sendState = useCallback(() => {
    if (!serial) return;
    invoke<SendView>("send_state", { serial }).then(setSend).catch(() => {});
  }, [serial]);
  const recent = useCallback(() => {
    invoke<Arrival[]>("send_recent").then(setArrivals).catch(() => {});
  }, []);

  useEffect(() => {
    list();
    sendState();
    recent();
  }, [list, sendState, recent]);

  useEffect(() => {
    let pending = 0;
    const unChanged = listen<{ phone: string }>("photos:changed", ({ payload }) => {
      if (payload.phone !== phone) return;
      // Several at once (a burst of screenshots) are one look.
      window.clearTimeout(pending);
      pending = window.setTimeout(list, 30);
    });
    const unThumb = listen<{ phone: string; id: number; url: string }>("photos:thumb", ({ payload }) => {
      if (payload.phone === phone) setThumbs((t) => ({ ...t, [String(payload.id)]: payload.url }));
    });
    const unSend = listen("send:state", () => {
      sendState();
      list();
    });
    const unArrived = listen("send:arrived", recent);
    return () => {
      window.clearTimeout(pending);
      unChanged.then((f) => f());
      unThumb.then((f) => f());
      unSend.then((f) => f());
      unArrived.then((f) => f());
    };
  }, [phone, list, sendState, recent]);

  // The arrival strip goes by itself once it is no longer fresh.
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(t);
  }, []);

  // Thumbnails are asked for as tiles come into view.
  const wanted = useRef<Set<number>>(new Set());
  const asked = useRef<Set<number>>(new Set());
  const flush = useRef(0);
  const want = useCallback(
    (id: number) => {
      if (!serial || asked.current.has(id)) return;
      wanted.current.add(id);
      window.clearTimeout(flush.current);
      flush.current = window.setTimeout(() => {
        const ids = [...wanted.current];
        wanted.current.clear();
        ids.forEach((i) => asked.current.add(i));
        invoke<Record<string, string>>("photos_thumb", { serial, ids })
          .then((have) => setThumbs((t) => ({ ...t, ...have })))
          .catch(() => ids.forEach((i) => asked.current.delete(i)));
      }, 60);
    },
    [serial],
  );
  // The link back after it dropped: thumbnails on their way then never came, so the tiles in view ask again.
  const [epoch, setEpoch] = useState(0);
  const wasLinked = useRef<boolean | null>(null);
  useEffect(() => {
    if (send === null) return;
    if (send.linked && wasLinked.current === false) {
      asked.current.clear();
      setEpoch((e) => e + 1);
    }
    wasLinked.current = send.linked;
  }, [send]);
  // A picture changed on the phone has a new thumbnail: asked for again.
  const lastModified = useRef<Map<number, number>>(new Map());
  useEffect(() => {
    for (const p of view?.items ?? []) {
      const was = lastModified.current.get(p.id);
      if (was !== undefined && was !== p.modified) {
        asked.current.delete(p.id);
        setThumbs((t) => {
          const n = { ...t };
          delete n[String(p.id)];
          return n;
        });
      }
      lastModified.current.set(p.id, p.modified);
    }
  }, [view]);
  const observer = useMemo(
    () =>
      new IntersectionObserver(
        (entries) => {
          for (const e of entries) {
            if (!e.isIntersecting) continue;
            const id = Number((e.target as HTMLElement).dataset.id);
            if (!Number.isNaN(id)) want(id);
          }
        },
        { rootMargin: "300px" },
      ),
    // A new observer for a link come back: each tile is observed again, and those in view ask.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [want, epoch],
  );
  useEffect(() => () => observer.disconnect(), [observer]);

  const items = view?.items ?? [];
  const days = useMemo(() => byDay(items), [items]);
  const chosen = items.find((p) => p.id === selected) ?? null;
  useEffect(() => {
    if (selected !== null && !items.some((p) => p.id === selected)) setSelected(null);
  }, [items, selected]);

  const photoAct = (p: Photo, what: "open" | "save" | "copy" | "show") => {
    if (!serial) return;
    setBusy(what);
    invoke<string>("photos_act", { serial, id: p.id, act: what })
      .then((said) => {
        if (said) say(said, "ok");
      })
      .catch((e) => say(`Couldn't ${what} ${p.name}`, "bad", why(String(e))))
      .finally(() => setBusy(null));
  };

  const older = () => {
    if (!serial) return;
    setOlderBusy(true);
    invoke<PhotosView>("photos_older", { serial, tz: tzMinutes() })
      .then(setView)
      .catch((e) => say("Couldn't load older photos", "bad", String(e)))
      .finally(() => setOlderBusy(false));
  };

  const strip = stripArrival(arrivals.filter((a) => a.phone === phone || !a.phone), dismissed, now);

  if (!device || !serial) {
    return (
      <Centered icon="phone" title="Connect your phone to see its photos">
        Plug it into {THIS_COMPUTER}, or connect over Wi‑Fi on the Desktop tab.
      </Centered>
    );
  }

  const name = deviceName(device);
  const state = view?.state ?? "loading";

  return (
    <div className={`flex min-h-full flex-col px-6 pt-5 ${chosen ? "pb-24" : "pb-8"}`}>
      <div className="mb-4">
        <h1 className="heading text-[17px]">Photos on {name}</h1>
        <p className="caption mt-0.5">New photos appear here automatically.</p>
      </div>

      <KeepCopy serial={serial} phone={phone} say={say} />

      {strip && (
        <div className="mt-3">
          <ArrivalStrip a={strip} send={send} say={say} onDismiss={() => setDismissed((d) => new Set(d).add(strip.id))} />
        </div>
      )}

      <div className="mt-5 flex flex-1 flex-col">
        {state === "loading" ? (
          <Centered spinner title="Getting your photos…">
            This can take a moment the first time.
          </Centered>
        ) : state === "failed" ? (
          <Centered icon="alert" title="Couldn't load photos" action={{ label: "Try again", onClick: () => invoke("photos_watch", { serial, on: true, again: true }).then(list) }}>
            {send?.refused
              ? "Open Android DeX didn't start on your phone. Unplug your phone, plug it back in, and try again."
              : view?.message || ""}
          </Centered>
        ) : items.length === 0 ? (
          <Centered icon="image" title="No photos yet">
            Take a photo or screenshot on your phone and it will appear here.
          </Centered>
        ) : (
          <>
            {days.map((d) => (
              <section key={d.day} className="mb-5" aria-label={d.day}>
                <h2 className="photo-day">{d.day}</h2>
                <div className="photos-grid" role="listbox" aria-label={`Photos from ${d.day}`}>
                  {d.items.map((p) => (
                    <Tile
                      key={p.id}
                      p={p}
                      src={thumbs[String(p.id)]}
                      selected={p.id === selected}
                      observer={observer}
                      onSelect={() => setSelected((s) => (s === p.id ? null : p.id))}
                      onOpen={() => photoAct(p, "open")}
                    />
                  ))}
                </div>
              </section>
            ))}
            {view?.more && (
              <div className="flex justify-center">
                <button type="button" className="btn btn-secondary" disabled={olderBusy} onClick={older}>
                  {olderBusy && <span className="spinner" aria-hidden="true" />}
                  Show older photos
                </button>
              </div>
            )}
          </>
        )}
      </div>

      <Sent serial={serial} send={send} arrivals={arrivals} say={say} onSetUp={sendState} />

      {chosen && (
        <div className="photo-bar card-raised" role="toolbar" aria-label={chosen.name}>
          <span className="min-w-0 flex-1 truncate text-[12.5px] text-[var(--ink-2)]" title={chosen.path}>
            {chosen.name}
          </span>
          <button type="button" className="btn btn-quiet btn-sm" disabled={busy !== null} onClick={() => photoAct(chosen, "open")}>
            {busy === "open" && <span className="spinner" aria-hidden="true" />}
            Open
          </button>
          <button type="button" className="btn btn-quiet btn-sm" disabled={busy !== null} onClick={() => photoAct(chosen, "save")}>
            {busy === "save" && <span className="spinner" aria-hidden="true" />}
            Save to Downloads
          </button>
          {!IS_LINUX && (
            <button type="button" className="btn btn-quiet btn-sm" disabled={busy !== null} onClick={() => photoAct(chosen, "copy")}>
              {busy === "copy" && <span className="spinner" aria-hidden="true" />}
              Copy
            </button>
          )}
          {chosen.here && (
            <button type="button" className="btn btn-quiet btn-sm" disabled={busy !== null} onClick={() => photoAct(chosen, "show")}>
              {send?.showInFolder ?? "Show in folder"}
            </button>
          )}
          <button type="button" className="btn btn-quiet btn-sm btn-icon" onClick={() => setSelected(null)} aria-label="Done" title="Done">
            <Icon name="x" size={14} />
          </button>
        </div>
      )}
    </div>
  );
}

/** The computer's half answers a photo it cannot fetch in one word: said in a sentence here. */
function why(e: string): string {
  switch (e) {
    case "gone":
      return "It isn't on your phone any more.";
    case "changed":
      return "It changed on your phone. Try again.";
    case "unreadable":
      return "Your phone couldn't read it.";
    case "lost":
      return "The connection to your phone was lost.";
    case "space":
      return `There isn't enough room on ${THIS_COMPUTER}.`;
    default:
      return e;
  }
}

function Tile({
  p,
  src,
  selected,
  observer,
  onSelect,
  onOpen,
}: {
  p: Photo;
  src?: string;
  selected: boolean;
  observer: IntersectionObserver;
  onSelect: () => void;
  onOpen: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el || src) return;
    observer.observe(el);
    return () => observer.unobserve(el);
  }, [observer, src]);
  return (
    <button
      ref={ref}
      type="button"
      role="option"
      aria-selected={selected}
      data-id={p.id}
      className="photo-tile"
      title={`${p.name} · ${p.album}`}
      onClick={onSelect}
      onDoubleClick={onOpen}
    >
      {src ? <img src={src} alt={p.name} draggable={false} /> : <span className="photo-blank" aria-hidden="true" />}
    </button>
  );
}

function Centered({
  icon,
  spinner,
  title,
  children,
  action,
}: {
  icon?: "phone" | "alert" | "image";
  spinner?: boolean;
  title: string;
  children: React.ReactNode;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-14 text-center">
      <span className="tile tile-lg mb-4">
        {spinner ? <span className="spinner" aria-hidden="true" /> : icon && <Icon name={icon} size={19} />}
      </span>
      <p className="heading">{title}</p>
      <p className="caption mt-1.5 max-w-[380px] select-text">{children}</p>
      {action && (
        <button className="btn btn-secondary mt-4" onClick={action.onClick}>
          <Icon name="refresh" size={15} />
          {action.label}
        </button>
      )}
    </div>
  );
}
