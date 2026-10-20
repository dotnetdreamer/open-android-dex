import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { NO_PERMISSIONS_HINT, THIS_COMPUTER, THIS_COMPUTER_CAP } from "../../lib/host";
import type { DeviceInfo } from "../../lib/types";
import { Icon, type IconName } from "../../ui/Icon";
import { Segmented } from "../../ui/kit";
import { Stage, type Scene } from "../../ui/Stage";
import { deviceName, routeName, type AdbState, type Phase, type Progress } from "./model";
import { UsbGuide } from "./UsbGuide";

/**
 * The desktop flow, as one screen whose picture never leaves: the phone and
 * the computer at the top (Stage.tsx) show where things are, one sentence under
 * them says it in words, and there is one obvious thing to press. Everything a
 * developer would want — the log, the diagnostics, the escape hatches for a
 * stuck desktop — is still a click away in the titlebar's help menu, and the
 * escape hatches are on this screen too while a desktop is running.
 *
 * Purely presentational: App.tsx owns the launch pipeline and hands down what
 * it knows and what can be done.
 */

export type Method = "usb" | "wifi";

export interface DesktopActions {
  launch: (d: DeviceInfo) => void;
  /** After a failed launch: launch again if a phone is there, else look again. */
  retry: () => void;
  retryAdb: () => void;
  openLog: () => void;
  diagnostics: () => void;
  showDesktop: (d: DeviceInfo) => void;
  goWireless: (d: DeviceInfo) => void;
  home: () => void;
  closeTop: () => void;
  disconnect: (d: DeviceInfo) => void;
  chooseAnother: () => void;
}

interface Props {
  phase: Phase;
  adb: AdbState;
  reconnecting: boolean;
  progress: Progress;
  /** A running desktop is being moved off the cable onto Wi-Fi. */
  switching: boolean;
  devices: DeviceInfo[];
  /** The phone a launch from here would use: the one asked for, when there is a choice. */
  ready?: DeviceInfo;
  /** The running desktop's stream dropped, and is coming back by itself. */
  stalled: boolean;
  /** Something the launch wants said, which stays as long as the session. */
  notice: string;
  method: Method;
  onMethod: (m: Method) => void;
  actions: DesktopActions;
  /** The Wi-Fi panel, wired to the launch pipeline by App. */
  wireless: ReactNode;
}

export function DesktopPage(props: Props) {
  const view = content(props);
  const { root, block, pad, instant } = useGlideToCentre();
  // The Wi-Fi panel has a place of its own, outside the keyed parts that come
  // and go with the state. Its work outlives the states it passes through: a
  // phone sent to Wi-Fi drops off adb while adbd restarts, and a phone that
  // pairs by QR appears in the list before the pairing has answered — both
  // change what this screen says, and a panel remounted under them would lose
  // the answer it was waiting for.
  const wifi = props.phase.kind === "connect" && props.method === "wifi";

  return (
    <div
      ref={root}
      className="flex min-h-full flex-col px-6 pb-8"
      style={{ paddingTop: pad, transition: instant ? "none" : "padding-top 520ms var(--ease-out)" }}
    >
      <div ref={block} className="flex w-full flex-col items-center">
        <div className="w-[min(380px,74vw)] [@media(max-height:560px)]:w-[min(300px,60vw)]">
          <Stage scene={view.scene} via={view.via} progress={view.step} stalled={props.stalled} />
        </div>
        <div key={view.key} className="rise-in mt-4 flex w-full flex-col items-center text-center">
          <h1 className="display max-w-[520px]">{view.title}</h1>
          {view.lede && <div className="lede mt-2 max-w-[460px]">{view.lede}</div>}
          {view.actions && <div className="mt-5 flex flex-wrap justify-center gap-2.5">{view.actions}</div>}
        </div>
        {view.top && (
          <div key={`${view.key}-top`} className="rise-in mt-6 w-full max-w-[520px] text-left [animation-delay:60ms]">
            {view.top}
          </div>
        )}
        {view.switcher && (
          <div className="mt-6">
            <Segmented
              label="How to connect"
              role="tablist"
              size="lg"
              value={props.method}
              onChange={props.onMethod}
              options={[
                { value: "usb", label: "USB cable", icon: "cable" },
                { value: "wifi", label: "Wi‑Fi", icon: "wifi" },
              ]}
            />
          </div>
        )}
        {view.body && (
          <div key={`${view.key}-body`} className="rise-in mt-5 w-full max-w-[520px] text-left [animation-delay:60ms]">
            {view.body}
          </div>
        )}
        {wifi && <div className="mt-5 w-full max-w-[520px] text-left">{props.wireless}</div>}
      </div>
    </div>
  );
}

