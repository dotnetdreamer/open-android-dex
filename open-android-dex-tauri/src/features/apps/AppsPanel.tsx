import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { THIS_COMPUTER } from "../../lib/host";
import type { DeviceInfo } from "../../lib/types";
import { Icon } from "../../ui/Icon";
import { deviceName } from "../desktop/model";

/**
 * The phone's apps, here — the window's Apps tab (src-tauri/src/apps).
 *
 * What the phone's own launcher lists, with its names and icons; one click
 * opens the app in a window of its own on this computer, beside everything
 * else on it, with no phone desktop around it. Nothing has to be set up: the
 * part on the phone starts the moment this page is looked at, so the first
 * click finds it ready.
 */

/** Where "open my apps here, not the desktop" is kept: this computer's choice, for every phone. */
const ON_CONNECT_KEY = "openandroiddex.onConnect";

/**
 * Whether a phone that connects opens its apps here instead of a desktop.
 * Off unless its owner turned it on: the desktop opening by itself on plug-in
 * is what everyone else expects, and nobody is asked which they want.
 */
export function opensAppsOnConnect(): boolean {
  try {
    return localStorage.getItem(ON_CONNECT_KEY) === "apps";
  } catch {
    return false;
  }
}

function setOpensAppsOnConnect(on: boolean) {
  try {
    if (on) localStorage.setItem(ON_CONNECT_KEY, "apps");
    else localStorage.removeItem(ON_CONNECT_KEY);
  } catch {
    // a webview without storage: the choice lasts as long as the window
  }
}

interface PhoneApp {
  /** The package, or "linux:<id>" for one of the phone's Linux apps. */
  pkg: string;
  label: string;
  system: boolean;
  kind: "android" | "linux";
  icon: string | null;
}

interface OpenWindow {
  id: number;
  serial: string;
  /**
   * The phone's ro.serialno. A window goes over the cable whenever the phone is
   * on one, so its serial need not be the one this page lists with.
   */
  phone: string;
  pkg: string;
  label: string;
}

type Load =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; apps: PhoneApp[] }
  | { kind: "failed"; message: string };

