import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import type { PmosPhone } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { Centered } from "../apps/AppsPanel";

/**
 * A postmarketOS phone's apps, on the Apps tab: what its own launcher lists,
 * with its icons, and one click opens an app in a window of its own here, as
 * an Android phone's do (AppsPanel.tsx). The phone makes the app a screen of
 * its own the size of that window and puts the app on it full screen
 * (pmos.rs, "the phone's apps"); closing the window puts the app back on the
 * phone's own screen.
 */

interface PmosAppInfo {
  /** Its desktop file, without .desktop. */
  id: string;
  name: string;
  /** A data URL, SVG or PNG. */
  icon: string | null;
}

interface OpenApp {
  phone: string;
  app: string;
}

interface AppsEvent {
  open: OpenApp[];
  /** An app's window that went by itself, and why: never opened, or failed. */
  ended: { phone: string; app: string; name: string; message: string } | null;
}

type Say = (text: string, tone?: "ok" | "warn" | "bad" | "info", detail?: string) => void;

/** Each phone's list as it was last, so coming back to the tab draws it at once and only catches up. */
const kept = new Map<string, PmosAppInfo[]>();

/** The icon, as a small PNG for the app's window: its title bar and its taskbar button. */
async function pngOf(dataUrl: string): Promise<number[] | null> {
  try {
    const img = new Image();
    img.src = dataUrl;
    await img.decode();
    const px = 64;
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = px;
    canvas.getContext("2d")?.drawImage(img, 0, 0, px, px);
    const blob = await new Promise<Blob | null>((done) => canvas.toBlob(done, "image/png"));
    return blob ? Array.from(new Uint8Array(await blob.arrayBuffer())) : null;
  } catch {
    return null;
  }
}

export function PmosApps({ phone, say }: { phone: PmosPhone; say: Say }) {
  const name = phone.name || "the postmarketOS phone";
  const [apps, setApps] = useState<PmosAppInfo[] | null>(() => kept.get(phone.id) ?? null);
  const [failed, setFailed] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<OpenApp[]>([]);
  /** Apps a click is opening, until their window is up. */
  const [opening, setOpening] = useState<Set<string>>(() => new Set());
  const search = useRef<HTMLInputElement>(null);

  const refresh = useCallback(() => {
    const t0 = performance.now();
    invoke<PmosAppInfo[]>("pmos_apps", { id: phone.id })
      .then((list) => {
        logInfo(`pmos: ${list.length} apps from ${phone.id} in ${Math.round(performance.now() - t0)} ms`);
        kept.set(phone.id, list);
        setApps(list);
        setFailed(null);
      })
      .catch((e) => {
        logError(`pmos: listing ${phone.id}'s apps: ${String(e)}`);
        setFailed(String(e));
      });
  }, [phone.id]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Which apps have a window here, and one that went by itself saying why.
  useEffect(() => {
    invoke<OpenApp[]>("pmos_app_windows").then(setOpen).catch(() => {});
    const un = listen<AppsEvent>("pmos:apps", ({ payload }) => {
      setOpen(payload.open);
      const e = payload.ended;
      if (e && e.phone === phone.id && e.message) say(`${e.name} closed`, "warn", e.message);
    });
    return () => {
      un.then((f) => f());
    };
  }, [phone.id, say]);

  // Typing anywhere on the page searches, as on an Android phone's.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.key.length !== 1) return;
      const typing = document.activeElement;
      if (typing === search.current || typing instanceof HTMLInputElement || typing instanceof HTMLTextAreaElement) return;
      search.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const launch = useCallback(
    async (a: PmosAppInfo) => {
      setOpening((s) => new Set(s).add(a.id));
      logInfo(`pmos: opening ${a.id} on ${phone.id}`);
      try {
        const icon = a.icon ? await pngOf(a.icon) : null;
        await invoke("pmos_open_app", { id: phone.id, appId: a.id, name: a.name, icon });
      } catch (e) {
        logError(`pmos: opening ${a.id}: ${String(e)}`);
        say(`${a.name} couldn't open`, "bad", String(e));
      } finally {
        setOpening((s) => {
          const next = new Set(s);
          next.delete(a.id);
          return next;
        });
      }
    },
    [phone.id, say],
  );

  const shown = useMemo(() => {
    if (!apps) return [];
    const q = query.trim().toLowerCase();
    if (!q) return apps;
    return apps.filter((a) => a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q));
  }, [apps, query]);

  const openIds = useMemo(() => new Set(open.filter((o) => o.phone === phone.id).map((o) => o.app)), [open, phone.id]);

  return (
    <div className="flex min-h-full flex-col px-6 pb-8 pt-5">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="heading text-[17px]">Apps on {name}</h1>
          <p className="caption mt-0.5">
            Each app opens in a window of its own. Close it and the app is back on the phone's screen.
          </p>
        </div>
        <label className="relative">
          <Icon name="search" size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-[var(--ink-4)]" />
          <input
            ref={search}
            className="field w-[220px] pl-9"
            placeholder="Search apps"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setQuery("");
              if (e.key === "Enter" && shown.length > 0) void launch(shown[0]);
            }}
            aria-label="Search the phone's apps"
          />
        </label>
      </div>

      {failed && !apps ? (
        <Centered icon="alert" title="The phone's apps didn't load" action={{ label: "Try again", onClick: refresh }}>
          {failed}
        </Centered>
      ) : !apps ? (
        <Centered spinner title="Getting the phone's apps…">
          The first time takes a moment while Open Android DeX sets itself up on the phone.
        </Centered>
      ) : shown.length === 0 ? (
        <Centered icon="search" title={query.trim() ? `Nothing called “${query.trim()}”` : "No apps on the phone"}>
          {query.trim() ? "Try another name." : "The phone's launcher lists none."}
        </Centered>
      ) : (
        <div className="apps-grid" role="list">
          {shown.map((a) => (
            <button
              key={a.id}
              role="listitem"
              className="app-tile"
              onClick={() => void launch(a)}
              title={a.name}
              data-open={openIds.has(a.id) || undefined}
            >
              <span className="app-icon">
                {a.icon ? <img src={a.icon} alt="" draggable={false} /> : <span className="app-icon-blank" />}
                {opening.has(a.id) && <span className="spinner app-icon-spinner" aria-hidden="true" />}
              </span>
              <span className="app-label">{a.name}</span>
              <span className="app-dot" aria-hidden="true" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