interface View {
  key: string;
  /** What the picture shows. */
  scene: Scene;
  via: "usb" | "wifi";
  step?: number;
  title: ReactNode;
  lede?: ReactNode;
  actions?: ReactNode;
  /** Under the words, above the cable / Wi-Fi choice: what went wrong, in detail. */
  top?: ReactNode;
  /** Offer the cable / Wi-Fi choice. */
  switcher?: boolean;
  /** Under the choice. */
  body?: ReactNode;
}

function content(props: Props): View {
  const { phase, adb, reconnecting, progress, switching, devices, ready, stalled, notice, method, actions } = props;
  // A phone adb can see but not use, by the way it is attached: an offline
  // Wi-Fi entry is a phone that left the network, not one to unplug.
  const pendingUsb = devices.find((d) => d.state !== "device" && d.connection === "usb");
  const pendingWifi = devices.find((d) => d.state !== "device" && d.connection === "wifi");
  const asking = (d: DeviceInfo | undefined) => d?.state === "unauthorized" || d?.state === "authorizing";

  switch (phase.kind) {
    case "boot": {
      if (adb.state === "error") {
        return {
          key: "boot-error",
          scene: "broken",
          via: "usb",
          title: "Open Android DeX couldn't start",
          lede: "The part of the app that talks to your phone didn't start. Try again — and if it keeps happening, restart the app.",
          actions: (
            <>
              <button className="btn btn-primary btn-lg" onClick={actions.retryAdb}>
                <Icon name="refresh" size={16} />
                Try again
              </button>
              <button className="btn btn-secondary btn-lg" onClick={actions.openLog}>
                <Icon name="doc" size={16} />
                Open log
              </button>
            </>
          ),
          top: <Problem text={adb.error} />,
        };
      }
      return reconnecting
        ? {
            key: "boot-reconnect",
            scene: "searching",
            via: "wifi",
            title: "Welcome back",
            lede: <Working>Reconnecting to your phone over Wi‑Fi…</Working>,
          }
        : {
            key: "boot",
            scene: "waiting",
            via: "usb",
            title: "Open Android DeX",
            lede: <Working>{adb.state === "busy" ? "Starting up…" : "Looking for your phone…"}</Working>,
          };
    }

    case "connect": {
      if (phase.error) {
        const [first, ...rest] = phase.error.split("\n");
        const more = rest.join("\n").trim();
        return {
          key: "connect-error",
          scene: "error",
          via: ready?.connection ?? method,
          title: "The desktop didn't open",
          lede: first,
          actions: (
            <>
              <button className="btn btn-primary btn-lg" onClick={actions.retry}>
                <Icon name="refresh" size={16} />
                Try again
              </button>
              <button className="btn btn-secondary btn-lg" onClick={actions.openLog}>
                <Icon name="doc" size={16} />
                Open log
              </button>
            </>
          ),
          // What to do, then the other ways in: a phone that fails the same
          // way on every try is not helped by "Try again" alone.
          top: (
            <div className="flex flex-col gap-3">
              {more && (
                <div className="card px-4 py-3.5">
                  <p className="section-label mb-2">What to do</p>
                  <p className="body whitespace-pre-wrap select-text">{more}</p>
                </div>
              )}
              <p className="caption text-center">
                Every step of this attempt is in the log. If it keeps failing,{" "}
                <button className="link text-[12px]" onClick={actions.diagnostics}>
                  create a diagnostics report
                </button>{" "}
                and attach it to a bug report — or connect another way below.
              </p>
            </div>
          ),
          switcher: true,
        };
      }

      if (ready) {
        return {
          key: `connect-ready-${ready.serial}`,
          scene: "ended",
          via: ready.connection,
          title: `${deviceName(ready)} is ready`,
          lede: ready.connection === "wifi" ? "Connected over Wi‑Fi." : "Connected with a USB cable.",
          actions: (
            <button className="btn btn-primary btn-lg" onClick={() => actions.launch(ready)}>
              <Icon name="play" size={15} />
              Open desktop
            </button>
          ),
          switcher: true,
        };
      }

      if (method === "usb") {
        if (pendingUsb && asking(pendingUsb)) {
          return {
            key: "connect-allow",
            scene: "allow",
            via: "usb",
            title: "Tap “Allow” on your phone",
            lede: (
              <>
                Your phone is asking if it can trust {THIS_COMPUTER}. Tick{" "}
                <em className="not-italic text-[var(--ink-1)]">Always allow from this computer</em> so it won't ask again.
              </>
            ),
            switcher: true,
            body: (
              <>
                <Checklist states={["done", "done", "active"]} />
                <p className="caption mt-3 text-center">Don't see the question? Unlock your phone, or unplug it and plug it back in.</p>
              </>
            ),
          };
        }
        if (pendingUsb) {
          const [title, lede] =
            pendingUsb.state === "no-permissions"
              ? [`${THIS_COMPUTER_CAP} can't open your phone yet`, NO_PERMISSIONS_HINT]
              : pendingUsb.state === "offline"
                ? [
                    "Your phone isn't responding",
                    "Unplug it, wait a few seconds, and plug it back in. If it keeps happening, try another cable or USB port.",
                  ]
                : ["Your phone is connecting…", `It says it is in “${pendingUsb.state}” mode. If this doesn't change, restart the phone.`];
          return {
            key: `connect-${pendingUsb.state}`,
            scene: "alert",
            via: "usb",
            title,
            lede,
            switcher: true,
            body: <Checklist states={["done", "active", "todo"]} />,
          };
        }
        return {
          key: "connect-usb",
          scene: "waiting",
          via: "usb",
          title: "Connect your phone",
          lede: `Plug it into ${THIS_COMPUTER} with a USB cable, and its desktop opens here by itself.`,
          switcher: true,
          body: (
            <>
              <Checklist states={["todo", "todo", "todo"]} />
              <p className="caption mt-3 flex items-center justify-center gap-2">
                <span className="dot dot-accent dot-live" aria-hidden="true" />
                Looking for your phone…
              </p>
            </>
          ),
        };
      }

      // Over Wi-Fi. A phone adb reaches there but cannot use is said here,
      // above the panel, in the network's terms.
      return asking(pendingWifi)
        ? {
            key: "connect-wifi-allow",
            scene: "allow",
            via: "wifi",
            title: "Tap “Allow” on your phone",
            lede: `Your phone is asking if it can trust ${THIS_COMPUTER}. Tick “Always allow from this computer” so it won't ask again.`,
            switcher: true,
          }
        : {
            key: "connect-wifi",
            scene: "searching",
            via: "wifi",
            title: "Connect over Wi‑Fi",
            lede: `Your phone and ${THIS_COMPUTER} need to be on the same Wi‑Fi network.`,
            switcher: true,
            body: pendingWifi && (
              <Callout
                tone="warn"
                text={
                  pendingWifi.state === "offline"
                    ? `The phone at ${pendingWifi.serial} isn't answering. Wake it up and check it's on the same network — or connect it again below.`
                    : `The phone at ${pendingWifi.serial} says it is “${pendingWifi.state}”. Connect it again below if it doesn't come back.`
                }
              />
            ),
          };
    }

    case "launching": {
      const d = phase.device;
      const title =
        progress.mode === "again"
          ? "Setting your desktop up again"
          : progress.mode === "settings"
            ? "Restarting your desktop"
            : progress.mode === "wireless"
              ? "Reconnecting over Wi‑Fi"
              : "Opening your desktop";
      return {
        key: `launching-${progress.mode}`,
        scene: "launching",
        via: d.connection,
        step: progress.step,
        title,
        lede: <DeviceChip device={d} />,
        top: (
          <div className="flex flex-col gap-3">
            {notice && <Callout tone="warn" text={notice} />}
            <Progressive progress={progress} />
            <p className="caption text-center">
              {progress.mode === "wireless"
                ? "You can unplug the cable now."
                : "Usually a few seconds. The very first time takes longer while the desktop installs on your phone."}
            </p>
          </div>
        ),
      };
    }

    case "ready": {
      const d = phase.device;
      if (switching) {
        return {
          key: "ready-switching",
          scene: "launching",
          via: "wifi",
          step: 0,
          title: "Moving to Wi‑Fi",
          lede: "Keep the cable plugged in for a moment — the desktop comes back over Wi‑Fi in a few seconds.",
          top: (
            <div className="card flex items-center gap-3 px-4 py-3.5">
              <span className="spinner" aria-hidden="true" />
              <span className="body">Switching {deviceName(d)} to Wi‑Fi…</span>
            </div>
          ),
        };
      }
      return {
        key: `ready-${d.serial}-${stalled ? "stalled" : "live"}`,
        scene: "live",
        via: d.connection,
        step: 3,
        title: stalled ? "Reconnecting to your desktop" : "Your desktop is running",
        lede: <DeviceChip device={d} tone={stalled ? "warn" : "live"} />,
        actions: (
          <>
            <button className="btn btn-primary btn-lg" onClick={() => actions.showDesktop(d)}>
              <Icon name="monitor" size={16} />
              Show desktop
            </button>
            <button className="btn btn-danger btn-lg" onClick={() => actions.disconnect(d)} title="Close the desktop and put the phone back to normal">
              <Icon name="power" size={16} />
              Disconnect
            </button>
          </>
        ),
        top: (
          <div className="flex flex-col gap-3">
            {notice && <Callout tone="warn" text={notice} />}
            <div className="card rows">
              {d.connection === "usb" && (
                <ActionRow
                  icon="wifi"
                  title="Go wireless"
                  detail="Move to Wi‑Fi, then unplug the cable"
                  onClick={() => actions.goWireless(d)}
                />
              )}
              <ActionRow
                icon="home"
                title="Home screen"
                detail="Bring the desktop back in front of a full-screen app"
                onClick={actions.home}
              />
              <ActionRow
                icon="windowClose"
                title="Close the front app"
                detail="For an app that has no close button"
                onClick={actions.closeTop}
              />
            </div>
          </div>
        ),
      };
    }

    case "ended": {
      const d = phase.device;
      return {
        key: "ended",
        scene: "ended",
        via: d.connection,
        title: phase.requested ? "DeX is closed" : "The desktop was closed",
        lede: "Your phone is back to normal.",
        actions: (
          <>
            <button className="btn btn-primary btn-lg" onClick={() => actions.launch(d)}>
              <Icon name="play" size={15} />
              Open desktop again
            </button>
            <button className="btn btn-secondary btn-lg" onClick={actions.chooseAnother}>
              Use another phone
            </button>
          </>
        ),
      };
    }
  }
}

