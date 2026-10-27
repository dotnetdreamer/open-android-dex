import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { error as logError, info as logInfo } from "@tauri-apps/plugin-log";
import { IS_MAC, THIS_COMPUTER, THIS_COMPUTER_CAP } from "../../lib/host";
import { Icon, type IconName } from "../../ui/Icon";
import { Popover, Switch } from "../../ui/kit";

/**
 * The phone's notifications as this computer's own — the bell in the title
 * bar (src-tauri/src/notifications, doc/phone-notifications.md).
 *
 * The bell says at a glance whether they are coming through: struck through
 * when they are off, a green dot while a phone's are arriving, an amber one
 * when something stands in the way; and it rings once as one arrives. Its
 * panel is the one place they are turned on and off — and what a toast may
 * say, whether calls ring here, the sound, whether dismissing one here clears
 * it on the phone, and each app's own switch, the apps listed as they notify.
 * "Send a test" has the phone post one, so the person sees the whole way work.
 */

interface PhoneState {
  phone: string;
  name: string;
  state: "starting" | "on" | "refused";
  message: string;
}

interface NotificationState {
  /** This computer can show them at all: Windows and macOS. */
  supported: boolean;
  on: boolean;
  content: boolean;
  sound: boolean;
  calls: boolean;
  clearOnPhone: boolean;
  /** What this computer's own notifications allow. */
  system: "ready" | "ask" | "off" | "unavailable";
  systemMessage: string;
  phones: PhoneState[];
}

interface NotifyingApp {
  pkg: string;
  label: string;
  icon: string | null;
  muted: boolean;
  /** Turned off for it in Windows' own settings. */
  blocked: boolean;
  last: number;
  count: number;
  /** How many of its notifications are on the attached phones now. */
  waiting: number;
}

type Choice = "on" | "content" | "sound" | "calls" | "clearOnPhone";

type Test = { kind: "idle" } | { kind: "sending" } | { kind: "sent"; text: string } | { kind: "failed"; text: string };

/** "Galaxy A13", "Galaxy A13 and Redmi 9A", "3 phones". */
function names(list: string[]): string {
  const n = list.map((name) => name || "your phone");
  if (n.length <= 2) return n.join(" and ");
  return `${n.length} phones`;
}

const phoneNames = (phones: PhoneState[]) => names(phones.map((p) => p.name));

/** When an app last had something to say here, the way a notification center says it. */
function lastHeard(a: NotifyingApp, now: number): string {
  const ms = a.last;
  if (!ms && a.waiting > 0) return `${a.waiting} ${a.waiting === 1 ? "notification" : "notifications"} on your phone`;
  if (!ms) return "Nothing yet";
  const min = Math.round((now - ms) / 60_000);
  if (min < 1) return "Just now";
  if (min < 60) return `${min} min ago`;
  const h = Math.round(min / 60);
  if (h < 24) return `${h} h ago`;
  const days = Math.round(h / 24);
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  return new Date(ms).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

export function NotificationsBell() {
  const [state, setState] = useState<NotificationState | null>(null);
  const [open, setOpen] = useState(false);
  const [ringing, setRinging] = useState(false);
  const ringTimer = useRef(0);

  useEffect(() => {
    invoke<NotificationState>("notifications_state")
      .then(setState)
      .catch((e) => logError(`notifications: ${String(e)}`));
    const unState = listen<NotificationState>("notifications:state", ({ payload }) => setState(payload));
    // A toast's "Settings", or this app's own toast clicked: the panel, open.
    const unPanel = listen("notifications:panel", () => setOpen(true));
    const unArrived = listen("notifications:arrived", () => {
      window.clearTimeout(ringTimer.current);
      setRinging(false);
      requestAnimationFrame(() => setRinging(true));
      ringTimer.current = window.setTimeout(() => setRinging(false), 950);
    });
    return () => {
      unState.then((f) => f());
      unPanel.then((f) => f());
      unArrived.then((f) => f());
      window.clearTimeout(ringTimer.current);
    };
  }, []);

  // Where they cannot come up at all (a Linux computer), no bell: it would lead nowhere.
  if (!state || !state.supported) return null;

  const live = state.phones.filter((p) => p.state === "on");
  const blocked = state.system === "off" || state.system === "unavailable" || state.system === "ask";
  const refused = state.phones.some((p) => p.state === "refused");
  const attention = state.on && (blocked || refused);
  const title = !state.on
    ? "Phone notifications are off"
    : attention
      ? "Phone notifications need your attention"
      : live.length > 0
        ? `Notifications from ${phoneNames(live)} show up here`
        : "Phone notifications: waiting for your phone";

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      label="Phone notifications"
      trigger={({ open, toggle, ref }) => (
        <button
          ref={ref}
          className={`btn btn-quiet btn-sm btn-icon bell ${open ? "is-open" : ""} ${ringing ? "is-ringing" : ""}`}
          onClick={toggle}
          aria-haspopup="dialog"
          aria-expanded={open}
          aria-label={title}
          title={title}
        >
          <Icon name={state.on ? "bell" : "bellOff"} size={17} />
          {state.on && (attention || live.length > 0) && (
            <span className={`bell-dot ${attention ? "is-warn" : ""}`} aria-hidden="true" />
          )}
        </button>
      )}
    >
      <Panel state={state} onState={setState} />
    </Popover>
  );
}