export function AppsPanel({
  device,
  say,
}: {
  /** The phone the apps are on: the one a desktop launch would use. */
  device?: DeviceInfo;
  say: (text: string, tone?: "ok" | "warn" | "bad" | "info", detail?: string) => void;
}) {
  const serial = device?.serial;
  const [load, setLoad] = useState<Load>({ kind: "idle" });
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState<OpenWindow[]>([]);
  /** The app a click is opening, until its window says it is up. */
  const [opening, setOpening] = useState<string | null>(null);
  const search = useRef<HTMLInputElement>(null);
  const attempt = useRef(0);
  const [appsFirst, setAppsFirst] = useState(opensAppsOnConnect);

  const refresh = useCallback(() => {
    if (!serial) {
      setLoad({ kind: "idle" });
      return;
    }
    const mine = ++attempt.current;
    setLoad((l) => (l.kind === "ready" ? l : { kind: "loading" }));
    const t0 = performance.now();
    invoke<PhoneApp[]>("apps_list", { serial })
      .then((apps) => {
        if (mine !== attempt.current) return;
        logInfo(`apps: ${apps.length} apps from ${serial} in ${Math.round(performance.now() - t0)} ms`);
        setLoad({ kind: "ready", apps });
      })
      .catch((e) => {
        if (mine !== attempt.current) return;
        logError(`apps: listing ${serial}: ${String(e)}`);
        setLoad({ kind: "failed", message: String(e) });
      });
  }, [serial]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Icons that were not here yet, as the phone draws them.
  useEffect(() => {
    const un = listen<{ serial: string; pkg: string; icon: string }>("apps:icon", ({ payload }) => {
      if (payload.serial !== serial) return;
      setLoad((l) =>
        l.kind !== "ready"
          ? l
          : { ...l, apps: l.apps.map((a) => (a.pkg === payload.pkg ? { ...a, icon: payload.icon } : a)) },
      );
    });
    return () => {
      un.then((f) => f());
    };
  }, [serial]);

  // Which apps have a window open, and what went wrong with one.
  useEffect(() => {
    invoke<OpenWindow[]>("apps_windows").then(setOpen).catch(() => {});
    const unWin = listen<OpenWindow[]>("apps:windows", ({ payload }) => {
      setOpen(payload);
      // A Linux app has no window until it maps one: the spinner waits for that.
      setOpening((o) => (o && payload.some((w) => w.pkg === o) ? null : o));
    });
    const unLive = listen<{ pkg: string; ms: number }>("apps:live", ({ payload }) => {
      logInfo(`apps: ${payload.pkg} on screen in ${payload.ms} ms`);
      setOpening((o) => (o === payload.pkg ? null : o));
    });
    const unProblem = listen<{ pkg: string; label: string; message: string }>("apps:problem", ({ payload }) => {
      setOpening((o) => (o === payload.pkg ? null : o));
      say(`${payload.label} couldn't open`, "bad", payload.message);
    });
    return () => {
      unWin.then((f) => f());
      unLive.then((f) => f());
      unProblem.then((f) => f());
    };
  }, [say]);

  // Typing anywhere on the page searches.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.key.length !== 1) return;
      if (document.activeElement === search.current) return;
      search.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const launch = useCallback(
    (a: PhoneApp) => {
      if (!serial) return;
      setOpening(a.pkg);
      logInfo(`apps: opening ${a.pkg} on ${serial}`);
      invoke("apps_open", { serial, pkg: a.pkg, device: device ? deviceName(device) : null }).catch((e) => {
        setOpening(null);
        logError(`apps: opening ${a.pkg}: ${String(e)}`);
        say(`${a.label} couldn't open`, "bad", String(e));
      });
      // An Android app's window shows at once; a Linux app's when the app maps it,
      // which a big one can take a few seconds to.
      window.setTimeout(() => setOpening((o) => (o === a.pkg ? null : o)), a.kind === "linux" ? 20000 : 1500);
    },
    [serial, device, say],
  );

  const shown = useMemo(() => {
    if (load.kind !== "ready") return [];
    const q = query.trim().toLowerCase();
    if (!q) return load.apps;
    return load.apps.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q));
  }, [load, query]);

  const phone = device?.hardwareSerial ?? serial;
  const openPkgs = useMemo(
    () => new Set(open.filter((w) => w.phone === phone || w.serial === serial).map((w) => w.pkg)),
    [open, phone, serial],
  );

  if (!device) {
    return (
      <Centered icon="phone" title="Connect your phone to see its apps">
        Plug it into {THIS_COMPUTER}, or connect over Wi‑Fi on the Desktop tab. Its apps appear here, and each one opens
        in a window of its own.
      </Centered>
    );
  }

  return (
    <div className="flex min-h-full flex-col px-6 pb-8 pt-5">
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="heading text-[17px]">Apps on {deviceName(device)}</h1>
          <p className="caption mt-0.5">Each app opens in a window of its own. Close it and the app is back on your phone.</p>
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
              if (e.key === "Enter" && shown.length > 0) launch(shown[0]);
            }}
            aria-label="Search your phone's apps"
          />
        </label>
      </div>

      {load.kind === "loading" || load.kind === "idle" ? (
        <Centered spinner title="Getting your apps…">
          The first time takes a moment while your phone gets ready.
        </Centered>
      ) : load.kind === "failed" ? (
        <Centered icon="alert" title="Your phone's apps didn't load" action={{ label: "Try again", onClick: refresh }}>
          {load.message}
        </Centered>
      ) : shown.length === 0 ? (
        <Centered icon="search" title={`Nothing called “${query.trim()}”`}>
          Try another name.
        </Centered>
      ) : (
        <>
        <div className="apps-grid" role="list">
          {shown.map((a) => (
            <button
              key={a.pkg}
              role="listitem"
              className="app-tile"
              onClick={() => launch(a)}
              title={a.kind === "linux" ? `${a.label} (Linux)` : a.label}
              data-open={openPkgs.has(a.pkg) || undefined}
            >
              <span className="app-icon">
                {a.icon ? <img src={a.icon} alt="" draggable={false} /> : <span className="app-icon-blank" />}
                {a.kind === "linux" && (
                  <span className="app-badge" title="Linux app">
                    <Icon name="terminal" size={10} strokeWidth={2.2} />
                  </span>
                )}
                {opening === a.pkg && <span className="spinner app-icon-spinner" aria-hidden="true" />}
              </span>
              <span className="app-label">{a.label}</span>
              <span className="app-dot" aria-hidden="true" />
            </button>
          ))}
        </div>
        <label className="apps-pref mt-6">
          <input
            type="checkbox"
            checked={appsFirst}
            onChange={(e) => {
              setAppsFirst(e.target.checked);
              setOpensAppsOnConnect(e.target.checked);
            }}
          />
          <span>
            <span className="heading block text-[12.5px]">Open my apps here when my phone connects</span>
            <span className="caption block">Instead of the desktop. You can still open the desktop from the Desktop tab.</span>
          </span>
        </label>
        </>
      )}
    </div>
  );
}

function Centered({
  icon,
  spinner,
  title,
  children,
  action,
}: {
  icon?: "phone" | "alert" | "search";
  spinner?: boolean;
  title: string;
  children: React.ReactNode;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-6 py-16 text-center">
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