// ── pieces ─────────────────────────────────────────────────────────────

/**
 * Keeps the screen's content in the middle of the window, and glides it there
 * when the content changes size — a checklist arriving under the start-up
 * screen, a guide opening — instead of jumping. Auto margins would centre it
 * too, but they cannot be animated, and the picture leaping half a window
 * between two states reads as two different screens rather than one changing.
 * A resized window is followed at once: easing behind the pointer feels slow.
 */
function useGlideToCentre() {
  const root = useRef<HTMLDivElement>(null);
  const block = useRef<HTMLDivElement>(null);
  const [state, setState] = useState({ pad: 4, instant: true });

  useLayoutEffect(() => {
    const box = root.current?.closest("main");
    const content = block.current;
    if (!box || !content) return;
    let lastBox = -1;
    const place = () => {
      const available = box.clientHeight;
      const resized = available !== lastBox;
      const first = lastBox === -1;
      lastBox = available;
      // Never closer to the titlebar than 4px; never so low the bottom padding is squeezed.
      const pad = Math.max(4, Math.round((available - content.offsetHeight - 32) / 2));
      setState((s) => (s.pad === pad ? s : { pad, instant: first || resized }));
    };
    place();
    const ro = new ResizeObserver(place);
    ro.observe(box);
    ro.observe(content);
    return () => ro.disconnect();
  }, []);

  return { root, block, ...state };
}