function Panel({ state, onState }: { state: NotificationState; onState: (s: NotificationState) => void }) {
  const [apps, setApps] = useState<NotifyingApp[] | null>(null);
  const [query, setQuery] = useState("");
  const [test, setTest] = useState<Test>({ kind: "idle" });
  const testTimer = useRef(0);
  const now = Date.now();

  const loadApps = useCallback(() => {
    invoke<NotifyingApp[]>("notifications_apps")
      .then(setApps)
      .catch(() => setApps([]));
  }, []);

  useEffect(() => {
    loadApps();
    const un = listen("notifications:apps", loadApps);
    return () => {
      un.then((f) => f());
      window.clearTimeout(testTimer.current);
    };
  }, [loadApps]);

  /** A switch flipped: shown at once, then as the app has it. */
  const choose = (choice: Choice, value: boolean) => {
    onState({ ...state, [choice]: value });
    logInfo(`notifications: ${choice} ${value ? "on" : "off"}`);
    invoke<NotificationState>("notifications_set", { [choice]: value })
      .then(onState)
      .catch((e) => logError(`notifications: ${choice}: ${String(e)}`));
  };

  const mute = (a: NotifyingApp, muted: boolean) => {
    setApps((list) => list?.map((x) => (x.pkg === a.pkg ? { ...x, muted } : x)) ?? list);
    invoke("notifications_mute", { pkg: a.pkg, muted }).catch((e) => logError(`notifications: ${a.pkg}: ${String(e)}`));
  };

  const sendTest = () => {
    window.clearTimeout(testTimer.current);
    setTest({ kind: "sending" });
    invoke<string[]>("notifications_test")
      .then((phones) =>
        setTest({
          kind: "sent",
          text: `Sent from ${names(phones)}`,
        }),
      )
      .catch((e) => setTest({ kind: "failed", text: String(e) }))
      .finally(() => {
        testTimer.current = window.setTimeout(() => setTest({ kind: "idle" }), 8000);
      });
  };

  const fix = () => {
    invoke("notifications_fix").catch((e) => logError(`notifications: ${String(e)}`));
  };

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!apps || !q) return apps ?? [];
    return apps.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q));
  }, [apps, query]);

  const live = state.phones.filter((p) => p.state === "on");
  const starting = state.phones.filter((p) => p.state === "starting");
  const refused = state.phones.filter((p) => p.state === "refused");
  // The system shows none, so no phone is asked for them: the callout below says why.
  const held = live.length === 0 && (state.system === "off" || state.system === "unavailable");
  const settingsName = IS_MAC ? "System Settings" : "Windows settings";

  return (
    <>
      <header className="notif-head">
        <span className={`tile tile-lg ${state.on ? "tile-accent" : ""}`}>
          <Icon name={state.on ? "bell" : "bellOff"} size={19} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="heading">Phone notifications</p>
          <p className="caption mt-0.5 flex min-w-0 items-center gap-1.5">
            {!state.on ? (
              <span className="truncate">Off, so notifications stay on your phone</span>
            ) : held ? (
              <span className="truncate text-[var(--warn)]">{THIS_COMPUTER_CAP} can't show notifications</span>
            ) : live.length > 0 ? (
              <>
                <span className="dot dot-ok dot-live !h-1.5 !w-1.5" aria-hidden="true" />
                <span className="truncate">Connected to {phoneNames(live)}</span>
              </>
            ) : starting.length > 0 ? (
              <>
                <span className="spinner !h-3 !w-3 !border-[1.5px]" aria-hidden="true" />
                <span className="truncate">Connecting to {phoneNames(starting)}</span>
              </>
            ) : refused.length > 0 ? (
              <span className="truncate text-[var(--warn)]">{phoneNames(refused)} can't share notifications</span>
            ) : (
              <span className="truncate">Waiting for your phone</span>
            )}
          </p>
        </div>
        <Switch
          checked={state.on}
          onChange={(v) => choose("on", v)}
          label={`Show my phone's notifications on ${THIS_COMPUTER}`}
        />
      </header>

      {state.on ? (
        <div className="notif-body">
          {state.system !== "ready" && (
            <div className={`callout ${state.system === "ask" ? "" : "callout-warn"}`}>
              <Icon name={state.system === "ask" ? "info" : "alert"} size={16} />
              <div className="min-w-0 flex-1">
                <p>{state.systemMessage}</p>
                {state.system !== "unavailable" && (
                  <button className="link mt-1.5 text-[12.5px]" onClick={fix}>
                    {state.system === "ask" ? "Allow notifications" : `Open ${settingsName}`}
                    <Icon name="arrowUpRight" size={12} />
                  </button>
                )}
              </div>
            </div>
          )}
          {refused.map((p) => (
            <div key={p.phone} className="callout callout-warn">
              <Icon name="alert" size={16} />
              <div className="min-w-0 flex-1 select-text">
                <p>{p.name || "Your phone"} didn't let Open Android DeX read notifications</p>
                {p.message && <p className="mt-1 text-[11.5px] opacity-75">{p.message}</p>}
              </div>
            </div>
          ))}

          <div className="card rows">
            <Option
              icon="eye"
              title="Show the message"
              detail={state.content ? "The sender and the full text" : "Only the app's name"}
              checked={state.content}
              onChange={(v) => choose("content", v)}
            />
            <Option
              icon="call"
              title="Ring when someone calls"
              detail={`Answer or decline on ${THIS_COMPUTER}`}
              checked={state.calls}
              onChange={(v) => choose("calls", v)}
            />
            <Option
              icon="speaker"
              title="Play a sound"
              detail={`${IS_MAC ? "Your Mac's" : "Your PC's"} usual alert sound`}
              checked={state.sound}
              onChange={(v) => choose("sound", v)}
            />
            <Option
              icon="sync"
              title="Clear on my phone too"
              detail={`When you close a notification on ${THIS_COMPUTER}`}
              checked={state.clearOnPhone}
              onChange={(v) => choose("clearOnPhone", v)}
            />
          </div>

          <div>
            <div className="mb-2 flex h-[26px] items-center justify-between gap-3 px-1">
              <p className="section-label">Apps{apps && apps.length > 0 ? ` · ${apps.length}` : ""}</p>
              {apps && apps.length > 6 && (
                <label className="relative">
                  <Icon
                    name="search"
                    size={13}
                    className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--ink-4)]"
                  />
                  <input
                    className="field notif-search"
                    placeholder="Find an app"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Escape" && query) {
                        e.stopPropagation();
                        setQuery("");
                      }
                    }}
                    aria-label="Find an app"
                  />
                </label>
              )}
            </div>
            {apps === null ? (
              <div className="card flex items-center gap-2.5 px-3 py-3">
                <span className="spinner" aria-hidden="true" />
                <span className="caption">Loading apps</span>
              </div>
            ) : apps.length === 0 ? (
              <div className="card px-3.5 py-3">
                <p className="caption">Apps that send you notifications show up here</p>
                <p className="caption mt-1">Turn off any app you don't want to hear from on {THIS_COMPUTER}</p>
              </div>
            ) : shown.length === 0 ? (
              <div className="card px-3.5 py-3">
                <p className="caption">No app called “{query.trim()}”</p>
              </div>
            ) : (
              <div className="card rows notif-apps" role="list" aria-label="Apps that notify you">
                {shown.map((a) => (
                  <div key={a.pkg} role="listitem" className="notif-app" data-muted={a.muted || a.blocked || undefined}>
                    <span className="notif-app-icon">
                      {a.icon ? <img src={a.icon} alt="" draggable={false} /> : <span className="app-icon-blank" />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[13px] font-medium text-[var(--ink-1)]">{a.label}</span>
                      <span className="caption block truncate">
                        {a.blocked
                          ? `Off in ${settingsName}`
                          : a.muted
                            ? `Off on ${THIS_COMPUTER}`
                            : lastHeard(a, now)}
                      </span>
                    </span>
                    <Switch
                      size="sm"
                      checked={!a.muted && !a.blocked}
                      disabled={a.blocked}
                      onChange={(on) => mute(a, !on)}
                      label={`Notifications from ${a.label}`}
                    />
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="caption notif-off">
          <p>See your phone's notifications on {THIS_COMPUTER}</p>
          <p className="mt-1">Reply to messages, and answer or decline calls</p>
        </div>
      )}

      <footer className="notif-foot">
        <button
          className="btn btn-secondary btn-sm"
          onClick={sendTest}
          disabled={!state.on || live.length === 0 || test.kind === "sending"}
          title={
            live.length === 0 ? "Connect your phone first" : `Your phone sends a test notification to ${THIS_COMPUTER}`
          }
        >
          {test.kind === "sending" ? <span className="spinner !h-3 !w-3" aria-hidden="true" /> : <Icon name="send" size={14} />}
          Send a test
        </button>
        <p
          className={`min-w-0 flex-1 text-[11.5px] leading-[15px] ${test.kind === "failed" ? "text-[var(--warn)]" : "text-[var(--ink-3)]"}`}
          role="status"
          aria-live="polite"
        >
          {test.kind === "sent" || test.kind === "failed" ? test.text : ""}
        </p>
        <button className="link muted shrink-0 text-[12px]" onClick={fix} title={`Open ${settingsName}`}>
          {IS_MAC ? "System Settings" : "Settings"}
          <Icon name="arrowUpRight" size={12} />
        </button>
      </footer>
    </>
  );
}

function Option({
  icon,
  title,
  detail,
  checked,
  onChange,
}: {
  icon: IconName;
  title: string;
  detail: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="notif-row" data-off={!checked || undefined}>
      <span className="tile">
        <Icon name={icon} size={15} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[13px] font-medium leading-[18px] text-[var(--ink-1)]">{title}</span>
        <span className="caption block">{detail}</span>
      </span>
      <Switch checked={checked} onChange={onChange} label={title} />
    </div>
  );
}