/** A sentence that is still happening. */
function Working({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2.5">
      <span className="spinner" aria-hidden="true" />
      {children}
    </span>
  );
}

/** The phone and how it is attached; with a dot for how the session is, while there is one. */
function DeviceChip({ device, tone }: { device: DeviceInfo; tone?: "live" | "warn" }) {
  return (
    <span className="mt-0.5 inline-flex items-center gap-2 text-[13.5px]">
      {tone ? (
        <span className={`dot ${tone === "live" ? "dot-ok" : "dot-warn dot-live"}`} aria-hidden="true" />
      ) : (
        <Icon name="phone" size={15} className="text-[var(--ink-3)]" />
      )}
      <span className="font-semibold text-[var(--ink-1)]">{deviceName(device)}</span>
      <span className="text-[var(--ink-4)]">·</span>
      <span className="inline-flex items-center gap-1.5">
        <Icon name={device.connection === "wifi" ? "wifi" : "cable"} size={14} className="text-[var(--ink-3)]" />
        {routeName(device)}
      </span>
    </span>
  );
}

type StepState = "todo" | "active" | "done";

/** The three things that stand between a phone and its desktop, ticked off as the app sees them happen. */
function Checklist({ states }: { states: [StepState, StepState, StepState] }) {
  const [guide, setGuide] = useState(false);
  return (
    <div className="card steps rows">
      <div className="step" data-state={states[0]}>
        <StepMarker n={1} state={states[0]} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="step-title">Turn on USB debugging</p>
              <p className="step-detail">A one-time switch in your phone's settings.</p>
            </div>
            <button className="link mt-0.5 shrink-0 text-[12.5px]" aria-expanded={guide} onClick={() => setGuide((g) => !g)}>
              {guide ? "Hide" : "Show me how"}
              <Icon name="chevronDown" size={13} className={`transition-transform duration-200 ${guide ? "rotate-180" : ""}`} />
            </button>
          </div>
          {guide && (
            <div className="rise-in mt-3.5">
              <UsbGuide />
            </div>
          )}
        </div>
      </div>
      <div className="step" data-state={states[1]}>
        <StepMarker n={2} state={states[1]} />
        <div>
          <p className="step-title">Plug your phone in</p>
          <p className="step-detail">Use a cable that carries data — some charging-only cables don't.</p>
        </div>
      </div>
      <div className="step" data-state={states[2]}>
        <StepMarker n={3} state={states[2]} />
        <div>
          <p className="step-title">Tap “Allow” on your phone</p>
          <p className="step-detail">Tick “Always allow from this computer” and you won't be asked again.</p>
        </div>
      </div>
    </div>
  );
}

function StepMarker({ n, state }: { n: number; state: StepState }) {
  return <span className="step-marker">{state === "done" ? <Icon name="check" size={13} strokeWidth={2.4} /> : n}</span>;
}

/** The launch, in the three parts a person can picture. */
function Progressive({ progress }: { progress: Progress }) {
  const parts: { title: string; detail: string }[] = [
    { title: "Connecting to your phone", detail: "" },
    { title: "Making room for the desktop", detail: "A screen of its own, the size of this one" },
    {
      title: "Starting the desktop",
      detail: progress.waiting ? "Waiting for your phone to come back…" : "Taskbar, windows and your apps",
    },
  ];
  return (
    <div className="card steps steps-threaded">
      {parts.map((p, i) => {
        const state: StepState = progress.step > i ? "done" : progress.step === i ? "active" : "todo";
        return (
          <div key={p.title} className="step" data-state={state}>
            <span className={`step-marker ${state === "active" ? "orbit" : ""}`}>
              {state === "done" ? <Icon name="check" size={13} strokeWidth={2.4} /> : i + 1}
            </span>
            <div>
              <p className={`step-title ${state === "todo" ? "!text-[var(--ink-3)]" : ""}`}>{p.title}</p>
              {state === "active" && p.detail && (
                <p className={`step-detail ${progress.waiting && i === 2 ? "!text-[var(--warn)]" : ""}`}>{p.detail}</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function ActionRow({ icon, title, detail, onClick }: { icon: IconName; title: string; detail: string; onClick: () => void }) {
  return (
    <button className="row-btn group" onClick={onClick}>
      <span className="tile">
        <Icon name={icon} size={17} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="heading block">{title}</span>
        <span className="caption block">{detail}</span>
      </span>
      <Icon
        name="chevronRight"
        size={15}
        className="text-[var(--ink-4)] transition-transform duration-150 group-hover:translate-x-0.5 group-hover:text-[var(--ink-2)]"
      />
    </button>
  );
}

export function Callout({ tone, text, icon }: { tone: "warn" | "ok" | "bad" | "info"; text: ReactNode; icon?: IconName }) {
  return (
    <div className={`callout ${tone === "info" ? "" : `callout-${tone}`}`}>
      <Icon name={icon ?? (tone === "ok" ? "check" : tone === "info" ? "info" : "alert")} size={16} />
      <div className="min-w-0 flex-1 select-text">{text}</div>
    </div>
  );
}

/** A raw error, for the log-minded, under the plain-words version. */
function Problem({ text }: { text: string }) {
  return (
    <div className="card px-4 py-3.5">
      <p className="section-label mb-2">What went wrong</p>
      <pre className="console">{text}</pre>
    </div>
  );
}
